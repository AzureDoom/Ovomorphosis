package mod.azure.ovomorphosis;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSpawnOverride;
import org.jspecify.annotations.Nullable;

import java.util.*;

import mod.azure.ovomorphosis.mixins.StructureAccessor;
import mod.azure.ovomorphosis.structuremodifier.StructureModifierManager;

public final class FabricStructureSpawnPatcher {

    private FabricStructureSpawnPatcher() {}

    public static void patch(MinecraftServer server) {
        var structureRegistry = server.registryAccess().lookupOrThrow(Registries.STRUCTURE);

        var patched = 0;

        for (var modifierEntry : StructureModifierManager.INSTANCE.getEntries().entrySet()) {
            var modifierId = modifierEntry.getKey();
            var modifier = modifierEntry.getValue();

            var resolvedStructures = resolveStructures(structureRegistry, modifier.structures());
            if (resolvedStructures.isEmpty()) {
                CommonMod.LOGGER.warn("Structure modifier {} resolved to no structures", modifierId);
                continue;
            }

            var spawnsByCategory = groupByCategory(modifierId, modifier.spawners());
            if (spawnsByCategory.isEmpty())
                continue;

            for (var structure : resolvedStructures) {
                patchStructure(structure, modifier.boundingBox(), spawnsByCategory);
                patched++;
            }
        }

        CommonMod.LOGGER.info("Patched {} structures from structure_modifier datapacks", patched);
    }

    private static Set<Structure> resolveStructures(Registry<Structure> registry, List<String> refs) {
        Set<Structure> resolved = new HashSet<>();

        for (var ref : refs) {
            if (ref.startsWith("#")) {
                var tagKey = TagKey.create(Registries.STRUCTURE, Identifier.parse(ref.substring(1)));
                for (var holder : registry.getTagOrEmpty(tagKey)) {
                    resolved.add(holder.value());
                }
            } else {
                var structure = registry.getValue(Identifier.parse(ref));
                if (structure != null) {
                    resolved.add(structure);
                }
            }
        }

        return resolved;
    }

    /**
     * Splits spawners by their entity's own mob category, matching NeoForge's add_spawns. MISC-category entities are
     * rejected, since vanilla's SpawnerData constructor would silently turn them into pigs.
     */
    private static Map<MobCategory, List<Weighted<MobSpawnSettings.SpawnerData>>> groupByCategory(
        Identifier modifierId,
        List<Weighted<MobSpawnSettings.SpawnerData>> spawners
    ) {
        Map<MobCategory, List<Weighted<MobSpawnSettings.SpawnerData>>> byCategory = new EnumMap<>(MobCategory.class);

        for (var spawner : spawners) {
            var category = spawner.value().type().getCategory();
            if (category == MobCategory.MISC) {
                CommonMod.LOGGER.warn(
                    "Structure modifier {} lists {}, which is MISC category and cannot spawn naturally; skipping",
                    modifierId,
                    spawner.value().type()
                );
                continue;
            }
            byCategory.computeIfAbsent(category, key -> new ArrayList<>()).add(spawner);
        }

        return byCategory;
    }

    private static void patchStructure(
        Structure structure,
        StructureSpawnOverride.BoundingBoxType boundingBox,
        Map<MobCategory, List<Weighted<MobSpawnSettings.SpawnerData>>> spawnsByCategory
    ) {
        var accessor = (StructureAccessor) structure;
        var oldSettings = accessor.ovomorphosis$getSettings();

        var newOverrides = new HashMap<>(oldSettings.spawnOverrides());

        for (var entry : spawnsByCategory.entrySet()) {
            var category = entry.getKey();
            newOverrides.put(
                category,
                mergeSpawnsIntoOverride(newOverrides.get(category), boundingBox, entry.getValue())
            );
        }

        accessor.ovomorphosis$setSettings(
            new Structure.StructureSettings(
                oldSettings.biomes(),
                newOverrides,
                oldSettings.step(),
                oldSettings.terrainAdaptation()
            )
        );
    }

    private static StructureSpawnOverride mergeSpawnsIntoOverride(
        @Nullable StructureSpawnOverride oldOverride,
        StructureSpawnOverride.BoundingBoxType boundingBox,
        List<Weighted<MobSpawnSettings.SpawnerData>> additions
    ) {
        List<Weighted<MobSpawnSettings.SpawnerData>> spawns = new ArrayList<>();

        if (oldOverride != null) {
            boundingBox = oldOverride.boundingBox();
            spawns.addAll(oldOverride.spawns().unwrap());
        }

        for (var addition : additions) {
            var type = addition.value().type();
            // Keeps repeated patch() calls (e.g. after /reload) from stacking duplicate entries.
            if (spawns.stream().noneMatch(existing -> existing.value().type() == type)) {
                spawns.add(addition);
            }
        }

        return new StructureSpawnOverride(boundingBox, WeightedList.of(spawns));
    }
}
