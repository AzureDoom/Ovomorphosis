package mod.azure.xenogenesis;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.server.packs.PackType;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.levelgen.Heightmap;

import mod.azure.xenogenesis.entities.chestburster.ChestbursterEntity;
import mod.azure.xenogenesis.entities.facehugger.FacehuggerEntity;
import mod.azure.xenogenesis.entities.ovomorph.OvomorphEntity;
import mod.azure.xenogenesis.entities.queen.QueenEntity;
import mod.azure.xenogenesis.entities.xenomorph.XenomorphEntity;
import mod.azure.xenogenesis.registry.EntityRegistry;
import mod.azure.xenogenesis.registry.ItemRegistry;

public final class FabricLibMod implements ModInitializer {

    @Override
    public void onInitialize() {
        CommonMod.initRegistries();
        ResourceManagerHelper.get(PackType.SERVER_DATA)
            .registerReloadListener(new FabricHeadOffsetReloadListener());
        FabricDefaultAttributeRegistry.register(
            EntityRegistry.OVOMORPH.get(),
            OvomorphEntity.createAttributes()
        );
        FabricDefaultAttributeRegistry.register(
            EntityRegistry.FACEHUGGER.get(),
            FacehuggerEntity.createAttributes()
        );
        FabricDefaultAttributeRegistry.register(
            EntityRegistry.CHESTBURSTER.get(),
            ChestbursterEntity.createAttributes()
        );
        FabricDefaultAttributeRegistry.register(
            EntityRegistry.XENOMORPH.get(),
            XenomorphEntity.createAttributes()
        );
        FabricDefaultAttributeRegistry.register(
            EntityRegistry.QUEEN.get(),
            QueenEntity.createAttributes()
        );
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.SPAWN_EGGS).register(entries -> {
            entries.accept(ItemRegistry.OVOMORPH_SPAWN_EGG.get());
            entries.accept(ItemRegistry.FACEHUGGER_SPAWN_EGG.get());
            entries.accept(ItemRegistry.CHESTBURSTER_SPAWN_EGG.get());
            entries.accept(ItemRegistry.XENOMORPH_SPAWN_EGG.get());
            entries.accept(ItemRegistry.QUEEN_SPAWN_EGG.get());
        });
        SpawnPlacements.register(
            EntityRegistry.FACEHUGGER.get(),
            SpawnPlacementTypes.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            (entityType, world, reason, pos, random) -> world.getBiome(pos).is(BiomeTags.IS_OVERWORLD)
        );
        SpawnPlacements.register(
            EntityRegistry.OVOMORPH.get(),
            SpawnPlacementTypes.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            (entityType, world, reason, pos, random) -> world.getBiome(pos).is(BiomeTags.IS_OVERWORLD)
        );
        SpawnPlacements.register(
            EntityRegistry.CHESTBURSTER.get(),
            SpawnPlacementTypes.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            (entityType, world, reason, pos, random) -> world.getBiome(pos).is(BiomeTags.IS_OVERWORLD)
        );
        SpawnPlacements.register(
            EntityRegistry.XENOMORPH.get(),
            SpawnPlacementTypes.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            (entityType, world, reason, pos, random) -> world.getBiome(pos).is(BiomeTags.IS_OVERWORLD)
        );
        SpawnPlacements.register(
            EntityRegistry.QUEEN.get(),
            SpawnPlacementTypes.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            (entityType, world, reason, pos, random) -> world.getBiome(pos).is(BiomeTags.IS_OVERWORLD)
        );
        // BiomeModifications.addSpawn(
        // BiomeSelectors.tag(ModTags.SPAWN_ARACHNIDS),
        // MobCategory.MONSTER,
        // EntityRegistry.WORKERBUG.get(),
        // 20,
        // 1,
        // 4
        // );
    }
}
