package mod.azure.ovomorphosis.ai.actions.xenomorph;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

import mod.azure.ovomorphosis.ai.core.Action;
import mod.azure.ovomorphosis.ai.core.ActionOutcome;
import mod.azure.ovomorphosis.ai.core.ActionStatus;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.ai.core.Cooldowns;
import mod.azure.ovomorphosis.ai.nav.MovementUtils;

public record DodgeProjectileAction<E extends Mob>(int priority) implements Action<E> {

    private static final double SCAN_RADIUS = 8.0D;

    private static final double INTERCEPT_CONE_DOT = 0.72D;

    private static final double MIN_PROJECTILE_SPEED_SQ = 0.08D * 0.08D;

    private static final double DODGE_SPEED = 0.65D;

    private static final double DODGE_JUMP = 0.30D;

    private static final int DODGE_COOLDOWN_TICKS = 25;

    private static final double PROBE_DISTANCE = 2.5D;

    public boolean hasIncomingProjectile(E mob) {
        return findIncomingProjectile(mob) != null;
    }

    @Override
    public void start(E mob, Blackboard blackboard, Cooldowns cooldowns) {}

    @Override
    public ActionOutcome tick(E mob, Blackboard blackboard, Cooldowns cooldowns) {
        if (mob.getHealth() <= 0)
            return ActionOutcome.failed();

        if (cooldowns.isOnCooldown(AiKeys.DODGE_COOLDOWN))
            return ActionOutcome.failed();

        var incoming = findIncomingProjectile(mob);
        if (incoming == null)
            return ActionOutcome.failed();

        applyDodge(mob, incoming);
        cooldowns.set(AiKeys.DODGE_COOLDOWN, DODGE_COOLDOWN_TICKS);
        return ActionOutcome.SUCCESS;
    }

    @Override
    public void stop(E mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {}

    @Override
    public boolean isInterruptible() {
        return true;
    }

    private static <E extends Mob> Projectile findIncomingProjectile(E mob) {
        var box = mob.getBoundingBox().inflate(SCAN_RADIUS);
        List<Projectile> projectiles = mob.level()
            .getEntitiesOfClass(Projectile.class, box, p -> p.isAlive() && p.getOwner() != mob);

        return projectiles.stream()
            .filter(p -> isOnInterceptCourse(mob, p))
            .min(Comparator.comparingDouble(mob::distanceToSqr))
            .orElse(null);
    }

    private static boolean isOnInterceptCourse(Mob mob, Projectile projectile) {
        var vel = projectile.getDeltaMovement();
        if (vel.lengthSqr() < MIN_PROJECTILE_SPEED_SQ)
            return false;

        var toMob = mob.getEyePosition().subtract(projectile.position()).normalize();
        return vel.normalize().dot(toMob) >= INTERCEPT_CONE_DOT;
    }

    private static <E extends Mob> void applyDodge(E mob, Projectile projectile) {
        var vel = projectile.getDeltaMovement().normalize();

        var perpRight = new Vec3(-vel.z, 0, vel.x).normalize();
        var perpLeft = perpRight.scale(-1.0D);

        var rightScore = scoreDodgeDirection(mob, projectile, perpRight);
        var leftScore = scoreDodgeDirection(mob, projectile, perpLeft);

        Vec3 chosen;
        if (Math.abs(rightScore - leftScore) < 2.0D) {
            chosen = mob.getRandom().nextBoolean() ? perpRight : perpLeft;
        } else {
            chosen = rightScore >= leftScore ? perpRight : perpLeft;
        }

        var lateral = chosen.scale(DODGE_SPEED);
        var safe = MovementUtils.findSafeMovement(mob, lateral, new int[] { 0 });
        var impulse = safe.equals(Vec3.ZERO) ? lateral : safe;

        mob.setDeltaMovement(impulse.x, DODGE_JUMP, impulse.z);
        mob.hasImpulse = true;

        var yaw = (float) (Math.atan2(impulse.z, impulse.x) * (180.0 / Math.PI)) - 90.0F;
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.yHeadRot = yaw;
    }

    /**
     * Scores a candidate dodge direction for a given mob and incoming projectile. Higher is better.
     * <p>
     * Components:
     * <ul>
     * <li><b>Projectile path clearance</b> — how far the probe point sits from the projectile's line of travel. Dodging
     * further away from the path is always preferred.</li>
     * <li><b>Walkable safety</b> — whether the probe landing position has solid ground below it and open air at
     * foot/head level.</li>
     * <li><b>Darkness bonus</b> — xenomorphs prefer darker positions; low ambient light scores positively.</li>
     * <li><b>Fire penalty</b> — probe landing near fire or lava is penalized heavily.</li>
     * <li><b>Light penalty</b> — bright positions score negatively (exposes the xenomorph).</li>
     * <li><b>Fall risk penalty</b> — no solid block below the probe landing position scores negatively.</li>
     * </ul>
     */
    private static <E extends Mob> double scoreDodgeDirection(
        E mob,
        Projectile projectile,
        Vec3 lateral
    ) {
        double score = 0.0D;
        var level = mob.level();

        var probePos = mob.position().add(lateral.scale(PROBE_DISTANCE));
        var probeBlock = BlockPos.containing(probePos);

        var vel = projectile.getDeltaMovement().normalize();
        var toProbe = probePos.subtract(projectile.position());
        var t = toProbe.dot(vel);
        var closest = projectile.position().add(vel.scale(Math.max(0, t)));
        var clearance = probePos.distanceTo(closest);
        score += clearance * 4.0D;

        var feetState = level.getBlockState(probeBlock);
        var belowState = level.getBlockState(probeBlock.below());
        var headState = level.getBlockState(probeBlock.above());

        var feetOpen = feetState.getCollisionShape(level, probeBlock).isEmpty();
        var headOpen = headState.getCollisionShape(level, probeBlock.above()).isEmpty();
        var hasSolidBelow = !belowState.getCollisionShape(level, probeBlock.below()).isEmpty();

        if (feetOpen && headOpen && hasSolidBelow) {
            score += 8.0D;
        } else if (!feetOpen || !headOpen) {
            score -= 15.0D;
        }

        if (!hasSolidBelow) {
            var twoBelow = level.getBlockState(probeBlock.below(2));
            if (twoBelow.getCollisionShape(level, probeBlock.below(2)).isEmpty()) {
                score -= 12.0D;
            } else {
                score -= 3.0D;
            }
        }

        var skyLight = level.getBrightness(LightLayer.SKY, probeBlock);
        var blockLight = level.getMaxLocalRawBrightness(probeBlock);
        var totalLight = Math.max(skyLight, blockLight);

        if (totalLight == 0) {
            score += 5.0D;
        } else if (totalLight <= 4) {
            score += 2.0D;
        } else {
            score -= totalLight * 0.5D;
        }

        if (
            isFireOrDanger(level.getBlockState(probeBlock))
                || isFireOrDanger(level.getBlockState(probeBlock.below()))
                || isFireOrDanger(level.getBlockState(probeBlock.above()))
        ) {
            score -= 30.0D;
        }
        for (
            var offset : new BlockPos[] {
                probeBlock.north(),
                probeBlock.south(),
                probeBlock.east(),
                probeBlock.west()
            }
        ) {
            if (isFireOrDanger(level.getBlockState(offset))) {
                score -= 10.0D;
                break;
            }
        }

        return score;
    }

    private static boolean isFireOrDanger(BlockState state) {
        return state.is(BlockTags.FIRE)
            || state.is(Blocks.LAVA)
            || state.is(Blocks.MAGMA_BLOCK)
            || state.is(Blocks.CAMPFIRE)
            || state.is(Blocks.SOUL_CAMPFIRE)
            || state.is(Blocks.LAVA_CAULDRON);
    }
}
