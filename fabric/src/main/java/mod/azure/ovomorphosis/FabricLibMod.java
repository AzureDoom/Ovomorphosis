package mod.azure.ovomorphosis;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.registry.FlammableBlockRegistry;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.server.packs.PackType;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.levelgen.Heightmap;

import mod.azure.ovomorphosis.client.facehugger.EntityHeadData;
import mod.azure.ovomorphosis.entities.chestburster.ChestbursterEntity;
import mod.azure.ovomorphosis.entities.facehugger.FacehuggerEntity;
import mod.azure.ovomorphosis.entities.ovomorph.OvomorphEntity;
import mod.azure.ovomorphosis.entities.runner.RunnerEntity;
import mod.azure.ovomorphosis.entities.xenomorph.XenomorphEntity;
import mod.azure.ovomorphosis.level.ResinWebRegistry;
import mod.azure.ovomorphosis.network.HeadDataSyncPacket;
import mod.azure.ovomorphosis.registry.BlockRegistry;
import mod.azure.ovomorphosis.registry.EntityRegistry;
import mod.azure.ovomorphosis.registry.ItemRegistry;
import mod.azure.ovomorphosis.structuremodifier.StructureModifierManager;
import mod.azure.ovomorphosis.util.ModTags;

@SuppressWarnings("unused")
public final class FabricLibMod implements ModInitializer {

    @Override
    public void onInitialize() {
        CommonMod.initRegistries();
        FabricLootInjects.init();
        ResourceLoader.get(PackType.SERVER_DATA)
            .registerReloadListener(
                CommonMod.modResource("ovomorphosis_head_data"),
                new EntityHeadData.ReloadListener()
            );
        PayloadTypeRegistry.clientboundPlay().register(HeadDataSyncPacket.TYPE, HeadDataSyncPacket.CODEC);
        ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS.register(
            (player, joined) -> ServerPlayNetworking.send(player, HeadDataSyncPacket.fromCurrent())
        );
        ServerLifecycleEvents.SERVER_STARTED.register(FabricStructureSpawnPatcher::patch);

        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            if (success) {
                FabricStructureSpawnPatcher.patch(server);
            }
        });
        ResourceLoader.get(PackType.SERVER_DATA)
            .registerReloadListener(
                StructureModifierManager.ID,
                StructureModifierManager.INSTANCE
            );
        FlammableBlockRegistry.getDefaultInstance().add(ModTags.RESIN, 50, 50);
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
            EntityRegistry.RUNNER.get(),
            RunnerEntity.createAttributes()
        );
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.SPAWN_EGGS).register(entries -> {
            entries.accept(ItemRegistry.OVOMORPH_SPAWN_EGG.get());
            entries.accept(ItemRegistry.FACEHUGGER_SPAWN_EGG.get());
            entries.accept(ItemRegistry.CHESTBURSTER_SPAWN_EGG.get());
            entries.accept(ItemRegistry.XENOMORPH_SPAWN_EGG.get());
            entries.accept(ItemRegistry.RUNNER_SPAWN_EGG.get());
        });
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.BUILDING_BLOCKS).register(entries -> {
            entries.accept(BlockRegistry.RESIN_ITEM.get());
            entries.accept(BlockRegistry.RESIN_WEB_ITEM.get());
            entries.accept(BlockRegistry.RESIN_WEB_CROSS_ITEM.get());
            entries.accept(BlockRegistry.RESIN_VENT_ITEM.get());
        });
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.COMBAT)
            .register(entries -> entries.accept(ItemRegistry.FLAMETHROWER.get()));
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
            entries.accept(ItemRegistry.SCANNER.get());
            entries.accept(ItemRegistry.MOTION_TRACKER.get());
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
            OvomorphEntity::canOvomorphSpawn
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
            EntityRegistry.RUNNER.get(),
            SpawnPlacementTypes.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            (entityType, world, reason, pos, random) -> world.getBiome(pos).is(BiomeTags.IS_OVERWORLD)
        );
        ServerLevelEvents.UNLOAD.register((server, level) -> ResinWebRegistry.clearDimension(level.dimension()));
    }
}
