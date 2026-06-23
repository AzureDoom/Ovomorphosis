package mod.azure.ovomorphosis.ai.actions.xenomorph;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import mod.azure.ovomorphosis.ai.core.*;
import mod.azure.ovomorphosis.ai.util.CrawlingCustomAStar;
import mod.azure.ovomorphosis.ai.util.HiveMemory;
import mod.azure.ovomorphosis.ai.util.MovementUtils;
import mod.azure.ovomorphosis.registry.BlockRegistry;

public final class CarryToWebAction<E extends Mob> implements Action<E> {

    private final int priority;

    private final Consumer<E> onStartCallback;

    private final Consumer<E> onDepositCallback;

    private BlockPos webTarget = null;

    private List<BlockPos> path = Collections.emptyList();

    private int pathIndex = 0;

    private int repathCooldown = 0;

    private final int[] steerBias = { 0 };

    public CarryToWebAction(
        int priority,
        java.util.function.Consumer<E> onStartCallback,
        java.util.function.Consumer<E> onDepositCallback
    ) {
        this.priority = priority;
        this.onStartCallback = onStartCallback;
        this.onDepositCallback = onDepositCallback;
    }

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        cooldowns.set(AiKeys.PASSIVE_DECISION, 1);
        webTarget = null;
        path = Collections.emptyList();
        pathIndex = 0;
        repathCooldown = 0;

        var memory = blackboard.get(AiKeys.HIVE_MEMORY, HiveMemory.class);
        if (memory != null) {
            Optional<BlockPos> nearest = memory.findNearestWebCross(
                mob.level(),
                mob.blockPosition(),
                80D
            );
            nearest.ifPresent(pos -> webTarget = pos);
        }

        if (webTarget == null) {
            webTarget = scanWorldForWebCross(mob);
            if (webTarget != null && memory != null) {
                memory.trackBlock(webTarget);
            }
        }

        onStartCallback.accept(mob);
    }

    @Override
    public ActionStatus tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        if (mob.getHealth() <= 0)
            return ActionStatus.INTERRUPTED;

        var victim = blackboard.get(AiKeys.TARGET, LivingEntity.class);

        if (victim == null || !victim.isAlive()) {
            return ActionStatus.FAILURE;
        }

        if (
            webTarget == null || !mob.level()
                .getBlockState(webTarget)
                .is(
                    BlockRegistry.RESIN_WEB_CROSS.get()
                )
        ) {
            var memory = blackboard.get(AiKeys.HIVE_MEMORY, HiveMemory.class);
            Optional<BlockPos> nearest = memory != null
                ? memory.findNearestWebCross(mob.level(), mob.blockPosition(), 80D)
                : Optional.empty();

            if (nearest.isPresent()) {
                webTarget = nearest.get();
            } else {
                webTarget = scanWorldForWebCross(mob);
                if (webTarget == null)
                    return ActionStatus.FAILURE;
                if (memory != null)
                    memory.trackBlock(webTarget);
            }
            path = Collections.emptyList();
        }

        pinVictim(mob, victim);

        var webVec = Vec3.atBottomCenterOf(webTarget);
        if (mob.distanceToSqr(webVec) <= 1.8D * 1.8D) {
            deposit(mob, victim, blackboard, cooldowns);
            return ActionStatus.SUCCESS;
        }

        navigate(mob);
        faceToward(mob, webVec);

        return ActionStatus.RUNNING;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {
        if (reason == ActionStatus.INTERRUPTED) {
            var victim = blackboard.get(AiKeys.TARGET, LivingEntity.class);
            if (victim != null) {
                victim.setDeltaMovement(0, -0.1, 0);
            }
        }
        mob.setDeltaMovement(
            mob.getDeltaMovement().x * 0.25,
            mob.getDeltaMovement().y,
            mob.getDeltaMovement().z * 0.25
        );
    }

    @Override
    public boolean isInterruptible() {
        return false;
    }

    @Override
    public int priority() {
        return priority;
    }

    private void pinVictim(E mob, LivingEntity victim) {
        var eyePos = mob.getEyePosition();
        var forward = mob.getLookAngle().scale(0.6D);
        var carryPos = eyePos.add(forward).add(0, 0.5D, 0);

        victim.setPos(carryPos.x, carryPos.y, carryPos.z);
        victim.setDeltaMovement(Vec3.ZERO);
        victim.fallDistance = 0F;
        victim.setNoGravity(true);
    }

    private void deposit(E mob, LivingEntity victim, Blackboard blackboard, Cooldowns cooldowns) {
        var centre = Vec3.atBottomCenterOf(webTarget);
        victim.setPos(centre.x, webTarget.getY(), centre.z);
        victim.setDeltaMovement(Vec3.ZERO);
        victim.setNoGravity(false);

        blackboard.set(AiKeys.TARGET, null);
        mob.setTarget(null);

        cooldowns.set(AiKeys.CARRY_COOLDOWN, 200);

        onDepositCallback.accept(mob);
    }

    private void navigate(E mob) {
        if (repathCooldown > 0) {
            repathCooldown--;
        }

        if (repathCooldown <= 0 || path.isEmpty() || pathIndex >= path.size()) {
            path = CrawlingCustomAStar.findPath(
                mob,
                mob.blockPosition(),
                webTarget,
                96,
                2
            );
            pathIndex = path.size() > 1 ? 1 : 0;
            repathCooldown = 15;
        }

        while (
            pathIndex < path.size()
                && mob.position().distanceToSqr(Vec3.atBottomCenterOf(path.get(pathIndex))) < 1.2D
        ) {
            pathIndex++;
        }

        Vec3 direction;
        if (pathIndex < path.size()) {
            direction = Vec3.atBottomCenterOf(path.get(pathIndex)).subtract(mob.position());
        } else {
            direction = Vec3.atBottomCenterOf(webTarget).subtract(mob.position());
        }

        var horizontal = new Vec3(direction.x, 0, direction.z);
        if (horizontal.lengthSqr() < 0.0001D)
            return;

        var movement = MovementUtils.steerAwayFromDangerEntities(
            mob,
            horizontal.normalize().scale(0.28D)
        );
        var safe = MovementUtils.findSafeMovement(mob, movement, steerBias);

        if (!safe.equals(Vec3.ZERO)) {
            mob.setDeltaMovement(safe.x, mob.getDeltaMovement().y, safe.z);
            mob.hasImpulse = true;
        }
    }

    private void faceToward(E mob, Vec3 target) {
        var dx = target.x - mob.getX();
        var dz = target.z - mob.getZ();
        var yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.yHeadRot = yaw;
    }

    private BlockPos scanWorldForWebCross(E mob) {
        var origin = mob.blockPosition();
        var rangeSqr = 80.0 * 80.0;
        var webBlock = BlockRegistry.RESIN_WEB_CROSS.get();
        var r = (int) Math.ceil(80.0);

        BlockPos best = null;
        var bestDistSq = Double.MAX_VALUE;

        for (
            var pos : BlockPos.betweenClosed(
                origin.offset(-r, -r, -r),
                origin.offset(r, r, r)
            )
        ) {
            var distSq = origin.distSqr(pos);
            if (distSq > rangeSqr || distSq >= bestDistSq)
                continue;
            if (!mob.level().getBlockState(pos).is(webBlock))
                continue;
            best = pos.immutable();
            bestDistSq = distSq;
        }

        return best;
    }
}
