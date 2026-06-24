package mod.azure.ovomorphosis.level;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side, chunk-bucketed registry of every {@code RESIN_WEB_CROSS} block that has been placed anywhere in any
 * dimension.
 * <h3>Thread safety</h3> All maps and sets use {@link ConcurrentHashMap} so they are safe to read from the AI tick
 * thread while Minecraft's level tick writes to them. Individual {@code BlockPos} immutable values are safe to share
 * across threads without copying.
 * <h3>Lifecycle</h3>
 * <ul>
 * <li>Call {@link #register} from {@code ResinWebFullBlock.onPlace}.</li>
 * <li>Call {@link #unregister} from {@code ResinWebFullBlock.onRemove}.</li>
 * <li>Call {@link #clearDimension} when a level unloads (server stopping or dimension unload event) to avoid leaking
 * cross-session state.</li>
 * </ul>
 */
public final class ResinWebRegistry {

    private static final ConcurrentHashMap<ResourceKey<Level>, ConcurrentHashMap<Long, Set<BlockPos>>> REGISTRY =
        new ConcurrentHashMap<>();

    private ResinWebRegistry() {}

    /**
     * Records {@code pos} as a live {@code RESIN_WEB_CROSS} in {@code level}. Safe to call from any thread, including
     * async chunk workers.
     *
     * @param level the dimension the block was placed in
     * @param pos   the block position (will be stored as an immutable copy)
     */
    public static void register(Level level, BlockPos pos) {
        chunkSet(level.dimension(), ChunkPos.asLong(pos)).add(pos.immutable());
    }

    /**
     * Removes {@code pos} from the registry when the block is destroyed or replaced.
     *
     * @param level the dimension the block was removed from
     * @param pos   the block position
     */
    public static void unregister(Level level, BlockPos pos) {
        var dimMap = REGISTRY.get(level.dimension());
        if (dimMap == null)
            return;
        var set = dimMap.get(ChunkPos.asLong(pos));
        if (set != null)
            set.remove(pos);
    }

    /**
     * Drops all cross-block entries for {@code dimension}. Call this on a {@code LevelEvent.UNLOAD} / server-stop to
     * prevent cross-session leaks.
     *
     * @param dimension the dimension key to purge
     */
    public static void clearDimension(ResourceKey<Level> dimension) {
        REGISTRY.remove(dimension);
    }

    /**
     * Returns every registered cross-block position within {@code rangeBlocks} of {@code origin} in the given
     * dimension, without performing any world block lookups.
     * <p>
     * The method iterates only the chunk buckets that could possibly contain a block within the requested range. For a
     * range of 80 blocks that is at most an 11 × 11 chunk grid (121 chunks) rather than an 160 × 160 × 160 block cube
     * (4 096 000 positions).
     *
     * @param dimension   the dimension to search in
     * @param origin      the search center
     * @param rangeBlocks the maximum block distance (exclusive)
     * @return an unmodifiable view snapshot; never {@code null}
     */
    public static Set<BlockPos> queryNearby(
        ResourceKey<Level> dimension,
        BlockPos origin,
        double rangeBlocks
    ) {
        var dimMap = REGISTRY.get(dimension);
        if (dimMap == null || dimMap.isEmpty())
            return Collections.emptySet();

        var rangeSq = rangeBlocks * rangeBlocks;
        var chunkRadius = (int) Math.ceil(rangeBlocks / 16.0);
        var originChunkX = origin.getX() >> 4;
        var originChunkZ = origin.getZ() >> 4;
        Set<BlockPos> result = ConcurrentHashMap.newKeySet();

        for (var cx = originChunkX - chunkRadius; cx <= originChunkX + chunkRadius; cx++) {
            for (var cz = originChunkZ - chunkRadius; cz <= originChunkZ + chunkRadius; cz++) {
                var set = dimMap.get(ChunkPos.asLong(cx, cz));
                if (set == null)
                    continue;
                for (var pos : set) {
                    if (origin.distSqr(pos) <= rangeSq) {
                        result.add(pos);
                    }
                }
            }
        }

        return result;
    }

    /**
     * Returns the nearest registered cross-block to {@code origin} within {@code rangeBlocks}, without touching the
     * world. Equivalent to scanning {@link #queryNearby} for the minimum, but avoids materializing the full set when
     * only the closest position is needed.
     *
     * @param dimension   the dimension to search in
     * @param origin      the search center
     * @param rangeBlocks the maximum block distance (exclusive)
     * @return the nearest position, or {@link Optional#empty()} if none registered in range
     */
    public static Optional<BlockPos> findNearest(
        ResourceKey<Level> dimension,
        BlockPos origin,
        double rangeBlocks
    ) {
        var dimMap = REGISTRY.get(dimension);
        if (dimMap == null || dimMap.isEmpty())
            return Optional.empty();

        var rangeSq = rangeBlocks * rangeBlocks;
        var chunkRadius = (int) Math.ceil(rangeBlocks / 16.0);
        var originChunkX = origin.getX() >> 4;
        var originChunkZ = origin.getZ() >> 4;

        BlockPos best = null;
        var bestSq = Double.MAX_VALUE;

        for (var cx = originChunkX - chunkRadius; cx <= originChunkX + chunkRadius; cx++) {
            for (var cz = originChunkZ - chunkRadius; cz <= originChunkZ + chunkRadius; cz++) {
                var set = dimMap.get(ChunkPos.asLong(cx, cz));
                if (set == null)
                    continue;
                for (var pos : set) {
                    var dSq = origin.distSqr(pos);
                    if (dSq <= rangeSq && dSq < bestSq) {
                        bestSq = dSq;
                        best = pos;
                    }
                }
            }
        }

        return Optional.ofNullable(best);
    }

    /** Returns (creating if absent) the chunk-keyed set for the given dimension and chunk. */
    private static Set<BlockPos> chunkSet(ResourceKey<Level> dimension, long chunkKey) {
        return REGISTRY
            .computeIfAbsent(dimension, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(chunkKey, k -> ConcurrentHashMap.newKeySet());
    }
}
