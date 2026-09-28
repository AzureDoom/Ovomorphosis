package mod.azure.ovomorphosis.registry;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.function.Function;
import java.util.function.Supplier;

import mod.azure.ovomorphosis.blocks.*;
import mod.azure.ovomorphosis.services.XenoServices;

public class BlockRegistry {

    private BlockRegistry() {}

    public static final Supplier<Block> RESIN = registerBlock(
        "resin",
        ResinBlock::new,
        BlockBehaviour.Properties.of().sound(SoundType.MOSS).strength(5.0f, 8.0f).randomTicks()
    );

    public static final Supplier<BlockItem> RESIN_ITEM = ItemRegistry.registerItem(
        "resin",
        properties -> new BlockItem(RESIN.get(), properties),
        Item.Properties::useBlockDescriptionPrefix
    );

    public static final Supplier<Block> RESIN_BLOCK = registerBlock(
        "resin_block",
        properties -> new AbstractResinBlock(properties) {},
        BlockBehaviour.Properties.of().sound(SoundType.MOSS).strength(5.0f, 8.0f).randomTicks()
    );

    public static final Supplier<BlockItem> RESIN_BLOCK_ITEM = ItemRegistry.registerItem(
        "resin_block",
        properties -> new BlockItem(RESIN_BLOCK.get(), properties),
        Item.Properties::useBlockDescriptionPrefix
    );

    public static final Supplier<Block> RESIN_VENT = registerBlock(
        "resin_vent",
        VentBlock::new,
        BlockBehaviour.Properties.of().sound(SoundType.METAL).strength(5.0f, 8.0f)
    );

    public static final Supplier<BlockItem> RESIN_VENT_ITEM = ItemRegistry.registerItem(
        "resin_vent",
        properties -> new BlockItem(RESIN_VENT.get(), properties),
        Item.Properties::useBlockDescriptionPrefix
    );

    public static final Supplier<Block> RESIN_WEB = registerBlock(
        "resin_web",
        ResinWebBlock::new,
        BlockBehaviour.Properties.of().sound(SoundType.MOSS).strength(5.0f, 8.0f).noCollision().randomTicks()
    );

    public static final Supplier<BlockItem> RESIN_WEB_ITEM = ItemRegistry.registerItem(
        "resin_web",
        properties -> new BlockItem(RESIN_WEB.get(), properties),
        Item.Properties::useBlockDescriptionPrefix
    );

    public static final Supplier<Block> RESIN_WEB_CROSS = registerBlock(
        "resin_web_cross",
        ResinWebFullBlock::new,
        BlockBehaviour.Properties.of()
            .sound(
                SoundType.MOSS
            )
            .noOcclusion()
            .requiresCorrectToolForDrops()
            .strength(2.0f, 0.0f)
            .randomTicks()
            .noCollision()
    );

    public static final Supplier<BlockItem> RESIN_WEB_CROSS_ITEM = ItemRegistry.registerItem(
        "resin_web_cross",
        properties -> new BlockItem(RESIN_WEB_CROSS.get(), properties),
        Item.Properties::useBlockDescriptionPrefix
    );

    static <T extends Block> Supplier<T> registerBlock(
        String blockName,
        Function<BlockBehaviour.Properties, T> factory,
        BlockBehaviour.Properties properties
    ) {
        return XenoServices.COMMON_REGISTRY.registerBlock(blockName, factory, properties);
    }

    public static void initialize() {}
}
