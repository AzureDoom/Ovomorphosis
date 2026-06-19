package mod.azure.xenogenesis.util;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;

import mod.azure.xenogenesis.CommonMod;

public class ModTags {

    public static final TagKey<Block> WEAK_BLOCKS = TagKey.create(
        Registries.BLOCK,
        CommonMod.modResource("weak_blocks")
    );

    public static final TagKey<Block> DANGER_BLOCKS = TagKey.create(
        Registries.BLOCK,
        CommonMod.modResource("danger_blocks")
    );

    public static final TagKey<Block> ACID_RESISTANT_BLOCKS = TagKey.create(
        Registries.BLOCK,
        CommonMod.modResource("acid_resistant")
    );

    public static final TagKey<Fluid> DANGER_FLUIDS = TagKey.create(
        Registries.FLUID,
        CommonMod.modResource("danger_fluids")
    );

    public static final TagKey<EntityType<?>> DANGER_ENTITIES = TagKey.create(
        Registries.ENTITY_TYPE,
        CommonMod.modResource("danger_entities")
    );

    public static final TagKey<EntityType<?>> ACID_RESISTANT_ENTITIES = TagKey.create(
        Registries.ENTITY_TYPE,
        CommonMod.modResource("acid_resistant_entities")
    );

    public static final TagKey<EntityType<?>> FACEHUGGER_BLACKLIST = TagKey.create(
        Registries.ENTITY_TYPE,
        CommonMod.modResource("facehugger_blacklist")
    );
}
