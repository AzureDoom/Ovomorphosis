package mod.azure.ovomorphosis.ai.actions;

import com.azure.azurecortex.api.action.Action;
import com.azure.azurecortex.api.action.ActionOutcome;
import com.azure.azurecortex.api.action.ActionStatus;
import com.azure.azurecortex.api.blackboard.Blackboard;
import com.azure.azurecortex.api.blackboard.CommonBlackboardKeys;
import com.azure.azurecortex.config.CortexConfig;
import com.azure.azurecortex.goap.PlanFailureReason;
import com.azure.azurecortex.navigation.movement.MovementController;
import com.azure.azurecortex.navigation.traversal.TraversalQueries;
import com.azure.azurecortex.runtime.CooldownTracker;
import com.azure.azurecortex.runtime.CortexDebug;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import mod.azure.ovomorphosis.ai.core.AiKeys;

public final class WanderAction<E extends Mob, G> implements Action<E, G> {

    private final double speed;

    private final int priority;

    private final int minDuration;

    private final int maxDuration;

    private final double radius;

    private final boolean preferDark;

    private final int[] steerBias = { 0 };

    private Vec3 destination;

    private int age;

    private int duration;

    public WanderAction(double speed, int priority, double radius, int minDuration, int maxDuration) {
        this(speed, priority, radius, minDuration, maxDuration, false);
    }

    public WanderAction(
        double speed,
        int priority,
        double radius,
        int minDuration,
        int maxDuration,
        boolean preferDark
    ) {
        this.speed = speed;
        this.priority = priority;
        this.radius = radius;
        this.minDuration = minDuration;
        this.maxDuration = maxDuration;
        this.preferDark = preferDark;
    }

    @Override
    public void start(E mob, Blackboard blackboard, CooldownTracker cooldowns) {
        this.age = 0;
        this.duration = minDuration + mob.getRandom().nextInt(maxDuration - minDuration + 1);
        this.destination = pickDestination(mob, blackboard);
        mob.setAggressive(false);
        cooldowns.set(CommonBlackboardKeys.PASSIVE_DECISION, 180);
    }

    @Override
    public ActionOutcome<G> tick(E mob, Blackboard blackboard, CooldownTracker cooldowns) {
        if (mob.getHealth() <= 0) {
            return ActionOutcome.failed();
        }

        if (!mob.getPassengers().isEmpty()) {
            return ActionOutcome.failed();
        }

        var target = blackboard.get(CommonBlackboardKeys.TARGET);
        if (target != null && target.isAlive()) {
            return ActionOutcome.success();
        }

        age++;

        if (destination == null) {
            destination = pickDestination(mob, blackboard);
            if (destination == null) {
                return ActionOutcome.running();
            }
        }

        var delta = destination.subtract(mob.position());
        if (delta.lengthSqr() < 0.5D || age >= duration) {
            return ActionOutcome.success();
        }

        var movement = delta.normalize().scale(speed);
        var safeMovement = MovementController.findSafeMovement(mob, movement, steerBias);

        if (safeMovement.equals(Vec3.ZERO)) {
            return ActionOutcome.failed(PlanFailureReason.FAILED_STUCK);
        }

        mob.setDeltaMovement(safeMovement.x, mob.getDeltaMovement().y, safeMovement.z);
        mob.hasImpulse = true;
        faceMovementDirection(mob, safeMovement);

        if (CortexConfig.get().enablePathfindingDebug)
            CortexDebug.sendParticlePath(mob, mob.position(), destination);
        return ActionOutcome.running();
    }

    @Override
    public void stop(E mob, Blackboard blackboard, CooldownTracker cooldowns, ActionStatus reason) {
        mob.setDeltaMovement(
            mob.getDeltaMovement().x * 0.25D,
            mob.getDeltaMovement().y,
            mob.getDeltaMovement().z * 0.25D
        );
        cooldowns.set(CommonBlackboardKeys.PASSIVE_DECISION, 1);
    }

    @Override
    public boolean isInterruptible() {
        return true;
    }

    @Override
    public int priority() {
        return priority;
    }

    private Vec3 pickDestination(E mob, Blackboard blackboard) {
        if (preferDark) {
            var dark = findDarkDestination(mob, blackboard);
            if (dark != null) {
                return Vec3.atCenterOf(dark);
            }
        }
        return findWanderDestination(mob);
    }

    private BlockPos findDarkDestination(E mob, Blackboard blackboard) {
        var level = mob.level();
        var origin = mob.blockPosition();
        var random = mob.getRandom();

        BlockPos resinCentre = null;
        var memory = blackboard.get(AiKeys.HIVE_MEMORY);
        if (memory != null) {
            var nearest = memory.findNearestOwnedWebCross(level, origin, 80.0);
            resinCentre = nearest.orElseGet(() -> memory.getDomeCenter().orElse(null));
        }

        List<ScoredPos> candidates = new ArrayList<>(64);

        for (var attempt = 0; attempt < 64 * 3 && candidates.size() < 64; attempt++) {
            var ox = random.nextInt(24 * 2 + 1) - 24;
            var oy = random.nextInt(12 * 2 + 1) - 12;
            var oz = random.nextInt(24 * 2 + 1) - 24;

            var pos = origin.offset(ox, oy, oz);

            if (origin.distSqr(pos) < 8.0 * 8.0)
                continue;

            if (!level.getBlockState(pos).isAir())
                continue;

            var floor = pos.below();
            if (level.getBlockState(floor).getCollisionShape(level, floor).isEmpty())
                continue;

            var clearAbove = true;
            for (var cy = 1; cy < 2; cy++) {
                if (!level.getBlockState(pos.above(cy)).isAir()) {
                    clearAbove = false;
                    break;
                }
            }
            if (!clearAbove)
                continue;

            var skyLight = level.getBrightness(LightLayer.SKY, pos);
            var blockLight = level.getMaxLocalRawBrightness(pos);
            var totalLight = Math.max(skyLight, blockLight);
            if (totalLight > 4)
                continue;

            var score = scoreDarkPosition(pos, resinCentre, totalLight);
            candidates.add(new ScoredPos(pos.immutable(), score));
        }

        if (candidates.isEmpty())
            return null;

        candidates.sort((a, b) -> Double.compare(b.score, a.score));
        return candidates.get(0).pos;
    }

    /**
     * Scores a candidate dark spot: darker is always better, and (when the hives whereabouts are known) moderately
     * close to existing hive structure is better too. This previously rewarded being <em>far</em> from
     * {@code resinCentre} once past the immediate 16-block ring, which — for idle wandering, the main consumer of
     * {@code preferDark} — actively pushed a mob further from the hive the longer it wandered, rather than letting it
     * drift back within range to keep contributing to {@code PlaceResinAction} (see that class's docs, which assume
     * this bias pulls mobs back toward the hive over time). The bonus is capped to a moderate radius rather than
     * favoring the closest possible spot, so mobs still spread out across the hive's territory instead of dogpiling the
     * exact same corner.
     */
    private static double scoreDarkPosition(BlockPos pos, BlockPos resinCentre, int totalLight) {
        var score = (4 - totalLight) * 10.0;
        if (totalLight == 0)
            score += 20.0;

        if (resinCentre != null) {
            var distSq = resinCentre.distSqr(pos);
            if (distSq >= 16.0 * 16.0 && distSq <= 48.0 * 48.0) {
                score += 15.0;
            }
        }

        return score;
    }

    private Vec3 findWanderDestination(E mob) {
        var origin = mob.blockPosition();

        for (var i = 0; i < 16; i++) {
            var xOffset = (mob.getRandom().nextDouble() * 2.0D - 1.0D) * radius;
            var zOffset = (mob.getRandom().nextDouble() * 2.0D - 1.0D) * radius;

            var candidate = BlockPos.containing(
                origin.getX() + xOffset,
                origin.getY(),
                origin.getZ() + zOffset
            );

            var ground = findSafeGround(mob, candidate);
            if (ground != null) {
                return Vec3.atCenterOf(ground);
            }
        }

        return null;
    }

    private void faceMovementDirection(E mob, Vec3 movement) {
        if (movement.horizontalDistanceSqr() < 0.0001D)
            return;

        var yaw = (float) (Math.atan2(movement.z, movement.x) * (180.0D / Math.PI)) - 90.0F;
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.yHeadRot = yaw;
        mob.getLookControl()
            .setLookAt(
                mob.getX() + movement.x,
                mob.getEyeY(),
                mob.getZ() + movement.z
            );
    }

    private BlockPos findSafeGround(E mob, BlockPos candidate) {
        var level = mob.level();

        for (var yOffset = 3; yOffset >= -4; yOffset--) {
            var feet = candidate.offset(0, yOffset, 0);
            var below = feet.below();
            var head = feet.above();

            if (isSafeStandPosition(mob, level, feet, head, below)) {
                return feet;
            }
        }

        return null;
    }

    private boolean isSafeStandPosition(E mob, Level level, BlockPos feet, BlockPos head, BlockPos below) {
        if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty())
            return false;

        if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty())
            return false;

        if (level.getBlockState(below).getCollisionShape(level, below).isEmpty())
            return false;

        return TraversalQueries.isSafeBlock(level, feet, mob)
            && TraversalQueries.isSafeBlock(level, head, mob);
    }

    private record ScoredPos(
        BlockPos pos,
        double score
    ) {}
}
