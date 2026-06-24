package mod.azure.ovomorphosis.ai.util;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

import mod.azure.ovomorphosis.registry.BlockRegistry;

import static net.minecraft.nbt.NbtUtils.readBlockPos;
import static net.minecraft.nbt.NbtUtils.writeBlockPos;

public final class HiveMemory {

    private static final int MAX_ENTRIES = 256;

    private static final int MISS_THRESHOLD = 8;

    private static final String NBT_KEY = "pos";

    private final Deque<BlockPos> placedBlocks = new ArrayDeque<>();

    private int missCount = 0;

    public void trackBlock(BlockPos pos) {
        if (placedBlocks.size() >= MAX_ENTRIES) {
            placedBlocks.pollFirst();
        }
        placedBlocks.addLast(pos.immutable());
    }

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

    public void evictStale(Level level) {
        placedBlocks.removeIf(
            pos -> !level.getBlockState(pos).is(BlockRegistry.RESIN_WEB_CROSS.get())
        );
    }

    public int size() {
        return placedBlocks.size();
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        var list = new ListTag();
        for (var pos : placedBlocks) {
            var entry = new CompoundTag();
            entry.put(NBT_KEY, writeBlockPos(pos));
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
            readBlockPos(list.getCompound(i), NBT_KEY).ifPresent(memory::trackBlock);
        }
        return memory;
    }
}
