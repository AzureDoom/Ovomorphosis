package mod.azure.ovomorphosis.ai.util;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

import mod.azure.ovomorphosis.ai.actions.xenomorph.PlaceResinAction;
import mod.azure.ovomorphosis.level.ResinWebRegistry;
import mod.azure.ovomorphosis.registry.BlockRegistry;

/**
 * Per-mob cache of known {@code RESIN_WEB_CROSS} positions.
 * <h3>Population strategy</h3> {@link HiveMemory} is populated in two ways:
 * <ol>
 * <li><b>Passive tracking</b> — any action that places or discovers a cross block calls {@link #trackBlock(BlockPos)}.
 * {@link PlaceResinAction} and similar actions already do this.</li>
 * <li><b>Registry sync</b> — {@link #syncFromRegistry(Level, BlockPos, double)} bulk-populates from
 * {@link ResinWebRegistry}, which is updated by {@code ResinWebFullBlock.onPlace} / {@code onRemove}. This replaces the
 * former O(n³) world-scan that lived in {@code CarryToWebAction.scanWorldForWebCross}.</li>
 * </ol>
 * <h3>Staleness handling</h3> The deque stores <em>last-known</em> positions. {@link #findNearestWebCross} skips any
 * entry whose block no longer exists in the world and counts those as misses. When accumulated misses exceed
 * {@code MISS_THRESHOLD} the full deque is scrubbed by {@link #evictStale}.
 * <h3>Sync cadence</h3> Callers should gate {@link #syncFromRegistry} behind a cooldown (e.g. every 60–120 ticks) so it
 * does not iterate the registry on every AI tick. The returned boolean indicates whether any new positions were added,
 * letting callers skip a subsequent {@link #findNearestWebCross} call when nothing changed.
 */
public final class HiveMemory {

    private static final int MAX_ENTRIES = 256;

    private static final int MISS_THRESHOLD = 8;

    private static final String NBT_KEY = "pos";

    private final Deque<BlockPos> placedBlocks = new ArrayDeque<>();

    private int missCount = 0;

    /**
     * Records a single cross-block position. Oldest entry is evicted when the deque is full.
     *
     * @param pos the position to remember (stored as an immutable copy)
     */
    public void trackBlock(BlockPos pos) {
        if (placedBlocks.size() >= MAX_ENTRIES) {
            placedBlocks.pollFirst();
        }
        placedBlocks.addLast(pos.immutable());
    }

    /**
     * Bulk-populates this memory from {@link ResinWebRegistry} for the given level and search radius, adding only
     * positions not already tracked.
     * <p>
     * This replaces the former {@code CarryToWebAction.scanWorldForWebCross} method. The registry query visits only the
     * chunk buckets that overlap the radius (≤ ceil(range/16)² chunks) rather than iterating every block position in a
     * cube, making it dramatically cheaper at larger radii.
     * <p>
     * Call this on a moderate cooldown (e.g. every 60–120 ticks) rather than every AI tick. Positions that the registry
     * returns but that are no longer valid blocks will be caught by the staleness eviction in
     * {@link #findNearestWebCross}.
     *
     * @param level       the level to query (used for its dimension key)
     * @param origin      the mob's current block position
     * @param rangeBlocks the maximum radius to include
     * @return {@code true} if at least one new position was added to this memory
     */
    public boolean syncFromRegistry(Level level, BlockPos origin, double rangeBlocks) {
        var fromRegistry = ResinWebRegistry.queryNearby(level.dimension(), origin, rangeBlocks);
        if (fromRegistry.isEmpty())
            return false;

        var added = false;
        for (var pos : fromRegistry) {
            if (!placedBlocks.contains(pos)) {
                trackBlock(pos);
                added = true;
            }
        }
        return added;
    }

    /**
     * Searches the cached positions for the nearest live {@code RESIN_WEB_CROSS} within {@code maxRange} of
     * {@code origin}.
     * <p>
     * Entries whose block no longer exists in the world are counted as misses. Once {@link #MISS_THRESHOLD} misses
     * accumulate across calls, {@link #evictStale} is triggered to prune dead entries in bulk.
     *
     * @param level    the level used to validate block states
     * @param origin   the search origin
     * @param maxRange maximum distance; entries further than this are skipped
     * @return the nearest valid position, or {@link Optional#empty()} if none known
     */
    public Optional<BlockPos> findNearestWebCross(Level level, BlockPos origin, double maxRange) {
        var maxRangeSqr = maxRange * maxRange;
        BlockPos best = null;
        var bestDistSqr = Double.MAX_VALUE;
        var missesThisCall = 0;

        for (var pos : placedBlocks) {
            var distSqr = origin.distSqr(pos);
            if (distSqr > maxRangeSqr)
                continue;

            if (!level.getBlockState(pos).is(BlockRegistry.RESIN_WEB_CROSS.get())) {
                missesThisCall++;
                continue;
            }

            if (distSqr < bestDistSqr) {
                bestDistSqr = distSqr;
                best = pos;
            }
        }

        missCount += missesThisCall;
        if (missCount >= MISS_THRESHOLD) {
            evictStale(level);
            missCount = 0;
        }

        return Optional.ofNullable(best);
    }

    /**
     * Removes all entries that no longer correspond to a live {@code RESIN_WEB_CROSS} block. Called automatically after
     * {@link #MISS_THRESHOLD} misses accumulate; also safe to call externally (e.g. after a large demolition event).
     *
     * @param level the level used to validate block states
     */
    public void evictStale(Level level) {
        placedBlocks.removeIf(
            pos -> !level.getBlockState(pos).is(BlockRegistry.RESIN_WEB_CROSS.get())
        );
    }

    /** Returns the number of positions currently tracked. */
    public int size() {
        return placedBlocks.size();
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        var list = new ListTag();
        for (var pos : placedBlocks) {
            var entry = new CompoundTag();
            entry.put(NBT_KEY, NbtUtils.writeBlockPos(pos));
            list.add(entry);
        }
        tag.put("blocks", list);
        return tag;
    }

    public static HiveMemory load(CompoundTag tag) {
        var memory = new HiveMemory();
        if (!tag.contains("blocks", Tag.TAG_LIST))
            return memory;
        var list = tag.getList("blocks", Tag.TAG_COMPOUND);
        for (var i = 0; i < list.size(); i++) {
            memory.trackBlock(NbtUtils.readBlockPos(list.getCompound(i)));
        }
        return memory;
    }

    public Iterable<BlockPos> getAllWebCrosses() {
        return placedBlocks;
    }
}
