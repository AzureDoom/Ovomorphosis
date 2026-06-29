package mod.azure.ovomorphosis;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.registry.FlammableBlockRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.server.packs.PackType;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.levelgen.Heightmap;

import mod.azure.ovomorphosis.entities.chestburster.ChestbursterEntity;
import mod.azure.ovomorphosis.entities.facehugger.FacehuggerEntity;
import mod.azure.ovomorphosis.entities.ovomorph.OvomorphEntity;
import mod.azure.ovomorphosis.entities.runner.RunnerEntity;
import mod.azure.ovomorphosis.entities.xenomorph.XenomorphEntity;
import mod.azure.ovomorphosis.level.ResinWebRegistry;
import mod.azure.ovomorphosis.network.FabricNetworkDispatcher;
import mod.azure.ovomorphosis.network.NetworkDispatcher;
import mod.azure.ovomorphosis.registry.BlockRegistry;
import mod.azure.ovomorphosis.registry.EntityRegistry;
import mod.azure.ovomorphosis.registry.ItemRegistry;
import mod.azure.ovomorphosis.util.ModTags;

public final class FabricLibMod implements ModInitializer {

    @Override
    public void onInitialize() {
        CommonMod.initRegistries();
        FabricLootInjects.init();
        NetworkDispatcher.Holder.INSTANCE = new FabricNetworkDispatcher();
        ResourceManagerHelper.get(PackType.SERVER_DATA)
            .registerReloadListener(new FabricHeadOffsetReloadListener());
        ServerLifecycleEvents.SERVER_STARTED.register(FabricStructureSpawnPatcher::patch);

        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            if (success) {
                FabricStructureSpawnPatcher.patch(server);
            }
        });
        FlammableBlockRegistry.getDefaultInstance().add(ModTags.RESIN, 5, 5);
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
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.SPAWN_EGGS).register(entries -> {
            entries.accept(ItemRegistry.OVOMORPH_SPAWN_EGG.get());
            entries.accept(ItemRegistry.FACEHUGGER_SPAWN_EGG.get());
            entries.accept(ItemRegistry.CHESTBURSTER_SPAWN_EGG.get());
            entries.accept(ItemRegistry.XENOMORPH_SPAWN_EGG.get());
            entries.accept(ItemRegistry.RUNNER_SPAWN_EGG.get());
        });
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.BUILDING_BLOCKS).register(entries -> {
            entries.accept(BlockRegistry.RESIN_ITEM.get());
            entries.accept(BlockRegistry.RESIN_WEB_ITEM.get());
            entries.accept(BlockRegistry.RESIN_WEB_CROSS_ITEM.get());
        });
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.COMBAT).register(entries -> {
            entries.accept(ItemRegistry.FLAMETHROWER.get());
            entries.accept(ItemRegistry.SCANNER.get());
            entries.accept(ItemRegistry.MOTION_TRACKER.get());
        });
        SpawnPlacements.register(
            EntityRegistry.FACEHUGGER.get(),
            SpawnPlacements.Type.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            (entityType, world, reason, pos, random) -> world.getBiome(pos).is(BiomeTags.IS_OVERWORLD)
        );
        SpawnPlacements.register(
            EntityRegistry.OVOMORPH.get(),
            SpawnPlacements.Type.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            OvomorphEntity::canOvomorphSpawn
        );
        SpawnPlacements.register(
            EntityRegistry.CHESTBURSTER.get(),
            SpawnPlacements.Type.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            (entityType, world, reason, pos, random) -> world.getBiome(pos).is(BiomeTags.IS_OVERWORLD)
        );
        SpawnPlacements.register(
            EntityRegistry.XENOMORPH.get(),
            SpawnPlacements.Type.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            (entityType, world, reason, pos, random) -> world.getBiome(pos).is(BiomeTags.IS_OVERWORLD)
        );
        SpawnPlacements.register(
            EntityRegistry.RUNNER.get(),
            SpawnPlacements.Type.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            (entityType, world, reason, pos, random) -> world.getBiome(pos).is(BiomeTags.IS_OVERWORLD)
        );
        ServerWorldEvents.UNLOAD.register((server, world) -> ResinWebRegistry.clearDimension(world.dimension()));
    }
}
