package mod.azure.xenogenesis;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.random.WeightedRandomList;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSpawnOverride;

import java.util.ArrayList;
import java.util.HashMap;

import mod.azure.xenogenesis.mixins.StructureAccessor;
import mod.azure.xenogenesis.registry.EntityRegistry;
import mod.azure.xenogenesis.util.ModTags;

public final class FabricStructureSpawnPatcher {

    private static final int OVOMORPH_WEIGHT = 2;

    private static final int OVOMORPH_MIN_COUNT = 1;

    private static final int OVOMORPH_MAX_COUNT = 1;

    private FabricStructureSpawnPatcher() {}

    public static void patch(MinecraftServer server) {
        var structureRegistry =
            server.registryAccess().registryOrThrow(Registries.STRUCTURE);

        var patched = 0;

        for (var holder : structureRegistry.getTagOrEmpty(ModTags.INFESTABLE_STRUCTURES)) {
            Structure structure = holder.value();

            if (patchStructure(structure)) {
                patched++;
            }
        }

        CommonMod.LOGGER.info("Patched {} structures", patched);
    }

    private static boolean patchStructure(Structure structure) {
        var accessor = (StructureAccessor) structure;

        var oldSettings = accessor.xenogenesis$getSettings();

        var newOverrides =
            new HashMap<>(oldSettings.spawnOverrides());

        var oldMonsterOverride =
            newOverrides.get(MobCategory.MONSTER);

        var newMonsterOverride =
            mergeOvomorphIntoMonsterOverride(oldMonsterOverride);

        newOverrides.put(MobCategory.MONSTER, newMonsterOverride);

        var newSettings = new Structure.StructureSettings(
            oldSettings.biomes(),
            newOverrides,
            oldSettings.step(),
            oldSettings.terrainAdaptation()
        );

        accessor.xenogenesis$setSettings(newSettings);
        return true;
    }

    private static StructureSpawnOverride mergeOvomorphIntoMonsterOverride(
        StructureSpawnOverride oldOverride
    ) {
        ArrayList<MobSpawnSettings.SpawnerData> spawns = new ArrayList<>();

        var boundingBoxType =
            StructureSpawnOverride.BoundingBoxType.STRUCTURE;

        if (oldOverride != null) {
            boundingBoxType = oldOverride.boundingBox();

            for (var spawn : oldOverride.spawns().unwrap()) {
                if (spawn.type == EntityRegistry.OVOMORPH.get()) {
                    return oldOverride;
                }

                spawns.add(spawn);
            }
        }

        spawns.add(
            new MobSpawnSettings.SpawnerData(
                EntityRegistry.OVOMORPH.get(),
                OVOMORPH_WEIGHT,
                OVOMORPH_MIN_COUNT,
                OVOMORPH_MAX_COUNT
            )
        );

        return new StructureSpawnOverride(
            boundingBoxType,
            WeightedRandomList.create(spawns)
        );
    }
}
