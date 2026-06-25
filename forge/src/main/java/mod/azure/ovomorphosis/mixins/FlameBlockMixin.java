package mod.azure.ovomorphosis.mixins;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.IPlantable;
import net.minecraftforge.common.extensions.IForgeBlock;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;

import mod.azure.ovomorphosis.blocks.AbstractResinBlock;

@Mixin(AbstractResinBlock.class)
public class FlameBlockMixin implements IForgeBlock {

    @Override
    public int getFireSpreadSpeed(
        @NotNull BlockState state,
        @NotNull BlockGetter level,
        @NotNull BlockPos pos,
        @NotNull Direction direction
    ) {
        return 5;
    }

    @Override
    public boolean canSustainPlant(
        BlockState blockState,
        BlockGetter blockGetter,
        BlockPos blockPos,
        Direction direction,
        IPlantable iPlantable
    ) {
        return false;
    }

    @Override
    public int getFlammability(
        @NotNull BlockState state,
        @NotNull BlockGetter level,
        @NotNull BlockPos pos,
        @NotNull Direction direction
    ) {
        return 5;
    }

    @Override
    public boolean isFlammable(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return true;
    }
}
