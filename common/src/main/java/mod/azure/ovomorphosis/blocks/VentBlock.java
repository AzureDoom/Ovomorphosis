package mod.azure.ovomorphosis.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import mod.azure.ovomorphosis.data.OvomorphosisSavedData;

/**
 * A plain full-cube resin block (like {@link AbstractResinBlock}'s other direct subclasses) that additionally
 * registers/unregisters itself with the nearest hive's {@code HiveMemory} vent network on placement/removal.
 * <p>
 * This is what makes vent blocks work regardless of who or what placed them — the AI's own procedural placement (see
 * {@code PlaceResinAction}, which also calls {@code HiveMemory#registerVentBlock} directly as a placement-order
 * micro-optimization) just as much as a block placed by hand in creative mode, by a structure, or by a datapack.
 * Registration deliberately uses {@link OvomorphosisSavedData#findNearestHive}, which — unlike
 * {@code OvomorphosisSavedData#getOrCreateHive} — never creates a new (dome-less) hive entry as a side effect of a
 * single block placement; a vent block placed with no hive within the usual join radius simply isn't tracked by
 * anything yet, exactly like an isolated xenomorph having no vent network to use.
 */
public class VentBlock extends AbstractResinBlock {

    public VentBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected void onPlace(
        @NotNull BlockState state,
        @NotNull Level level,
        @NotNull BlockPos pos,
        @NotNull BlockState oldState,
        boolean movedByPiston
    ) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            OvomorphosisSavedData.findNearestHive(serverLevel, pos)
                .ifPresent(hive -> {
                    hive.registerVentBlock(pos);
                    OvomorphosisSavedData.markHiveDirty(serverLevel);
                });
        }
    }

    @Override
    protected void onRemove(
        @NotNull BlockState state,
        @NotNull Level level,
        @NotNull BlockPos pos,
        @NotNull BlockState newState,
        boolean movedByPiston
    ) {
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            OvomorphosisSavedData.findNearestHive(serverLevel, pos)
                .ifPresent(hive -> {
                    hive.unregisterVentBlock(pos);
                    OvomorphosisSavedData.markHiveDirty(serverLevel);
                });
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
