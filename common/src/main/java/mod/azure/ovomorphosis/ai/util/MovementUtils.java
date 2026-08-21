package mod.azure.ovomorphosis.ai.util;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;

import mod.azure.ovomorphosis.util.ModTags;

/**
 * Stateless utility methods for movement safety checks, obstacle steering, and velocity computation used throughout the
 * AI action classes.
 */
public final class MovementUtils {

    /** Default distance (blocks) ahead of the mob used for obstacle look-ahead checks. */
    private static final double DEFAULT_LOOK_AHEAD = 1.25D;

    /** Candidate steering angles (degrees) tried in order when the direct path is blocked. */
    private static final int[] STEER_ANGLES = { 30, -30, 60, -60, 90, -90, 120, -120, 150, -150 };

    private MovementUtils() {}

    /**
     * Returns {@code true} if the block at {@code pos} is safe to stand on or walk through — not tagged as a danger
     * block and not containing a danger fluid.
     *
     * @param level the world
     * @param pos   the block position to check
     * @return {@code true} if the block is safe
     */
    public static boolean isSafeBlock(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (state.is(ModTags.DANGER_BLOCKS))
            return false;
        if (state.is(ModTags.RESIN))
            return true;
        var fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            return !fluid.is(ModTags.DANGER_FLUIDS);
        }
        return true;
    }

    private static boolean hasGroundWithinDrop(Level level, BlockPos feetPos, int maxDrop) {
        for (var drop = 1; drop <= maxDrop; drop++) {
            var ground = feetPos.below(drop);

            if (
                !level.getBlockState(ground).getCollisionShape(level, ground).isEmpty()
                    && isSafeBlock(level, ground)
            ) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns {@code true} if the path {@code distance} blocks ahead of the mob in the {@code forward} direction is
     * free of danger blocks, solid geometry, and lava, and has ground within nine blocks below.
     * <p>
     * Samples multiple points across the mob's width to account for its footprint. Each sample first checks the column
     * at the mob's current foot height; if that's blocked, it retries one block higher before giving up, since a
     * one-block rise is exactly what the entity's own step-up assist can climb without help from this function. Without
     * that retry, any ordinary 1-block terrace/ledge within the lookahead reads as solid wall (because it's tested at
     * the old, lower height) and the mob refuses to walk toward it at all, even though it's perfectly capable of
     * stepping up onto it.
     *
     * @param mob      the mob performing the check
     * @param forward  normalized horizontal direction to check
     * @param distance how far ahead (blocks) to scan
     * @return {@code true} if the path is clear
     */
    public static boolean isSafeAhead(Mob mob, Vec3 forward, double distance) {
        var level = mob.level();
        var feetY = Mth.floor(mob.getBoundingBox().minY);
        var side = new Vec3(-forward.z, 0.0D, forward.x);
        var halfW = (mob.getBbWidth() / 2.0D) + 0.15D;

        for (var d = 0.25D; d <= distance; d += 0.25D) {
            var center = mob.position().add(forward.scale(d));

            for (var s = -halfW; s <= halfW; s += halfW / 2.0D) {
                var sample = center.add(side.scale(s));

                var feetPos = new BlockPos(Mth.floor(sample.x), feetY, Mth.floor(sample.z));

                if (!isPassableColumn(level, feetPos)) {
                    var stepped = feetPos.above();
                    if (!isPassableColumn(level, stepped)) {
                        return false;
                    }
                    feetPos = stepped;
                    feetY = feetPos.getY();
                }

                var groundPos = feetPos.below();
                var feetState = level.getBlockState(feetPos);
                var feetCollision = feetState.getCollisionShape(level, feetPos);

                var feetFluid = feetState.getFluidState();
                var inWater = feetFluid.is(FluidTags.WATER);

                if (!inWater) {
                    var feetPassable =
                        feetCollision.isEmpty()
                            || feetState.is(ModTags.RESIN);

                    if (!feetPassable)
                        return false;

                    if (!hasGroundWithinDrop(level, feetPos, 9))
                        return false;
                }

                if (feetFluid.is(FluidTags.LAVA))
                    return false;
                if (level.getBlockState(groundPos).getFluidState().is(FluidTags.LAVA))
                    return false;
            }
        }

        return true;
    }

    /**
     * Returns {@code true} if a mob's foot/head cells at {@code feetPos} are clear enough to occupy — not a danger
     * block, not solid geometry (unless resin, which entities pass through). Used by {@link #isSafeAhead} to test a
     * column at both the mob's current height and, on retry, one block higher.
     */
    private static boolean isPassableColumn(Level level, BlockPos feetPos) {
        var headPos = feetPos.above();

        if (!isSafeBlock(level, feetPos))
            return false;

        if (!isSafeBlock(level, headPos))
            return false;

        var feetState = level.getBlockState(feetPos);
        var headState = level.getBlockState(headPos);

        var feetCollision = feetState.getCollisionShape(level, feetPos);
        var headCollision = headState.getCollisionShape(level, headPos);

        if (!headCollision.isEmpty() && !headState.is(ModTags.RESIN))
            return false;

        return feetCollision.isEmpty() || feetState.is(ModTags.RESIN);
    }

    /**
     * Returns a movement vector that steers around obstacles while staying as close as possible to
     * {@code desiredMovement}.
     * <p>
     * Tries progressively larger steering angles from {@link #STEER_ANGLES}, biasing toward the direction that worked
     * last time (tracked via {@code steerBias[0]}). Returns {@link Vec3#ZERO} if no safe direction is found.
     *
     * @param mob             the mob moving
     * @param desiredMovement the ideal movement vector
     * @param steerBias       a single-element array holding the last successful steering bias ({@code 1} = right,
     *                        {@code -1} = left, {@code 0} = none); updated in place
     * @return a safe movement vector, or {@link Vec3#ZERO} if the mob is completely blocked
     */
    public static Vec3 findSafeMovement(Mob mob, Vec3 desiredMovement, int[] steerBias) {
        var horizontal = new Vec3(desiredMovement.x, 0.0D, desiredMovement.z);
        var length = horizontal.length();

        if (length < 0.001D)
            return desiredMovement;

        var forward = horizontal.normalize();

        if (isSafeAhead(mob, forward, DEFAULT_LOOK_AHEAD)) {
            steerBias[0] = 0;
            return desiredMovement;
        }

        var angles = sortByBias(steerBias[0]);

        for (var angleDeg : angles) {
            var rotated = rotate(forward, angleDeg);
            if (isSafeAhead(mob, rotated, DEFAULT_LOOK_AHEAD)) {
                steerBias[0] = angleDeg > 0 ? 1 : -1;
                return rotated.scale(length);
            }
        }

        if (steerBias[0] != 0) {
            var wallFollow = rotate(forward, steerBias[0] > 0 ? 90 : -90);
            if (isSafeAhead(mob, wallFollow, DEFAULT_LOOK_AHEAD * 0.5D)) {
                return wallFollow.scale(length);
            }
        }

        return Vec3.ZERO;
    }

    private static Vec3 rotate(Vec3 forward, int angleDeg) {
        var radians = Math.toRadians(angleDeg);
        var cos = Math.cos(radians);
        var sin = Math.sin(radians);
        return new Vec3(
            forward.x * cos - forward.z * sin,
            0.0D,
            forward.x * sin + forward.z * cos
        );
    }

    private static int[] sortByBias(int bias) {
        if (bias == 0)
            return MovementUtils.STEER_ANGLES;

        var preferred = new ArrayList<Integer>();
        var other = new ArrayList<Integer>();

        for (var a : MovementUtils.STEER_ANGLES) {
            if ((bias > 0 && a > 0) || (bias < 0 && a < 0)) {
                preferred.add(a);
            } else {
                other.add(a);
            }
        }

        preferred.addAll(other);
        return preferred.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Computes a repulsion vector that pushes {@code mob} away from nearby danger entities (tagged via
     * {@code ModTags.DANGER_ENTITIES}).
     * <p>
     * Each danger entity within five blocks contributes a weighted outward force. Returns {@link Vec3#ZERO} if no
     * danger entities are nearby.
     *
     * @param mob the mob to protect
     * @return the combined repulsion vector
     */
    public static Vec3 getDangerEntityRepulsion(Mob mob) {
        final var dangerRadius = 5.0D;
        final var dangerRadiusSqr = dangerRadius * dangerRadius;
        final var avoidStrength = 1.25D;

        var away = Vec3.ZERO;
        var box = mob.getBoundingBox().inflate(dangerRadius);

        for (var entity : mob.level().getEntities(mob, box)) {
            if (!entity.getType().is(ModTags.DANGER_ENTITIES)) {
                continue;
            }

            var offset = mob.position().subtract(entity.position());
            var distSqr = offset.lengthSqr();

            if (distSqr > dangerRadiusSqr) {
                continue;
            }

            if (distSqr < 0.0001D) {
                offset = Vec3.directionFromRotation(0.0F, mob.getYRot()).scale(-1.0D);
                distSqr = 0.0001D;
            }

            var distance = Math.sqrt(distSqr);
            var weight = 1.0D - distance / dangerRadius;

            away = away.add(offset.normalize().scale(weight * avoidStrength));
        }

        return away;
    }

    /**
     * Blends {@code desiredMovement} with a repulsion vector away from nearby danger entities, returning a movement
     * vector that avoids them while still pursuing the goal.
     *
     * @param mob             the mob moving
     * @param desiredMovement the ideal movement vector before repulsion is applied
     * @return the adjusted movement vector
     */
    public static Vec3 steerAwayFromDangerEntities(Mob mob, Vec3 desiredMovement) {
        var away = getDangerEntityRepulsion(mob);

        if (away.lengthSqr() < 0.0001D) {
            return desiredMovement;
        }

        var desiredHorizontal = new Vec3(desiredMovement.x, 0.0D, desiredMovement.z);
        var desiredLength = desiredHorizontal.length();

        if (desiredLength < 0.001D) {
            return away.normalize().scale(0.12D);
        }

        var blended = desiredHorizontal.add(away);

        if (blended.lengthSqr() < 0.0001D) {
            return away.normalize().scale(desiredLength);
        }

        return blended.normalize().scale(desiredLength);
    }

    /**
     * Returns {@code true} if there is a safe landing spot {@code distance} blocks ahead of the mob in the horizontal
     * component of {@code direction}.
     *
     * @param mob       the mob about to leap
     * @param direction the intended leap direction
     * @param distance  the expected horizontal travel distance in blocks
     * @return {@code true} if the landing area is safe
     */
    public static boolean hasSafeLandingAfterLeap(Mob mob, Vec3 direction, double distance) {
        if (direction.lengthSqr() < 0.0001D) {
            return false;
        }

        var level = mob.level();
        var forward = new Vec3(direction.x, 0.0D, direction.z).normalize();

        var landingCenter = mob.position().add(forward.scale(distance));
        var feetY = mob.getBoundingBox().minY;

        var feetPos = BlockPos.containing(
            landingCenter.x,
            feetY,
            landingCenter.z
        );

        var headPos = feetPos.above();

        if (!isSafeBlock(level, feetPos)) {
            return false;
        }

        if (!isSafeBlock(level, headPos)) {
            return false;
        }

        if (!level.getBlockState(feetPos).getCollisionShape(level, feetPos).isEmpty()) {
            return false;
        }

        if (!level.getBlockState(headPos).getCollisionShape(level, headPos).isEmpty()) {
            return false;
        }

        return hasGroundWithinDrop(level, feetPos, 4);
    }

    /**
     * Returns {@code true} if any danger entity is close enough to produce a non-zero repulsion vector for {@code mob}.
     *
     * @param mob the mob to check
     * @return {@code true} if at least one danger entity is within repulsion range
     */
    public static boolean hasNearbyDangerEntity(Mob mob) {
        return getDangerEntityRepulsion(mob).lengthSqr() > 0.0001D;
    }

    /**
     * Returns {@code true} if the mob is in a world state that permits wall-crawl movement — not submerged in water and
     * not acting as a vehicle.
     *
     * @param mob the mob to check
     * @return {@code true} if wall-crawl movement is permitted
     */
    public static boolean canWallCrawl(Mob mob) {
        return !mob.isInWater() && !mob.isVehicle();
    }

    /**
     * Returns {@code true} if there is solid geometry adjacent to the given coordinates that a mob could cling to.
     *
     * @param level    the world
     * @param x        block X coordinate
     * @param y        block Y coordinate
     * @param z        block Z coordinate
     * @param generous if {@code true}, uses a larger detection radius (1.5 blocks vs 0.5)
     * @return {@code true} if a climbable surface is nearby
     */
    public static boolean isClimbable(Level level, int x, int y, int z, boolean generous) {
        var reachBox = new AABB(x, y, z, x + 1, y + 1, z + 1)
            .inflate(generous ? 1.5D : 0.5D);

        return !level.noBlockCollision(null, reachBox);
    }

    /**
     * Convenience overload of {@link #isClimbable(Level, int, int, int, boolean)} that accepts a {@link BlockPos}.
     *
     * @param level    the world
     * @param pos      the block position to check
     * @param generous if {@code true}, uses a larger detection radius
     * @return {@code true} if a climbable surface is nearby
     */
    public static boolean isClimbable(Level level, BlockPos pos, boolean generous) {
        return isClimbable(level, pos.getX(), pos.getY(), pos.getZ(), generous);
    }

    /**
     * Returns {@code true} if {@code feet} is a valid node for a wall-crawling mob — both the feet and head positions
     * are safe, neither is blocked by solid geometry, and the position is adjacent to a climbable surface.
     *
     * @param level the world
     * @param feet  the candidate feet position
     * @return {@code true} if the mob can cling at this position
     */
    public static boolean isSafeClimbNode(Level level, BlockPos feet) {
        return isSafeClimbNode(level, feet, null);
    }

    /**
     * Cache-aware variant. The adjacent-surface test is a handful of solidity lookups (routed through {@code cache}
     * when supplied) instead of the old inflated-AABB {@link #isClimbable} sweep, which walked up to ~27 block shapes
     * per call and dominated pathfinding CPU. It also no longer treats the floor below the node as a cling surface — an
     * air cell resting on solid ground is a walk node, not a climb node — which removes a large amount of spurious
     * climb branching on open terrain.
     *
     * @param level the world
     * @param feet  the candidate feet position
     * @param cache optional per-pathfind cache for the solidity lookups; may be {@code null}
     * @return {@code true} if the mob can cling at this position
     */
    public static boolean isSafeClimbNode(Level level, BlockPos feet, PathNodeCache cache) {
        var head = feet.above();

        if (!isSafeBlock(level, feet)) {
            return false;
        }

        if (!isSafeBlock(level, head)) {
            return false;
        }

        if (
            !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && !level.getBlockState(feet).is(ModTags.RESIN)
        ) {
            return false;
        }

        if (
            !level.getBlockState(head).getCollisionShape(level, head).isEmpty()
                && !level.getBlockState(head).is(ModTags.RESIN)
        ) {
            return false;
        }

        return hasAdjacentClingSurface(level, feet, head, cache);
    }

    /**
     * Cheap replacement for the old {@code isClimbable(feet, false)} sweep: a wall-crawler needs a solid face that
     * spans BOTH feet and head height on the same horizontal side (a genuine 2+-block-tall surface), or an overhead
     * ceiling to grip. Deliberately excludes the floor block, so ordinary ground cells are not misclassified as climb
     * nodes. Requiring the pair (rather than either height independently) also excludes single-block-tall lips — the
     * wall of a 1-deep trench, a stair edge, a fence-height ledge — which are solid at only one of the two heights;
     * those are ordinary auto-step/fall terrain, not something a mob should be gluing itself to and wall-crawling over.
     * Each lookup is memoized when a {@link PathNodeCache} is supplied.
     */
    private static boolean hasAdjacentClingSurface(Level level, BlockPos feet, BlockPos head, PathNodeCache cache) {
        if (solidAt(level, head.above(), cache)) {
            return true;
        }
        return (solidAt(level, feet.north(), cache) && solidAt(level, head.north(), cache))
            || (solidAt(level, feet.south(), cache) && solidAt(level, head.south(), cache))
            || (solidAt(level, feet.east(), cache) && solidAt(level, head.east(), cache))
            || (solidAt(level, feet.west(), cache) && solidAt(level, head.west(), cache));
    }

    private static boolean solidAt(Level level, BlockPos pos, PathNodeCache cache) {
        return cache != null
            ? cache.isPhysicallySolid(level, pos)
            : CrawlingCustomAStar.isPhysicallySolid(level, pos);
    }

    /**
     * Returns {@code true} if reaching {@code wanted} requires wall-crawl movement given the mob's current position and
     * the surrounding terrain.
     *
     * @param mob    the mob evaluating the move
     * @param wanted the world-space position to reach
     * @return {@code true} if wall-crawl movement is necessary
     */
    public static boolean needsWallCrawl(Mob mob, Vec3 wanted) {
        if (!canWallCrawl(mob)) {
            return false;
        }

        var center = BlockPos.containing(
            mob.getBoundingBox().getCenter().x,
            mob.getBoundingBox().getCenter().y,
            mob.getBoundingBox().getCenter().z
        );

        if (!isClimbable(mob.level(), center, true)) {
            return false;
        }

        var current = requiredMovementAt(mob, mob.blockPosition());
        if (current == MovementType.CLIMB) {
            return true;
        }

        var wantedBlock = BlockPos.containing(wanted.x, wanted.y, wanted.z);
        var wantedType = requiredMovementAt(mob, wantedBlock);

        if (wantedType == MovementType.CLIMB) {
            return true;
        }

        return wantedBlock.equals(mob.blockPosition().above())
            && wantedType == MovementType.JUMP;
    }

    /**
     * Describes the type of movement required to occupy a given block position.
     */
    public enum MovementType {
        /** The mob can walk to this position normally. */
        WALK,
        /** The mob must jump one block up to reach this position. */
        JUMP,
        /** The mob must climb (wall-crawl or ladder) to reach this position. */
        CLIMB
    }

    /**
     * Determines the {@link MovementType} required for the mob to occupy {@code pos}.
     *
     * @param mob the mob being evaluated
     * @param pos the target block position
     * @return the movement type needed
     */
    public static MovementType requiredMovementAt(Mob mob, BlockPos pos) {
        var below = pos.below();
        var stateBelow = mob.level().getBlockState(below);

        if (stateBelow.entityCanStandOn(mob.level(), below, mob)) {
            return MovementType.WALK;
        }

        var twoBelow = below.below();
        var stateTwoBelow = mob.level().getBlockState(twoBelow);

        if (stateTwoBelow.entityCanStandOn(mob.level(), twoBelow, mob)) {
            return MovementType.JUMP;
        }

        return MovementType.CLIMB;
    }

    /**
     * Computes a velocity vector for a wall-crawling mob moving toward a world-space {@code wanted} position, clamped
     * to {@code speed}.
     *
     * @param mob    the mob to move
     * @param wanted the target position in world space
     * @param speed  maximum movement speed in blocks per tick
     * @return the velocity to apply this tick, or {@link Vec3#ZERO} if already at the target
     */
    public static Vec3 computeWallCrawlVelocity(Mob mob, Vec3 wanted, double speed) {
        var center = mob.getBoundingBox().getCenter();
        var offset = wanted.subtract(center);
        var dist = offset.length();

        if (dist < 0.1D) {
            return Vec3.ZERO;
        }

        var clampedSpeed = Math.min(speed, dist);
        return offset.normalize().scale(clampedSpeed);
    }
}
