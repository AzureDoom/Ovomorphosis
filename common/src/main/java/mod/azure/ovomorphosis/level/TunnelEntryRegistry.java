package mod.azure.ovomorphosis.level;

import com.azure.azurecortex.navigation.astar.PathNodeCache;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side, chunk-bucketed index of known tunnel-entry positions (tight tunnels and vertical shafts), used so
 * crawling mobs can find a nearby entry with a few bucket lookups instead of a world scan.
 * <p>
 * Follows the same layout and lifecycle as {@link ResinWebRegistry}, with one key difference: tunnel entries are a
 * property of world <em>shape</em> rather than a single tracked block, so entries can silently become invalid when any
 * nearby block changes. The registry therefore treats itself as a hint index, not a source of truth — every query
 * revalidates candidates against the live world via {@link PathNodeCache} and evicts entries that fail.
 * <h3>Population</h3>
 * <ul>
 * <li>Self-populating: {@code MoveToTargetAction}'s ring scan registers every entry it discovers.</li>
 * <li>Optional explicit wiring: call {@link #register} wherever the mod deliberately creates crawl spaces (resin
 * structure placement, hive tunnel carving, structure modifiers) to make them instantly discoverable without a scan
 * ever running.</li>
 * <li>Call {@link #clearDimension} on level unload / server stop, mirroring {@link ResinWebRegistry}.</li>
 * </ul>
 * <h3>Thread safety</h3> All maps and sets use {@link ConcurrentHashMap}, matching {@link ResinWebRegistry}.
 * {@link #findNearestValid} performs world block reads, so it must only be called from the server thread.
 */
public final class TunnelEntryRegistry {

    /**
     * Safety valve against unbounded growth in pathological worlds (e.g. giant cave systems where every position is a
     * tunnel). Real tunnel entries are sparse; a chunk hitting this cap still answers queries usefully.
     */
    private static final int MAX_ENTRIES_PER_CHUNK = 64;

    private static final ConcurrentHashMap<ResourceKey<Level>, ConcurrentHashMap<Long, Set<BlockPos>>> REGISTRY =
        new ConcurrentHashMap<>();

    private TunnelEntryRegistry() {}

    /**
     * Records {@code pos} as a known tunnel entry in {@code level}. Safe to call redundantly; the backing set
     * deduplicates. Silently drops the entry if the chunk bucket is at capacity.
     *
     * @param level the dimension the entry was found in
     * @param pos   the entry position (stored as an immutable copy)
     */
    public static void register(Level level, BlockPos pos) {
        var set = chunkSet(level.dimension(), ChunkPos.asLong(pos));
        if (set.size() >= MAX_ENTRIES_PER_CHUNK) {
            return;
        }
        set.add(pos.immutable());
    }

    /**
     * Removes {@code pos} from the registry, e.g. when a revalidation check discovers the tunnel has been sealed.
     *
     * @param level the dimension the entry was removed from
     * @param pos   the entry position
     */
    public static void unregister(Level level, BlockPos pos) {
        var dimMap = REGISTRY.get(level.dimension());
        if (dimMap == null) {
            return;
        }
        var set = dimMap.get(ChunkPos.asLong(pos));
        if (set != null) {
            set.remove(pos);
        }
    }

    /**
     * Drops all entries for {@code dimension}. Call on {@code LevelEvent.UNLOAD} / server stop, alongside the
     * equivalent {@link ResinWebRegistry#clearDimension} call.
     *
     * @param dimension the dimension key to purge
     */
    public static void clearDimension(ResourceKey<Level> dimension) {
        REGISTRY.remove(dimension);
    }

    /**
     * Returns the nearest registered entry to {@code origin} that still validates against the live world, or
     * {@code null} if none is known in range. Iterates only the chunk buckets that could contain a match, skips
     * unloaded chunks entirely (so it never forces chunk loads), and evicts entries in loaded chunks that fail
     * validation — the registry self-heals on every query.
     * <p>
     * Server thread only: candidates are validated with world block reads through {@code cache}.
     *
     * @param level            the level to search in
     * @param mob              the mob the entry must fit (crawl height depends on the mob)
     * @param origin           the search center
     * @param horizontalRadius maximum |dx| and |dz| from origin, in blocks
     * @param minDy            minimum {@code pos.y - origin.y}, inclusive
     * @param maxDy            maximum {@code pos.y - origin.y}, inclusive
     * @param cache            the caller's per-tick {@link PathNodeCache} for validation
     * @return the nearest valid entry, or {@code null}
     */
    public static BlockPos findNearestValid(
        Level level,
        Mob mob,
        BlockPos origin,
        int horizontalRadius,
        int minDy,
        int maxDy,
        PathNodeCache cache
    ) {
        var dimMap = REGISTRY.get(level.dimension());
        if (dimMap == null || dimMap.isEmpty()) {
            return null;
        }

        var chunkRadius = (horizontalRadius >> 4) + 1;
        var originChunkX = origin.getX() >> 4;
        var originChunkZ = origin.getZ() >> 4;

        BlockPos best = null;
        var bestSq = Double.MAX_VALUE;

        for (var cx = originChunkX - chunkRadius; cx <= originChunkX + chunkRadius; cx++) {
            for (var cz = originChunkZ - chunkRadius; cz <= originChunkZ + chunkRadius; cz++) {
                var set = dimMap.get(ChunkPos.asLong(cx, cz));
                if (set == null || set.isEmpty()) {
                    continue;
                }

                if (!level.hasChunk(cx, cz)) {
                    continue;
                }

                for (var pos : set) {
                    var dy = pos.getY() - origin.getY();
                    if (dy < minDy || dy > maxDy) {
                        continue;
                    }
                    if (Math.abs(pos.getX() - origin.getX()) > horizontalRadius) {
                        continue;
                    }
                    if (Math.abs(pos.getZ() - origin.getZ()) > horizontalRadius) {
                        continue;
                    }

                    if (
                        !cache.tunnelCanStandAt(level, mob, pos)
                            && !cache.verticalShaftCanCrawlAt(level, mob, pos)
                    ) {
                        set.remove(pos);
                        continue;
                    }

                    var dSq = origin.distSqr(pos);
                    if (dSq < bestSq) {
                        bestSq = dSq;
                        best = pos;
                    }
                }
            }
        }

        return best;
    }

    /** Returns (creating if absent) the chunk-keyed set for the given dimension and chunk. */
    private static Set<BlockPos> chunkSet(ResourceKey<Level> dimension, long chunkKey) {
        return REGISTRY
            .computeIfAbsent(dimension, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(chunkKey, k -> ConcurrentHashMap.newKeySet());
    }
}
