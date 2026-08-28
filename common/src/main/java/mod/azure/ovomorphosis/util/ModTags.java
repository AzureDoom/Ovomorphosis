package mod.azure.ovomorphosis.util;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.material.Fluid;

import mod.azure.ovomorphosis.CommonMod;

public record ModTags() {

    public static final TagKey<Block> RESIN = TagKey.create(
        Registries.BLOCK,
        CommonMod.modResource("resin")
    );

    public static final TagKey<Block> WEAK_BLOCKS = TagKey.create(
        Registries.BLOCK,
        CommonMod.modResource("weak_blocks")
    );

    public static final TagKey<Block> VENT_BLOCKS = TagKey.create(
        Registries.BLOCK,
        CommonMod.modResource("vent_blocks")
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

    public static final TagKey<EntityType<?>> XENO_GRAB_BLACKLIST = TagKey.create(
        Registries.ENTITY_TYPE,
        CommonMod.modResource("xeno_grab_blacklist")
    );

    public static final TagKey<EntityType<?>> XENOMORPH_HOST = TagKey.create(
        Registries.ENTITY_TYPE,
        CommonMod.modResource("xenomorph_host")
    );

    public static final TagKey<EntityType<?>> RUNNER_HOST = TagKey.create(
        Registries.ENTITY_TYPE,
        CommonMod.modResource("runner_host")
    );

    public static final TagKey<EntityType<?>> MOTION_TRACKABLE = TagKey.create(
        Registries.ENTITY_TYPE,
        CommonMod.modResource("motion_trackable")
    );

    public static final TagKey<EntityType<?>> UNDEAD = TagKey.create(
        Registries.ENTITY_TYPE,
        CommonMod.modResource("undead")
    );

    public static final TagKey<Structure> INFESTABLE_STRUCTURES = TagKey.create(
        Registries.STRUCTURE,
        CommonMod.modResource("infestable_structures")
    );

    public static final TagKey<MobEffect> REMOVABLE_EFFECTS = TagKey.create(
        Registries.MOB_EFFECT,
        CommonMod.modResource("removable_effects")
    );

    public static final TagKey<Item> BURSTER_FOOD = TagKey.create(
        Registries.ITEM,
        CommonMod.modResource("burster_foods")
    );

    public static final TagKey<Item> FACEHUGGER_BLOCKING_HELMETS = TagKey.create(
        Registries.ITEM,
        CommonMod.modResource("facehugger_blocking_helmets")
    );

    public static final TagKey<Item> POTIONS = TagKey.create(Registries.ITEM, CommonMod.modResource("potions"));
}
