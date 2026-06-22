package mod.azure.ovomorphosis.ai.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

import mod.azure.ovomorphosis.registry.BlockRegistry;

public final class HiveMemory {

    private final Deque<BlockPos> placedBlocks = new ArrayDeque<>();

    public void trackBlock(BlockPos pos) {
        if (placedBlocks.size() >= 256) {
            placedBlocks.pollFirst();
        }
        placedBlocks.addLast(pos.immutable());
    }

    public Optional<BlockPos> findNearestWebCross(Level level, BlockPos origin, double maxRange) {
        var maxRangeSqr = maxRange * maxRange;
        BlockPos best = null;
        var bestDistSqr = Double.MAX_VALUE;

        for (var pos : placedBlocks) {
            var distSqr = origin.distSqr(pos);
            if (distSqr > maxRangeSqr)
                continue;

            var state = level.getBlockState(pos);
            if (!state.is(BlockRegistry.RESIN_WEB_CROSS.get()))
                continue;

            if (distSqr < bestDistSqr) {
                bestDistSqr = distSqr;
                best = pos;
            }
        }

        return Optional.ofNullable(best);
    }

    public Deque<BlockPos> all() {
        return placedBlocks;
    }

    // TODO: Clear this somewhere
    public void clear() {
        placedBlocks.clear();
    }
}
