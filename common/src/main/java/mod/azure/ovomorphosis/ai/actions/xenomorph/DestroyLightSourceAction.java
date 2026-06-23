package mod.azure.ovomorphosis.ai.actions.xenomorph;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import mod.azure.ovomorphosis.ai.core.Action;
import mod.azure.ovomorphosis.ai.core.ActionStatus;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.ai.core.Cooldowns;
import mod.azure.ovomorphosis.entities.xenomorph.XenomorphEntity;

/**
 * The xenomorph seeks out and destroys nearby light-emitting blocks, helping it keep the environment dark for hive
 * construction and ambush opportunities.
 * <p>
 * This action handles its own locomotion via direct velocity (matching how {@code MoveToTargetAction} works) rather
 * than delegating to {@code MoveToDestinationAction} via {@link AiKeys#DESTINATION}. Delegation via DESTINATION caused
 * the tree to immediately select {@code MoveToDestinationAction} (priority 25) on the next tick and interrupt this
 * action before it could do anything.
 *
 * @param <E> xenomorph entity type
 */
public class DestroyLightSourceAction<E extends XenomorphEntity> implements Action<E> {

    private final int postBreakCooldownTicks;

    private BlockPos lightBlock = null;

    private float breakProgress = 0f;

    private int breakId = -1;

    private int stuckTimer = 0;

    private Vec3 lastPos = Vec3.ZERO;

    public DestroyLightSourceAction(int postBreakCooldownTicks) {
        this.postBreakCooldownTicks = postBreakCooldownTicks;
    }

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        lightBlock = null;
        breakProgress = 0f;
        breakId = mob.getId() ^ 0x11AC_0000;
        stuckTimer = 0;
        lastPos = mob.position();
    }

    @Override
    public ActionStatus tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        var level = mob.level();

        if (lightBlock == null) {
            lightBlock = findBrightestLightBlock(mob);
            if (lightBlock == null) {
                return ActionStatus.FAILURE;
            }
            cooldowns.set(AiKeys.LIGHT_SCAN_COOLDOWN, 40);
            breakProgress = 0f;
            stuckTimer = 0;
            lastPos = mob.position();
        }

        var state = level.getBlockState(lightBlock);
        if (state.isAir() || getLightEmission(state) < 8) {
            level.destroyBlockProgress(breakId, lightBlock, -1);
            lightBlock = null;
            return ActionStatus.SUCCESS;
        }

        var target = Vec3.atCenterOf(lightBlock);
        var distSq = mob.distanceToSqr(target);

        if (distSq > 6.25D) {
            if (mob.position().distanceToSqr(lastPos) < 0.001D) {
                stuckTimer++;
            } else {
                stuckTimer = 0;
            }
            lastPos = mob.position();

            if (stuckTimer > 60) {
                level.destroyBlockProgress(breakId, lightBlock, -1);
                lightBlock = null;
                stuckTimer = 0;
                return ActionStatus.FAILURE;
            }

            var direction = target.subtract(mob.position());
            var horizontal = new Vec3(direction.x, 0.0D, direction.z);

            if (horizontal.lengthSqr() > 0.0001D) {
                var move = horizontal.normalize().scale(0.32D);

                var yErr = target.y - mob.getY();
                var yVel = mob.onGround()
                    ? (yErr > 1.0D ? 0.35D : mob.getDeltaMovement().y)
                    : mob.getDeltaMovement().y;

                mob.setDeltaMovement(move.x, yVel, move.z);
                mob.hasImpulse = true;

                var yaw = (float) (Math.atan2(move.z, move.x) * (180.0D / Math.PI)) - 90.0F;
                mob.setYRot(yaw);
                mob.yBodyRot = yaw;
                mob.yHeadRot = yaw;
            }

            mob.getLookControl().setLookAt(target.x, target.y, target.z, 30f, 30f);
            return ActionStatus.RUNNING;
        }

        mob.setDeltaMovement(
            mob.getDeltaMovement().x * 0.4D,
            mob.getDeltaMovement().y,
            mob.getDeltaMovement().z * 0.4D
        );
        mob.getLookControl().setLookAt(target.x, target.y, target.z, 30f, 30f);

        var hardness = state.getDestroySpeed(level, lightBlock);
        var tickProgress = hardness <= 0f
            ? 0.05f * 4f
            : 0.05f / Math.max(hardness, 0.1f);
        breakProgress += tickProgress;

        var stage = (int) Math.min(breakProgress * 10f, 9f);
        level.destroyBlockProgress(breakId, lightBlock, stage);

        if (breakProgress >= 1f) {
            level.destroyBlockProgress(breakId, lightBlock, -1);
            level.destroyBlock(lightBlock, true, mob);
            lightBlock = null;
            breakProgress = 0f;
            cooldowns.set(AiKeys.LIGHT_SCAN_COOLDOWN, postBreakCooldownTicks);
            return ActionStatus.SUCCESS;
        }

        return ActionStatus.RUNNING;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {
        if (lightBlock != null) {
            mob.level().destroyBlockProgress(breakId, lightBlock, -1);
        }
        lightBlock = null;
        breakProgress = 0f;
    }

    @Override
    public boolean isInterruptible() {
        return true;
    }

    @Override
    public int priority() {
        return 10;
    }

    private static BlockPos findBrightestLightBlock(XenomorphEntity mob) {
        var level = mob.level();
        var origin = mob.blockPosition();

        BlockPos best = null;
        var bestLight = 8 - 1;

        for (var x = -12; x <= 12; x++) {
            for (var y = -6; y <= 6; y++) {
                for (var z = -12; z <= 12; z++) {
                    var pos = origin.offset(x, y, z);
                    var emission = getLightEmission(level.getBlockState(pos));
                    if (emission > bestLight) {
                        bestLight = emission;
                        best = pos.immutable();
                    }
                }
            }
        }
        return best;
    }

    private static int getLightEmission(BlockState state) {
        return state.getLightEmission();
    }
}
