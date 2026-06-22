package mod.azure.ovomorphosis.registry;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.function.Supplier;

import mod.azure.ovomorphosis.blocks.AbstractResinBlock;
import mod.azure.ovomorphosis.blocks.ResinBlock;
import mod.azure.ovomorphosis.blocks.ResinWebBlock;
import mod.azure.ovomorphosis.blocks.ResinWebFullBlock;
import mod.azure.ovomorphosis.services.XenoServices;

public class BlockRegistry {

    private BlockRegistry() {}

    public static final Supplier<Block> RESIN = registerBlock(
        "resin",
        () -> new ResinBlock(
            BlockBehaviour.Properties.of().sound(SoundType.MOSS).strength(5.0f, 8.0f)
        )
    );

    public static final Supplier<BlockItem> RESIN_ITEM = ItemRegistry.registerItem(
        "resin",
        () -> new BlockItem(RESIN.get(), new Item.Properties())
    );

    public static final Supplier<Block> RESIN_BLOCK = registerBlock(
        "resin_block",
        () -> new AbstractResinBlock(
            BlockBehaviour.Properties.of().sound(SoundType.MOSS).strength(5.0f, 8.0f)
        ) {}
    );

    public static final Supplier<BlockItem> RESIN_BLOCK_ITEM = ItemRegistry.registerItem(
        "resin_block",
        () -> new BlockItem(RESIN_BLOCK.get(), new Item.Properties())
    );

    public static final Supplier<Block> RESIN_WEB = registerBlock(
        "resin_web",
        () -> new ResinWebBlock(
            BlockBehaviour.Properties.of().sound(SoundType.MOSS).strength(5.0f, 8.0f).noCollission()
        )
    );

    public static final Supplier<BlockItem> RESIN_WEB_ITEM = ItemRegistry.registerItem(
        "resin_web",
        () -> new BlockItem(RESIN_WEB.get(), new Item.Properties())
    );

    public static final Supplier<Block> RESIN_WEB_CROSS = registerBlock(
        "resin_web_cross",
        () -> new ResinWebFullBlock(
            BlockBehaviour.Properties.of()
                .sound(
                    SoundType.MOSS
                )
                .noOcclusion()
                .requiresCorrectToolForDrops()
                .strength(5.0f, 8.0f)
                .noCollission()
        )
    );

    public static final Supplier<BlockItem> RESIN_WEB_CROSS_ITEM = ItemRegistry.registerItem(
        "resin_web_cross",
        () -> new BlockItem(RESIN_WEB_CROSS.get(), new Item.Properties())
    );

    static <T extends Block> Supplier<T> registerBlock(String blockName, Supplier<T> block) {
        return XenoServices.COMMON_REGISTRY.register(BuiltInRegistries.BLOCK, blockName, block);
    }

    public static void initialize() {}
}
