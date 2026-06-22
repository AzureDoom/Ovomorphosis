package mod.azure.ovomorphosis.registry;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.function.Supplier;

import mod.azure.ovomorphosis.blocks.ResinWebBlockEntity;
import mod.azure.ovomorphosis.services.XenoServices;

public class BlockEntityRegistry {

    private BlockEntityRegistry() {}

    public static final Supplier<BlockEntityType<ResinWebBlockEntity>> RESIN_WEB_CROSS_BE =
        XenoServices.COMMON_REGISTRY.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            "resin_web_cross",
            () -> BlockEntityType.Builder
                .of(ResinWebBlockEntity::new, BlockRegistry.RESIN_WEB_CROSS.get())
                .build(null)
        );

    public static void initialize() {}
}
