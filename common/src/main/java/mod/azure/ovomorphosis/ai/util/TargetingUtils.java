package mod.azure.ovomorphosis.ai.util;

import com.azure.azurecortex.api.blackboard.Blackboard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;

import java.util.function.Predicate;

import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.entities.AbstractAlienEntity;
import mod.azure.ovomorphosis.entities.facehugger.FacehuggerEntity;
import mod.azure.ovomorphosis.infection.InfectionManager;
import mod.azure.ovomorphosis.registry.BlockRegistry;
import mod.azure.ovomorphosis.util.ModTags;

/**
 * Factory class for composable {@link Predicate}s used to validate and filter potential targets.
 * <p>
 * Predicates are deliberately kept separate so that callers can mix and match them without duplicating filtering logic.
 */
public final class TargetingUtils {

    private TargetingUtils() {}

    /**
     * Returns the standard compound predicate used to decide whether an entity is a valid target for {@code mob}.
     * <p>
     * Combines {@link #baseValid}, {@link #notAnnoyingMobs}, {@link #notExploding}, and {@link #inRangeOrVisible}.
     *
     * @param mob the mob evaluating the candidate
     * @return a predicate that returns {@code true} for acceptable targets
     */
    public static Predicate<LivingEntity> validTarget(LivingEntity mob) {
        return baseValid(mob)
            .and(notAnnoyingMobs())
            .and(notExploding())
            .and(inRangeOrVisible(mob));
    }

    public static Predicate<LivingEntity> validTargetSmallDistance(LivingEntity mob) {
        return baseValid(mob)
            .and(notAnnoyingMobs())
            .and(notExploding())
            .and(inRangeOrVisibleSmall(mob));
    }

    /**
     * Returns a predicate that accepts entities that are alive, are not the mob itself, are not spectators, are not
     * creative-mode players, and are not of the same entity type as the mob.
     *
     * @param mob the mob evaluating the candidate
     * @return the base validity predicate
     */
    public static Predicate<LivingEntity> baseValid(LivingEntity mob) {
        return e -> e != null &&
            e.isAlive() &&
            e != mob &&
            !e.isSpectator() &&
            !(e instanceof Player p && (p.isCreative() || p.isSpectator())) &&
            e.getType() != mob.getType() &&
            !(e instanceof AbstractAlienEntity) &&
            e.getPassengers().stream().noneMatch(AbstractAlienEntity.class::isInstance) &&
            !e.hasControllingPassenger() &&
            (!e.isPassenger() || !(e.getVehicle() instanceof AbstractAlienEntity)) &&
            e.getFeetBlockState() != BlockRegistry.RESIN_WEB_CROSS.get().defaultBlockState() &&
            !InfectionManager.isInfected(e);
    }

    /**
     * Returns a predicate that rejects bats, water animals, and entities currently in water — mobs that would be
     * impractical to fight in normal combat.
     *
     * @return the "not annoying" filter predicate
     */
    public static Predicate<LivingEntity> eggmorphValid() {
        return e -> e != null &&
            e.isAlive() &&
            !e.isSpectator() &&
            !(e instanceof Player p && (p.isCreative() || p.isSpectator())) &&
            !(e instanceof AbstractAlienEntity) &&
            e.getPassengers().stream().noneMatch(AbstractAlienEntity.class::isInstance) &&
            !e.hasControllingPassenger() &&
            !InfectionManager.isInfected(e);
    }

    public static Predicate<LivingEntity> notAnnoyingMobs() {
        return e -> !(e instanceof Bat || e instanceof Phantom) &&
            !(e instanceof WaterAnimal);
    }

    /**
     * Returns a predicate that rejects {@link Creeper}s that are ignited or already swelling, preventing mobs from
     * attacking a creeper about to explode.
     *
     * @return the "not exploding" filter predicate
     */
    public static Predicate<LivingEntity> notExploding() {
        return e -> !(e instanceof Creeper c && (c.isIgnited() || c.getSwellDir() > 0));
    }

    /**
     * Returns a predicate that accepts entities within 64 blocks (squared distance ≤ 4096) or within line of sight of
     * the mob, regardless of distance.
     * <p>
     * Crouching entities are exempt from the range fallback: a crouching candidate is only accepted while the mob has
     * direct, unobstructed line of sight to it, no matter how close it is. This lets a player (or other crouching
     * entity) break targeting/aggro by sneaking out of sight, instead of remaining trackable purely by proximity.
     *
     * @param mob the mob checking visibility
     * @return the range-or-visibility filter predicate
     */
    public static Predicate<LivingEntity> inRangeOrVisible(LivingEntity mob) {
        return e -> {
            if (e.isCrouching()) {
                return mob.hasLineOfSight(e);
            }
            double dist = mob.distanceToSqr(e);
            return dist <= 4096 || mob.hasLineOfSight(e);
        };
    }

    public static Predicate<LivingEntity> inRangeOrVisibleSmall(LivingEntity mob) {
        return e -> {
            var dist = mob.distanceToSqr(e);
            return dist <= 1024;
        };
    }

    /**
     * Returns {@code true} if the mob's bounding box, inflated by {@code reach}, intersects the target's bounding box.
     *
     * @param mob    the attacking mob
     * @param target the entity being checked
     * @param reach  additional reach in blocks beyond the mob's own hitbox
     * @return {@code true} if the target is within melee range
     */
    public static boolean isInAttackRange(Mob mob, LivingEntity target, double reach) {
        return mob.getBoundingBox()
            .inflate(reach)
            .intersects(target.getBoundingBox());
    }

    public static boolean faceHuggerTest(LivingEntity self, LivingEntity target) {
        if (target instanceof AmbientCreature)
            return false;

        if (target instanceof Allay)
            return false;

        if (target instanceof Vex)
            return false;

        if (target.getType().is(ModTags.UNDEAD))
            return false;

        if (target instanceof AbstractAlienEntity)
            return false;

        if (target.getType().is(ModTags.FACEHUGGER_BLACKLIST))
            return false;

        if (!(target.getType().is(ModTags.XENOMORPH_HOST) || target.getType().is(ModTags.RUNNER_HOST)))
            return false;

        if (isFacehuggerAttached(target))
            return false;

        if (InfectionManager.isInfected(target))
            return false;

        return validTargetSmallDistance(self).test(target);
    }

    public static boolean isFacehuggerAttached(Entity entity) {
        return (entity != null && entity.getPassengers().stream().anyMatch(FacehuggerEntity.class::isInstance));
    }

    public static boolean hasMeleeLineOfSight(Mob mob, LivingEntity target) {
        var level = mob.level();

        var from = mob.getEyePosition();
        var to = target.getEyePosition();

        var hit = level.clip(
            new ClipContext(
                from,
                to,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                mob
            )
        );

        return hit.getType() == HitResult.Type.MISS;
    }

    public static boolean hasNearbyWebCross(Blackboard blackboard, AbstractAlienEntity xenomorph) {
        var memory = blackboard.get(AiKeys.HIVE_MEMORY);
        if (memory == null)
            return false;
        return memory.findNearestOwnedWebCross(xenomorph.level(), xenomorph.blockPosition(), 80.0D)
            .isPresent();
    }

    public static BlockPos findNearbyGroundPos(AbstractAlienEntity mob) {
        var level = mob.level();
        var origin = mob.blockPosition();

        for (var dy = 1; dy <= 16; dy++) {
            var candidate = origin.below(dy);
            var below = candidate.below();
            if (
                level.getBlockState(candidate).getCollisionShape(level, candidate).isEmpty()
                    && level.getBlockState(candidate).getFluidState().isEmpty()
                    && !level.getBlockState(below).getCollisionShape(level, below).isEmpty()
            ) {
                return candidate;
            }
        }

        int[][] lateralDirs = {
            { 1, 0 },
            { -1, 0 },
            { 0, 1 },
            { 0, -1 },
            { 1, 1 },
            { 1, -1 },
            { -1, 1 },
            { -1, -1 }
        };
        for (var radius = 1; radius <= 24; radius++) {
            for (var dir : lateralDirs) {
                var lateral = origin.offset(dir[0] * radius, 0, dir[1] * radius);
                for (var dy = 0; dy <= 16; dy++) {
                    var candidate = lateral.below(dy);
                    var below = candidate.below();
                    if (
                        level.getBlockState(candidate).getCollisionShape(level, candidate).isEmpty()
                            && level.getBlockState(candidate).getFluidState().isEmpty()
                            && !level.getBlockState(below).getCollisionShape(level, below).isEmpty()
                    ) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static <T> T self(Object object) {
        return (T) object;
    }
}
