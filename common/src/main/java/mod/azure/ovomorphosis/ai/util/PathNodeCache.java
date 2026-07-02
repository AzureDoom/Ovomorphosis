package mod.azure.ovomorphosis.ai.util;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

/**
 * Short-lived memoization cache for the expensive per-position classifications used by the custom pathfinders: physical
 * solidity, full-size walkability, tunnel-crawl fit, vertical-shaft fit, and climb nodes.
 * <p>
 * Adjacent A* nodes and per-tick AI checks repeatedly re-classify the same positions — {@code isTightTunnel} alone
 * queries eight neighbors per candidate, so two adjacent candidates share most of their lookups. Memoizing by
 * {@link BlockPos#asLong()} collapses those repeats into single block-state/collision-shape queries.
 * <h3>Scope rules</h3> Entries are only valid while the world is unchanged, so a cache must never outlive a single
 * pathfind, scan, or AI tick. Reuse an instance across ticks by calling {@link #clear()} at the start of each tick —
 * the backing maps keep their capacity, so steady-state reuse is allocation-free.
 * <p>
 * Results are also implicitly keyed to one mob (crawl height, footprint) and one dimension; never share an instance
 * across mobs or levels without clearing.
 * <h3>Thread safety</h3> Not thread-safe. Server thread only.
 */
public final class PathNodeCache {

    private static final byte TRUE = 1;

    private static final byte FALSE = 2;

    /** {@code 0} (the fastutil default return value) means "not cached". */
    private final Long2ByteOpenHashMap solid = new Long2ByteOpenHashMap(256);

    private final Long2ByteOpenHashMap walk = new Long2ByteOpenHashMap(128);

    private final Long2ByteOpenHashMap tunnel = new Long2ByteOpenHashMap(128);

    private final Long2ByteOpenHashMap shaft = new Long2ByteOpenHashMap(128);

    private final Long2ByteOpenHashMap climb = new Long2ByteOpenHashMap(128);

    /** Invalidates all entries while keeping map capacity. Call once per tick / scan / pathfind. */
    public void clear() {
        solid.clear();
        walk.clear();
        tunnel.clear();
        shaft.clear();
        climb.clear();
    }

    /** Memoized {@link CrawlingCustomAStar#isPhysicallySolid}. */
    public boolean isPhysicallySolid(Level level, BlockPos pos) {
        var key = pos.asLong();
        var cached = solid.get(key);
        if (cached != 0) {
            return cached == TRUE;
        }
        var result = CrawlingCustomAStar.isPhysicallySolid(level, pos);
        solid.put(key, result ? TRUE : FALSE);
        return result;
    }

    /** Memoized {@link CustomAStar#canStandAt} (full-size footprint walkability). */
    public boolean canStandAt(Level level, Mob mob, BlockPos pos) {
        var key = pos.asLong();
        var cached = walk.get(key);
        if (cached != 0) {
            return cached == TRUE;
        }
        var result = CustomAStar.canStandAt(level, mob, pos);
        walk.put(key, result ? TRUE : FALSE);
        return result;
    }

    /**
     * Memoized {@link CrawlingCustomAStar#tunnelCanStandAt}. Passes itself down to memoize the inner solidity checks.
     */
    public boolean tunnelCanStandAt(Level level, Mob mob, BlockPos pos) {
        var key = pos.asLong();
        var cached = tunnel.get(key);
        if (cached != 0) {
            return cached == TRUE;
        }
        var result = CrawlingCustomAStar.tunnelCanStandAt(level, mob, pos, this);
        tunnel.put(key, result ? TRUE : FALSE);
        return result;
    }

    /**
     * Memoized {@link CrawlingCustomAStar#verticalShaftCanCrawlAt}. Passes itself down for the inner solidity checks.
     */
    public boolean verticalShaftCanCrawlAt(Level level, Mob mob, BlockPos pos) {
        var key = pos.asLong();
        var cached = shaft.get(key);
        if (cached != 0) {
            return cached == TRUE;
        }
        var result = CrawlingCustomAStar.verticalShaftCanCrawlAt(level, mob, pos, this);
        shaft.put(key, result ? TRUE : FALSE);
        return result;
    }

    /** Memoized {@link MovementUtils#isSafeClimbNode}. */
    public boolean isSafeClimbNode(Level level, BlockPos pos) {
        var key = pos.asLong();
        var cached = climb.get(key);
        if (cached != 0) {
            return cached == TRUE;
        }
        var result = MovementUtils.isSafeClimbNode(level, pos, this);
        climb.put(key, result ? TRUE : FALSE);
        return result;
    }
}
