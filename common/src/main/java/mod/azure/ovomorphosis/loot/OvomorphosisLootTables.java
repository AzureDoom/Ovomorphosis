package mod.azure.ovomorphosis.loot;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

public final class OvomorphosisLootTables {

    private OvomorphosisLootTables() {}

    public static final Set<ResourceLocation> SURVIVOR_NOTE_TARGETS = Set.of(
        vanillaNamespace("chests/abandoned_mineshaft"),
        vanillaNamespace("chests/simple_dungeon"),
        vanillaNamespace("chests/desert_pyramid"),
        vanillaNamespace("chests/ancient_city"),
        vanillaNamespace("chests/buried_treasure"),
        vanillaNamespace("chests/jungle_temple"),
        vanillaNamespace("chests/jungle_temple_dispenser"),
        vanillaNamespace("chests/mansion"),
        vanillaNamespace("chests/pillager_outpost"),
        vanillaNamespace("chests/shipwreck_supply"),
        vanillaNamespace("chests/shipwreck_treasure"),
        vanillaNamespace("chests/underwater_ruin_small"),
        vanillaNamespace("chests/underwater_ruin_big"),
        vanillaNamespace("chests/stronghold_corridor"),
        vanillaNamespace("chests/stronghold_crossing"),
        vanillaNamespace("chests/stronghold_library"),
        vanillaNamespace("chests/spawn_bonus_chest")
    );

    public static boolean shouldInjectSurvivorNote(ResourceLocation id) {
        return SURVIVOR_NOTE_TARGETS.contains(id);
    }

    private static ResourceLocation vanillaNamespace(String path) {
        return ResourceLocation.fromNamespaceAndPath("minecraft", path);
    }
}
