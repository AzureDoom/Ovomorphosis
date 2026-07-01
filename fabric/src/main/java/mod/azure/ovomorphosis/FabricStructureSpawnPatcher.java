package mod.azure.ovomorphosis;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.random.WeightedRandomList;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSpawnOverride;

import java.util.*;

import mod.azure.ovomorphosis.mixins.StructureAccessor;
import mod.azure.ovomorphosis.structuremodifier.StructureModifierEntry;
import mod.azure.ovomorphosis.structuremodifier.StructureModifierManager;
import mod.azure.ovomorphosis.structuremodifier.StructureModifierSpawn;

public final class FabricStructureSpawnPatcher {

    private FabricStructureSpawnPatcher() {}

    public static void patch(MinecraftServer server) {
        var structureRegistry = server.registryAccess().registryOrThrow(Registries.STRUCTURE);

        var patched = 0;

        for (var modifierEntry : StructureModifierManager.INSTANCE.getEntries().entrySet()) {
            var modifierId = modifierEntry.getKey();
            var modifier = modifierEntry.getValue();

            var resolvedStructures = resolveStructures(structureRegistry, modifier.structures());

            if (resolvedStructures.isEmpty()) {
                CommonMod.LOGGER.warn(
                    "Structure modifier {} resolved to no structures",
                    modifierId
                );
                continue;
            }

            for (var structure : resolvedStructures) {
                if (patchStructure(structure, modifier)) {
                    patched++;
                }
            }
        }

        CommonMod.LOGGER.info("Patched {} structures from structure_modifier datapacks", patched);
    }

    private static Set<Structure> resolveStructures(
        Registry<Structure> registry,
        List<String> refs
    ) {
        Set<Structure> resolved = new HashSet<>();

        for (var ref : refs) {
            if (ref.startsWith("#")) {
                var tagId = new ResourceLocation(ref.substring(1));
                var tagKey = TagKey.create(Registries.STRUCTURE, tagId);

                for (var holder : registry.getTagOrEmpty(tagKey)) {
                    resolved.add(holder.value());
                }
            } else {
                var structure = registry.get(new ResourceLocation(ref));
                if (structure != null) {
                    resolved.add(structure);
                }
            }
        }

        return resolved;
    }

    private static boolean patchStructure(Structure structure, StructureModifierEntry modifier) {
        var accessor = (StructureAccessor) structure;
        var oldSettings = accessor.ovomorphosis$getSettings();

        var newOverrides = new HashMap<>(oldSettings.spawnOverrides());
        var oldCategoryOverride = newOverrides.get(modifier.category());

        var newCategoryOverride = mergeSpawnsIntoOverride(
            oldCategoryOverride,
            modifier.boundingBox(),
            modifier.spawns()
        );

        newOverrides.put(modifier.category(), newCategoryOverride);

        var newSettings = new Structure.StructureSettings(
            oldSettings.biomes(),
            newOverrides,
            oldSettings.step(),
            oldSettings.terrainAdaptation()
        );

        accessor.ovomorphosis$setSettings(newSettings);
        return true;
    }

    private static StructureSpawnOverride mergeSpawnsIntoOverride(
        StructureSpawnOverride oldOverride,
        StructureSpawnOverride.BoundingBoxType boundingBoxType,
        java.util.List<StructureModifierSpawn> spawnDefs
    ) {
        ArrayList<MobSpawnSettings.SpawnerData> spawns = new ArrayList<>();

        if (oldOverride != null) {
            boundingBoxType = oldOverride.boundingBox();
            spawns.addAll(oldOverride.spawns().unwrap());
        }

        for (StructureModifierSpawn spawnDef : spawnDefs) {
            var entityType = BuiltInRegistries.ENTITY_TYPE.get(spawnDef.entity());

            var alreadyPresent = spawns.stream().anyMatch(existing -> existing.type == entityType);

            if (alreadyPresent) {
                continue;
            }

            spawns.add(
                new MobSpawnSettings.SpawnerData(
                    entityType,
                    spawnDef.weight(),
                    spawnDef.minCount(),
                    spawnDef.maxCount()
                )
            );
        }

        return new StructureSpawnOverride(boundingBoxType, WeightedRandomList.create(spawns));
    }
}
