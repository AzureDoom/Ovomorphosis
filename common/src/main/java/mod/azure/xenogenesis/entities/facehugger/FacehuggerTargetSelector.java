package mod.azure.xenogenesis.entities.facehugger;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.monster.warden.Warden;

import java.util.*;

import mod.azure.xenogenesis.ai.core.AiKeys;
import mod.azure.xenogenesis.ai.core.Blackboard;
import mod.azure.xenogenesis.ai.util.TargetSelector;
import mod.azure.xenogenesis.ai.util.TargetingUtils;
import mod.azure.xenogenesis.entities.AbstractAlienEntity;
import mod.azure.xenogenesis.util.ModTags;

/**
 * A sound-based {@link TargetSelector} for the {@link FacehuggerEntity}, modeled loosely on the Vanilla {@link Warden}
 * anger system.
 * <p>
 * Each potential target accumulates <em>suspicion</em> points over time. Sounds increase suspicion; line-of-sight adds
 * additional weight each tick while the entity can be seen. Once suspicion for a candidate exceeds
 * {@value #SUSPICION_THRESHOLD} the facehugger locks onto that target. Suspicion decays gradually when the candidate is
 * neither seen nor heard.
 * <h3>Filtering</h3> A candidate is only considered when <em>all</em> of the following hold:
 * <ul>
 * <li>Passes {@link TargetingUtils#faceHuggerTest} (alive, not blacklisted, not already hugged, not carried by an
 * {@link AbstractAlienEntity}, not creative/spectator, etc.)
 * <li>Is not itself an {@link AmbientCreature}
 * <li>Is within {@value #HEAR_RANGE} blocks
 * </ul>
 * <p>
 * The selector is designed to be called every tick from the behavior tree. Call {@link #hearSound(LivingEntity, int)}
 * from wherever your sound-event hooks fire to inject suspicion from outside the tick loop.
 */
@SuppressWarnings("unused")
public final class FacehuggerTargetSelector<E extends FacehuggerEntity> implements TargetSelector<E> {

    private static final double HEAR_RANGE = 16.0D;

    private static final int LOS_SUSPICION_PER_TICK = 2;

    public static final int DEFAULT_SOUND_SUSPICION = 10;

    private static final int SUSPICION_THRESHOLD = 80;

    private static final int DECAY_PER_TICK = 1;

    private static final int MAX_SUSPICION = 150;

    private final Map<UUID, Integer> suspicionMap = new HashMap<>();

    /**
     * Injects suspicion for a candidate that made a noise. Should be called from your sound-listener / game-event
     * subscriber whenever a relevant sound is detected nearby.
     *
     * @param candidate the entity that produced the sound
     * @param suspicion amount to add (use {@link #DEFAULT_SOUND_SUSPICION} for footsteps)
     */
    public void hearSound(LivingEntity candidate, int suspicion) {
        var current = suspicionMap.getOrDefault(candidate.getUUID(), 0);
        suspicionMap.put(candidate.getUUID(), Math.min(current + suspicion, MAX_SUSPICION));
    }

    /**
     * {@inheritDoc}
     * <p>
     * Each tick this method:
     * <ol>
     * <li>Retains the current blackboard target if it is still valid.
     * <li>Scans nearby {@link LivingEntity} instances within {@value #HEAR_RANGE} blocks.
     * <li>Increments suspicion for any candidate currently in line-of-sight.
     * <li>Decays suspicion for candidates that are neither seen nor recently heard.
     * <li>Returns the candidate with suspicion ≥ {@value #SUSPICION_THRESHOLD} (highest suspicion wins if multiple
     * qualify), or {@code null} if none yet qualifies.
     * </ol>
     */
    @Override
    public LivingEntity findTarget(E mob, Blackboard blackboard) {
        var current = blackboard.get(AiKeys.TARGET, LivingEntity.class);
        if (
            current != null && current.isAlive()
                && !(current instanceof AbstractAlienEntity)
                && TargetingUtils.faceHuggerTest(mob, current)
        ) {
            return current;
        }

        var level = mob.level();

        var nearby = level.getEntitiesOfClass(
            LivingEntity.class,
            mob.getBoundingBox().inflate(HEAR_RANGE),
            candidate -> isValidCandidate(mob, candidate)
        );

        var seenThisTick = new HashSet<UUID>();

        for (var candidate : nearby) {
            var uuid = candidate.getUUID();

            if (mob.hasLineOfSight(candidate)) {
                seenThisTick.add(uuid);
                var prev = suspicionMap.getOrDefault(uuid, 0);
                suspicionMap.put(uuid, Math.min(prev + LOS_SUSPICION_PER_TICK, MAX_SUSPICION));
            }
        }

        suspicionMap.entrySet().removeIf(entry -> {
            if (!seenThisTick.contains(entry.getKey())) {
                var decayed = entry.getValue() - DECAY_PER_TICK;
                if (decayed <= 0) {
                    return true; // remove
                }
                entry.setValue(decayed);
            }
            return false;
        });

        return nearby.stream()
            .filter(c -> suspicionMap.getOrDefault(c.getUUID(), 0) >= SUSPICION_THRESHOLD)
            .min(Comparator.comparingInt(c -> -suspicionMap.getOrDefault(c.getUUID(), 0)))
            .orElse(null);
    }

    private boolean isValidCandidate(E mob, LivingEntity candidate) {
        if (candidate instanceof AmbientCreature)
            return false;
        if (candidate.getType().is(ModTags.FACEHUGGER_BLACKLIST))
            return false;
        if (TargetingUtils.isFacehuggerAttached(candidate))
            return false;
        if (isCarriedByAlien(candidate))
            return false;
        if (candidate instanceof AbstractAlienEntity)
            return false;
        return TargetingUtils.baseValid(mob).test(candidate)
            && TargetingUtils.notAnnoyingMobs().test(candidate)
            && mob.distanceToSqr(candidate) <= HEAR_RANGE * HEAR_RANGE;
    }

    private boolean isCarriedByAlien(LivingEntity entity) {
        var vehicle = entity.getVehicle();
        return vehicle instanceof AbstractAlienEntity;
    }
}
