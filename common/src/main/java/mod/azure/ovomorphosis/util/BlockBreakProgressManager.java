package mod.azure.ovomorphosis.util;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks partial "damage" applied to blocks by non-player sources (acid pools) so it accumulates across hits and
 * renders the vanilla crack overlay.
 * <p>
 * Progress is stored per dimension: the same {@link BlockPos} in the Overworld and the Nether are different blocks and
 * must not share progress.
 */
public class BlockBreakProgressManager {

    /**
     * Hardness floor used when dividing damage by a block's hardness, so zero-hardness blocks (grass, torches, flowers)
     * dissolve almost immediately instead of dividing by zero or never progressing.
     */
    private static final float MIN_EFFECTIVE_HARDNESS = 0.05F;

    /** How long an untouched progress entry survives before being purged, in game ticks (5 minutes). */
    private static final long ENTRY_LIFETIME_TICKS = 20L * 60L * 5L;

    private static final Map<ResourceKey<Level>, Map<BlockPos, Progress>> PROGRESS = new HashMap<>();

    private record Progress(
        long lastUpdatedGameTime,
        float value
    ) {}

    public static void tick(Level level) {
        var gameTime = level.getGameTime();

        if (gameTime % (20 * 20) != 0) {
            return;
        }

        var dimensionProgress = PROGRESS.get(level.dimension());
        if (dimensionProgress == null) {
            return;
        }

        dimensionProgress.entrySet().removeIf(entry -> {
            var expired = gameTime - entry.getValue().lastUpdatedGameTime() > ENTRY_LIFETIME_TICKS;
            if (expired) {
                level.destroyBlockProgress(computeBreakerId(entry.getKey()), entry.getKey(), -1);
            }
            return expired;
        });

        if (dimensionProgress.isEmpty()) {
            PROGRESS.remove(level.dimension());
        }
    }

    /** Drops all tracked progress. Called when a server's saved data is (re)initialized. */
    public static void clearAll() {
        PROGRESS.clear();
    }

    public static void resetProgress(Level level, BlockPos pos) {
        var dimensionProgress = PROGRESS.get(level.dimension());
        if (dimensionProgress != null) {
            dimensionProgress.remove(pos);
        }
        level.destroyBlockProgress(computeBreakerId(pos), pos, -1);
    }

    /**
     * Applies {@code damage} to the block at {@code blockPos}. Progress per call is {@code damage / hardness}, and the
     * block is destroyed once accumulated progress reaches {@code 1.0}, so harder blocks take proportionally longer.
     *
     * @param damage work applied by this hit, in "hardness units" (a damage of 1.5 instantly destroys stone)
     */
    public static Result damage(Level level, BlockPos blockPos, float damage) {
        if (damage <= 0F) {
            return Result.NOT_DAMAGED;
        }

        var pos = blockPos.immutable();
        var blockState = level.getBlockState(pos);

        if (blockState.isAir() || blockState.is(Blocks.FIRE)) {
            return Result.NOT_DAMAGED;
        }

        var hardness = blockState.getDestroySpeed(level, pos);
        if (hardness < 0F) {
            // Unbreakable (bedrock, barriers, portals, ...).
            return Result.NOT_DAMAGED;
        }

        var dimensionProgress = PROGRESS.computeIfAbsent(level.dimension(), key -> new HashMap<>());
        var existing = dimensionProgress.get(pos);
        var current = existing == null ? 0F : existing.value();
        var updated = current + damage / Math.max(hardness, MIN_EFFECTIVE_HARDNESS);

        if (updated >= 1F) {
            resetProgress(level, pos);
            level.destroyBlock(pos, false);
            return Result.DESTROYED;
        }

        dimensionProgress.put(pos, new Progress(level.getGameTime(), updated));
        level.destroyBlockProgress(computeBreakerId(pos), pos, toCrackStage(updated));

        return Result.DAMAGED;
    }

    /** Maps 0..1 progress onto the vanilla crack overlay stages 0..9. */
    private static int toCrackStage(float progress) {
        return (int) Mth.clamp(progress * 10F, 0F, 9F);
    }

    /**
     * Breaker ids share a namespace with entity ids on the client, so a positive hash could collide with a real entity
     * and move that entity's crack overlay. Entity ids are always non-negative, so mapping every position into the
     * negative range avoids that.
     */
    private static int computeBreakerId(BlockPos pos) {
        return -1 - (Long.hashCode(pos.asLong()) & Integer.MAX_VALUE);
    }

    private BlockBreakProgressManager() {
        throw new UnsupportedOperationException();
    }

    public enum Result {
        DAMAGED,
        DESTROYED,
        NOT_DAMAGED
    }
}
