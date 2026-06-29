package mod.azure.ovomorphosis.loot;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

public final class OvomorphosisLootTables {

    private OvomorphosisLootTables() {}

    public static final Set<ResourceLocation> SURVIVOR_NOTE_TARGETS = Set.of(
        new ResourceLocation("minecraft", "chests/abandoned_mineshaft"),
        new ResourceLocation("minecraft", "chests/simple_dungeon"),
        new ResourceLocation("minecraft", "chests/desert_pyramid"),
        new ResourceLocation("minecraft", "chests/ancient_city"),
        new ResourceLocation("minecraft", "chests/buried_treasure"),
        new ResourceLocation("minecraft", "chests/jungle_temple"),
        new ResourceLocation("minecraft", "chests/jungle_temple_dispenser"),
        new ResourceLocation("minecraft", "chests/mansion"),
        new ResourceLocation("minecraft", "chests/pillager_outpost"),
        new ResourceLocation("minecraft", "chests/shipwreck_supply"),
        new ResourceLocation("minecraft", "chests/shipwreck_treasure"),
        new ResourceLocation("minecraft", "chests/underwater_ruin_small"),
        new ResourceLocation("minecraft", "chests/underwater_ruin_big"),
        new ResourceLocation("minecraft", "chests/stronghold_corridor"),
        new ResourceLocation("minecraft", "chests/stronghold_crossing"),
        new ResourceLocation("minecraft", "chests/stronghold_library"),
        new ResourceLocation("minecraft", "chests/spawn_bonus_chest")
    );

    public static boolean shouldInjectSurvivorNote(ResourceLocation id) {
        return SURVIVOR_NOTE_TARGETS.contains(id);
    }
}
