package mod.azure.xenogenesis.ai.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Utility class that drives wall-crawling physics, orientation, and decision logic for mobs that implement
 * {@link WallCrawlingMob}.
 * <p>
 * Call {@link #updateWallCrawlingPhysics} and {@link #updateCrawlOrientation} each tick from the mob's tick method to
 * keep gravity suppression and surface alignment current.
 */
public final class CrawlingManager {

    private CrawlingManager() {}

    /**
     * Returns {@code true} if {@code mob} implements {@link WallCrawlingMob} and is in a state where wall-crawling is
     * permitted (not in water, not a vehicle).
     *
     * @param mob the mob to check
     * @return {@code true} if wall-crawl movement is available
     */
    public static boolean canWallCrawl(Mob mob) {
        return mob instanceof WallCrawlingMob && MovementUtils.canWallCrawl(mob);
    }

    /**
     * Sets the wall-crawling flag on {@code mob} if it implements {@link WallCrawlingMob}. Does nothing for mobs that
     * do not implement the interface.
     *
     * @param mob      the mob to update
     * @param crawling {@code true} to enable crawling, {@code false} to disable
     */
    public static void setWallCrawling(Mob mob, boolean crawling) {
        if (mob instanceof WallCrawlingMob wallCrawler) {
            wallCrawler.xenogenesis$setWallCrawling(crawling);
        }
    }

    /**
     * Returns true if the mob was wall-crawling recently — either it is currently crawling, or it still has grace ticks
     * remaining from a recent crawl. Use this in actions that take over from CrawlToTargetAction so they can inherit
     * the crawling state even after the previous action cleared the flag.
     */
    public static boolean wasRecentlyWallCrawling(Mob mob) {
        if (!(mob instanceof WallCrawlingMob wallCrawler))
            return false;
        return wallCrawler.xenogenesis$isWallCrawling()
            || wallCrawler.xenogenesis$getWallCrawlGraceTicks() > 0;
    }

    /**
     * Returns {@code true} if {@code mob} is currently in an active wall-crawling state.
     *
     * @param mob the mob to check
     * @return {@code true} if crawling right now
     */
    public static boolean isWallCrawling(Mob mob) {
        return mob instanceof WallCrawlingMob wallCrawler
            && wallCrawler.xenogenesis$isWallCrawling();
    }

    /**
     * Returns {@code true} if reaching {@code destination} warrants switching to wall-crawl movement. Considers
     * vertical height difference and whether the destination is on a climbable surface.
     *
     * @param mob         the mob evaluating the move
     * @param destination the target block position
     * @return {@code true} if wall-crawl pathing should be used
     */
    public static boolean shouldUseWallCrawlingTo(Mob mob, BlockPos destination) {
        if (!canWallCrawl(mob) || destination == null) {
            return false;
        }

        var destVec = Vec3.atBottomCenterOf(destination);

        if (MovementUtils.needsWallCrawl(mob, destVec)) {
            return true;
        }

        var yDiff = destination.getY() - mob.blockPosition().getY();

        if (Math.abs(yDiff) >= 2) {
            return true;
        }

        return MovementUtils.isClimbable(mob.level(), destination, false);
    }

    /**
     * Returns {@code true} if reaching {@code target} warrants switching to wall-crawl movement. Considers the vertical
     * difference and whether the target's position is on a climbable surface.
     *
     * @param mob    the mob evaluating the move
     * @param target the entity to reach
     * @return {@code true} if wall-crawl pathing should be used
     */
    public static boolean shouldUseWallCrawlingTo(Mob mob, LivingEntity target) {
        if (!canWallCrawl(mob) || target == null || !target.isAlive()) {
            return false;
        }

        if (MovementUtils.needsWallCrawl(mob, target.position())) {
            return true;
        }

        var yDiff = target.blockPosition().getY() - mob.blockPosition().getY();

        if (Math.abs(yDiff) >= 2) {
            return true;
        }

        return MovementUtils.isClimbable(mob.level(), target.blockPosition(), false);
    }

    /**
     * Updates gravity suppression and fall-distance zeroing each tick for wall-crawling mobs.
     * <p>
     * Gravity is suppressed while the mob is actively crawling or has grace ticks remaining and is adjacent to a
     * surface. Grace ticks are decremented here.
     *
     * @param mob the mob to update
     */
    public static void updateWallCrawlingPhysics(Mob mob) {
        if (!(mob instanceof WallCrawlingMob wallCrawler)) {
            mob.setNoGravity(false);
            return;
        }

        if (wallCrawler.xenogenesis$isWallCrawling()) {
            wallCrawler.xenogenesis$setWallCrawlGraceTicks(3);
        } else if (wallCrawler.xenogenesis$getWallCrawlGraceTicks() > 0) {
            wallCrawler.xenogenesis$setWallCrawlGraceTicks(
                wallCrawler.xenogenesis$getWallCrawlGraceTicks() - 1
            );
        }

        var isCrawling = wallCrawler.xenogenesis$isWallCrawling();
        var graceTicks = wallCrawler.xenogenesis$getWallCrawlGraceTicks();

        var touchingSurface = isAdjacentToAnySurface(mob);
        var active = (isCrawling || graceTicks > 0) && touchingSurface;

        mob.setNoGravity(active);

        if (active) {
            mob.fallDistance = 0.0F;
        }
    }

    /**
     * Recomputes and stores the mob's crawl orientation (forward and up vectors) based on its current movement and the
     * nearest surface normal.
     * <p>
     * Should be called after applying movement each tick so that the renderer can smoothly interpolate the mob's
     * rotation.
     *
     * @param mob      the mob to orient
     * @param movement the displacement vector applied this tick
     */
    public static void updateCrawlOrientation(Mob mob, Vec3 movement) {
        if (!(mob instanceof WallCrawlingMob wallCrawler)) {
            return;
        }

        var up = findSurfaceNormal(mob);

        if (up == null) {
            up = new Vec3(0.0D, 1.0D, 0.0D);
        }

        var forward = new Vec3(movement.x, movement.y, movement.z);

        if (forward.lengthSqr() < 0.0001D) {
            forward = wallCrawler.xenogenesis$getCrawlForward();
        }

        forward = forward.subtract(up.scale(forward.dot(up)));

        if (forward.lengthSqr() < 0.0001D) {
            forward = new Vec3(0.0D, 1.0D, 0.0D).subtract(up.scale(up.y));
        }

        if (forward.lengthSqr() < 0.0001D) {
            forward = wallCrawler.xenogenesis$getCrawlForward();
        }

        wallCrawler.xenogenesis$setCrawlOrientation(
            forward.normalize(),
            up.normalize(),
            distanceToSurface(mob, up.scale(-1.0D))
        );
    }

    private static Vec3 findSurfaceNormal(Mob mob) {
        var level = mob.level();
        var box = mob.getBoundingBox();

        Vec3 bestSurfaceUp = null;
        var bestDistance = Double.MAX_VALUE;

        Vec3 currentUp = null;
        double hysteresisBonus = 0.0D;
        if (mob instanceof WallCrawlingMob wc) {
            var up = wc.xenogenesis$getCrawlUp();
            if (up != null && up.lengthSqr() > 0.0001D) {
                currentUp = up;
                hysteresisBonus = 0.8D;
            }
        }

        var horizontalDirections = new Direction[] {
            Direction.NORTH,
            Direction.SOUTH,
            Direction.WEST,
            Direction.EAST
        };

        var detectionProbe = 0.25D;

        for (var direction : horizontalDirections) {
            var intoSurface = Vec3.atLowerCornerOf(direction.getNormal());
            var movedBox = box.move(intoSurface.scale(detectionProbe));

            if (!level.noBlockCollision(mob, movedBox)) {
                var distance = distanceToSurface(mob, intoSurface);
                var candidateUp = intoSurface.scale(-1.0D);

                var effectiveDistance = distance;
                if (currentUp != null && candidateUp.dot(currentUp) < 0.5D) {
                    effectiveDistance += hysteresisBonus;
                }

                if (effectiveDistance < bestDistance) {
                    bestDistance = effectiveDistance;
                    bestSurfaceUp = candidateUp;
                }
            }
        }

        if (bestSurfaceUp != null) {
            return bestSurfaceUp;
        }

        if (!mob.onGround()) {
            var intoCeiling = new Vec3(0.0D, 1.0D, 0.0D);
            if (!level.noBlockCollision(mob, box.move(intoCeiling.scale(0.15D)))) {
                return new Vec3(0.0D, -1.0D, 0.0D);
            }

            var intoFloor = new Vec3(0.0D, -1.0D, 0.0D);
            if (!level.noBlockCollision(mob, box.move(intoFloor.scale(0.15D)))) {
                return new Vec3(0.0D, 1.0D, 0.0D);
            }
        }

        if (mob instanceof WallCrawlingMob wc) {
            var lastUp = wc.xenogenesis$getCrawlUp();
            if (lastUp != null && lastUp.lengthSqr() > 0.0001D) {
                return lastUp;
            }
        }

        return new Vec3(0.0D, 1.0D, 0.0D);
    }

    private static boolean isAdjacentToAnySurface(Mob mob) {
        var level = mob.level();
        var box = mob.getBoundingBox();

        var probe = (mob.getBbWidth() / 2.0D) + 0.5D;

        return !level.noBlockCollision(mob, box.move(probe, 0, 0))
            || !level.noBlockCollision(mob, box.move(-probe, 0, 0))
            || !level.noBlockCollision(mob, box.move(0, 0, probe))
            || !level.noBlockCollision(mob, box.move(0, 0, -probe))
            || !level.noBlockCollision(mob, box.move(0, probe, 0));
    }

    private static double distanceToSurface(Mob mob, Vec3 normal) {
        var level = mob.level();
        var box = mob.getBoundingBox();

        for (var distance = 0.0D; distance <= 1.5D; distance += 0.05D) {
            var movedBox = box.move(normal.scale(distance));

            if (!level.noBlockCollision(mob, movedBox)) {
                return distance;
            }
        }

        return mob.getBbHeight() / 2.0D;
    }

    /**
     * Returns {@code true} if the mob should use wall-crawl movement to close in on {@code target} specifically for
     * combat purposes.
     * <p>
     * Activates when the vertical gap is three or more blocks and either the horizontal distance is within eight blocks
     * or a wall-crawl is otherwise required.
     *
     * @param mob    the mob evaluating the approach
     * @param target the combat target
     * @return {@code true} if wall-crawl movement should be used to reach the target
     */
    public static boolean shouldUseWallCrawlingToTarget(Mob mob, LivingEntity target) {
        if (!canWallCrawl(mob) || target == null || !target.isAlive()) {
            return false;
        }

        var yDiff = target.blockPosition().getY() - mob.blockPosition().getY();
        var absYDiff = Math.abs(yDiff);

        if (absYDiff < 3) {
            return false;
        }

        var horizontalDistSqr = mob.position()
            .multiply(1.0D, 0.0D, 1.0D)
            .distanceToSqr(target.position().multiply(1.0D, 0.0D, 1.0D));

        if (horizontalDistSqr <= 8.0D * 8.0D) {
            return true;
        }

        return MovementUtils.needsWallCrawl(mob, target.position());
    }
}
