package mod.azure.xenogenesis;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.registry.FlammableBlockRegistry;
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
import mod.azure.xenogenesis.entities.xenomorph.XenomorphEntity;
import mod.azure.xenogenesis.registry.BlockRegistry;
import mod.azure.xenogenesis.registry.EntityRegistry;
import mod.azure.xenogenesis.registry.ItemRegistry;
import mod.azure.xenogenesis.util.ModTags;

public final class FabricLibMod implements ModInitializer {

    @Override
    public void onInitialize() {
        CommonMod.initRegistries();
        ResourceManagerHelper.get(PackType.SERVER_DATA)
            .registerReloadListener(new FabricHeadOffsetReloadListener());
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
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.SPAWN_EGGS).register(entries -> {
            entries.accept(ItemRegistry.OVOMORPH_SPAWN_EGG.get());
            entries.accept(ItemRegistry.FACEHUGGER_SPAWN_EGG.get());
            entries.accept(ItemRegistry.CHESTBURSTER_SPAWN_EGG.get());
            entries.accept(ItemRegistry.XENOMORPH_SPAWN_EGG.get());
        });
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.BUILDING_BLOCKS).register(entries -> {
            entries.accept(BlockRegistry.RESIN_ITEM.get());
            entries.accept(BlockRegistry.RESIN_BLOCK_ITEM.get());
            entries.accept(BlockRegistry.RESIN_WEB_ITEM.get());
            entries.accept(BlockRegistry.RESIN_WEB_CROSS_ITEM.get());
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
