package mod.azure.ovomorphosis.loot;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

public final class OvomorphosisLootTables {

    private OvomorphosisLootTables() {}

    public static final Set<ResourceLocation> SURVIVOR_NOTE_TARGETS = Set.of(
        ResourceLocation.fromNamespaceAndPath("minecraft", "chests/abandoned_mineshaft"),
        ResourceLocation.fromNamespaceAndPath("minecraft", "chests/simple_dungeon"),
        ResourceLocation.fromNamespaceAndPath("minecraft", "chests/desert_pyramid"),
        ResourceLocation.fromNamespaceAndPath("minecraft", "chests/ancient_city"),
        ResourceLocation.fromNamespaceAndPath("minecraft", "chests/buried_treasure"),
        ResourceLocation.fromNamespaceAndPath("minecraft", "chests/jungle_temple"),
        ResourceLocation.fromNamespaceAndPath("minecraft", "chests/jungle_temple_dispenser"),
        ResourceLocation.fromNamespaceAndPath("minecraft", "chests/mansion"),
        ResourceLocation.fromNamespaceAndPath("minecraft", "chests/pillager_outpost"),
        ResourceLocation.fromNamespaceAndPath("minecraft", "chests/shipwreck_supply"),
        ResourceLocation.fromNamespaceAndPath("minecraft", "chests/shipwreck_treasure"),
        ResourceLocation.fromNamespaceAndPath("minecraft", "chests/underwater_ruin_small"),
        ResourceLocation.fromNamespaceAndPath("minecraft", "chests/underwater_ruin_big"),
        ResourceLocation.fromNamespaceAndPath("minecraft", "chests/stronghold_corridor"),
        ResourceLocation.fromNamespaceAndPath("minecraft", "chests/stronghold_crossing"),
        ResourceLocation.fromNamespaceAndPath("minecraft", "chests/stronghold_library"),
        ResourceLocation.fromNamespaceAndPath("minecraft", "chests/spawn_bonus_chest")
    );

    public static boolean shouldInjectSurvivorNote(ResourceLocation id) {
        return SURVIVOR_NOTE_TARGETS.contains(id);
    }
}
