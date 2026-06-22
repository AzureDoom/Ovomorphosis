package mod.azure.ovomorphosis.ai.util;

import net.minecraft.world.phys.Vec3;

/**
 * Mixin interface implemented by mobs capable of crawling along walls, ceilings, and floors.
 * <p>
 * Methods are prefixed with {@code ovomorphosis$} to avoid collisions with other mixins. Physics and orientation are
 * managed by {@link CrawlingManager}; this interface is the data contract that the mixin injects into the mob's class.
 */
public interface WallCrawlingMob {

    /**
     * Returns {@code true} if the mob is currently in an active wall-crawling state.
     *
     * @return {@code true} while crawling
     */
    boolean ovomorphosis$isWallCrawling();

    /**
     * Sets whether the mob is actively crawling on a surface.
     *
     * @param crawling {@code true} to enable crawling, {@code false} to disable
     */
    void ovomorphosis$setWallCrawling(boolean crawling);

    /**
     * Returns the number of grace ticks remaining after crawling stopped, during which gravity is still suppressed.
     *
     * @return remaining grace ticks
     */
    int ovomorphosis$getWallCrawlGraceTicks();

    /**
     * Sets the remaining grace tick count.
     *
     * @param ticks the new grace tick value
     */
    void ovomorphosis$setWallCrawlGraceTicks(int ticks);

    /**
     * Returns the mob's current crawl-forward direction (the direction it is moving along the surface).
     *
     * @return normalized forward vector in world space
     */
    Vec3 ovomorphosis$getCrawlForward();

    /**
     * Returns the crawl-forward direction from the previous tick, used for smooth interpolation.
     *
     * @return previous tick's normalized forward vector
     */
    Vec3 ovomorphosis$getOldCrawlForward();

    /**
     * Returns the surface normal the mob is currently clinging to (the "up" direction relative to the surface).
     *
     * @return normalized up vector pointing away from the surface
     */
    Vec3 ovomorphosis$getCrawlUp();

    /**
     * Returns the surface normal from the previous tick, used for smooth interpolation.
     *
     * @return previous tick's normalized up vector
     */
    Vec3 ovomorphosis$getOldCrawlUp();

    /**
     * Returns the mob's current distance from the surface it is clinging to.
     *
     * @return distance in blocks
     */
    double ovomorphosis$getCrawlDistFromBlock();

    /**
     * Returns the distance from the surface recorded on the previous tick.
     *
     * @return previous tick's distance in blocks
     */
    double ovomorphosis$getOldCrawlDistFromBlock();

    /**
     * Updates all three orientation values atomically.
     *
     * @param forward       the new forward direction along the surface
     * @param up            the new surface normal
     * @param distFromBlock the new distance from the surface in blocks
     */
    void ovomorphosis$setCrawlOrientation(Vec3 forward, Vec3 up, double distFromBlock);
}
