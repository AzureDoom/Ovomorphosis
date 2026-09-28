package mod.azure.ovomorphosis.registry;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.Set;
import java.util.function.Supplier;

import mod.azure.ovomorphosis.blocks.ResinWebBlockEntity;
import mod.azure.ovomorphosis.services.XenoServices;

public class BlockEntityRegistry {

    private BlockEntityRegistry() {}

    public static final Supplier<BlockEntityType<ResinWebBlockEntity>> RESIN_WEB_CROSS_BE = registerBlockEntity(
        "resin_web_cross",
        () -> new BlockEntityType<>(
            ResinWebBlockEntity::new,
            Set.of(BlockRegistry.RESIN_WEB_CROSS.get())
        )
    );

    static <T extends BlockEntity> Supplier<BlockEntityType<T>> registerBlockEntity(
        String blockEntityName,
        Supplier<BlockEntityType<T>> blockEntity
    ) {
        return XenoServices.COMMON_REGISTRY.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            blockEntityName,
            blockEntity
        );
    }

    public static void initialize() {}
}
