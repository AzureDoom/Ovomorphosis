package mod.azure.ovomorphosis.ai.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

import mod.azure.ovomorphosis.registry.BlockRegistry;

public final class HiveMemory {

    private final Deque<BlockPos> placedBlocks = new ArrayDeque<>();

    private int missCount = 0;

    public void trackBlock(BlockPos pos) {
        if (placedBlocks.size() >= 256) {
            placedBlocks.pollFirst();
        }
        placedBlocks.addLast(pos.immutable());
    }

    /**
     * Returns the nearest tracked position that still contains a resin web cross block.
     * <p>
     * Stale entries (blocks that were broken since being tracked) are counted and, once 8 accumulates, a sweep removes
     * all stale positions so they don't keep polluting future lookups.
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
        if (missCount >= 8) {
            evictStale(level);
            missCount = 0;
        }

        return Optional.ofNullable(best);
    }

    /**
     * Removes every entry that no longer contains a resin web cross. Called automatically once enough misses
     * accumulate; can also be called explicitly when a block is known to have been broken (e.g. from a block-break
     * event handler on the hive coordinator).
     */
    public void evictStale(Level level) {
        placedBlocks.removeIf(
            pos -> !level.getBlockState(pos).is(BlockRegistry.RESIN_WEB_CROSS.get())
        );
    }

    public int size() {
        return placedBlocks.size();
    }
}
