package mod.azure.xenogenesis.ai.util;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.AmbientCreature;

import java.util.*;

import mod.azure.xenogenesis.ai.core.AiKeys;
import mod.azure.xenogenesis.ai.core.Blackboard;
import mod.azure.xenogenesis.entities.AbstractAlienEntity;
import mod.azure.xenogenesis.entities.facehugger.FacehuggerEntity;
import mod.azure.xenogenesis.util.ModTags;

@SuppressWarnings("unused")
public final class FacehuggerHostileTargetSelector<E extends FacehuggerEntity> implements TargetSelector<E> {

    private final double range;

    private final Map<UUID, Integer> suspicionMap = new HashMap<>();

    public FacehuggerHostileTargetSelector(double range) {
        this.range = range;
    }

    public void hearSound(LivingEntity candidate, int suspicion) {
        var current = suspicionMap.getOrDefault(candidate.getUUID(), 0);
        suspicionMap.put(candidate.getUUID(), Math.min(current + suspicion, 150));
    }

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
            mob.getBoundingBox().inflate(range),
            candidate -> isValidCandidate(mob, candidate)
        );

        var seenThisTick = new HashSet<UUID>();

        for (var candidate : nearby) {
            var uuid = candidate.getUUID();
            if (mob.hasLineOfSight(candidate)) {
                seenThisTick.add(uuid);
                var prev = suspicionMap.getOrDefault(uuid, 0);
                suspicionMap.put(uuid, Math.min(prev + 2, 150));
            }
        }

        suspicionMap.entrySet().removeIf(entry -> {
            if (!seenThisTick.contains(entry.getKey())) {
                var decayed = entry.getValue() - 1;
                if (decayed <= 0)
                    return true;
                entry.setValue(decayed);
            }
            return false;
        });

        return nearby.stream()
            .filter(c -> suspicionMap.getOrDefault(c.getUUID(), 0) >= 80)
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
        if (candidate.getVehicle() instanceof AbstractAlienEntity)
            return false;
        if (candidate instanceof AbstractAlienEntity)
            return false;
        return TargetingUtils.baseValid(mob).test(candidate)
            && TargetingUtils.notAnnoyingMobs().test(candidate)
            && mob.distanceToSqr(candidate) <= range * range;
    }
}
