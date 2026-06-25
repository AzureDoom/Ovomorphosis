package mod.azure.ovomorphosis;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.entity.SpawnPlacementRegisterEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

import mod.azure.ovomorphosis.client.facehugger.EntityHeadOffsetData;
import mod.azure.ovomorphosis.entities.chestburster.ChestbursterEntity;
import mod.azure.ovomorphosis.entities.facehugger.FacehuggerEntity;
import mod.azure.ovomorphosis.entities.ovomorph.OvomorphEntity;
import mod.azure.ovomorphosis.entities.runner.RunnerEntity;
import mod.azure.ovomorphosis.entities.xenomorph.XenomorphEntity;
import mod.azure.ovomorphosis.level.ResinWebRegistry;
import mod.azure.ovomorphosis.network.ForgeNetworkHandler;
import mod.azure.ovomorphosis.registry.BlockRegistry;
import mod.azure.ovomorphosis.registry.EntityRegistry;
import mod.azure.ovomorphosis.registry.ItemRegistry;

@Mod.EventBusSubscriber
@Mod(CommonMod.MOD_ID)
public final class ForgeMod {

    public static DeferredRegister<EntityType<?>> entityTypeDeferredRegister = DeferredRegister.create(
        ForgeRegistries.ENTITY_TYPES,
        CommonMod.MOD_ID
    );

    public static DeferredRegister<Block> blockDeferredRegister = DeferredRegister.create(
        ForgeRegistries.BLOCKS,
        CommonMod.MOD_ID
    );

    public static DeferredRegister<BlockEntityType<?>> blockEntityDeferredRegister = DeferredRegister.create(
        ForgeRegistries.BLOCK_ENTITY_TYPES,
        CommonMod.MOD_ID
    );

    public static DeferredRegister<Item> itemDeferredRegister = DeferredRegister.create(
        ForgeRegistries.ITEMS,
        CommonMod.MOD_ID
    );

    public static DeferredRegister<SoundEvent> soundEventDeferredRegister = DeferredRegister.create(
        ForgeRegistries.SOUND_EVENTS,
        CommonMod.MOD_ID
    );

    public ForgeMod(FMLJavaModLoadingContext loadingContext) {
        var modEventBus = loadingContext.getModEventBus();
        CommonMod.initRegistries();
        blockDeferredRegister.register(modEventBus);
        entityTypeDeferredRegister.register(modEventBus);
        blockEntityDeferredRegister.register(modEventBus);
        itemDeferredRegister.register(modEventBus);
        soundEventDeferredRegister.register(modEventBus);
        MinecraftForge.EVENT_BUS.addListener(
            (AddReloadListenerEvent event) -> event.addListener(new EntityHeadOffsetData.ReloadListener())
        );
        MinecraftForge.EVENT_BUS.addListener(
            (LevelEvent.Unload event) -> {
                if (event.getLevel() instanceof ServerLevel serverLevel) {
                    ResinWebRegistry.clearDimension(serverLevel.dimension());
                }
            }
        );
        modEventBus.addListener(this::createEntityAttributes);
        modEventBus.addListener(this::addSpawnPlacements);
        modEventBus.addListener(this::addCreativeTabs);
        ForgeNetworkHandler.registerMessages();
        ModStructureModifierSerializers.STRUCTURE_MODIFIER_SERIALIZERS.register(modEventBus);
    }

    public void createEntityAttributes(final EntityAttributeCreationEvent event) {
        event.put(EntityRegistry.OVOMORPH.get(), OvomorphEntity.createAttributes().build());
        event.put(EntityRegistry.FACEHUGGER.get(), FacehuggerEntity.createAttributes().build());
        event.put(EntityRegistry.CHESTBURSTER.get(), ChestbursterEntity.createAttributes().build());
        event.put(EntityRegistry.XENOMORPH.get(), XenomorphEntity.createAttributes().build());
        event.put(EntityRegistry.RUNNER.get(), RunnerEntity.createAttributes().build());
    }

    public void addCreativeTabs(final BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(ItemRegistry.OVOMORPH_SPAWN_EGG.get());
            event.accept(ItemRegistry.FACEHUGGER_SPAWN_EGG.get());
            event.accept(ItemRegistry.CHESTBURSTER_SPAWN_EGG.get());
            event.accept(ItemRegistry.XENOMORPH_SPAWN_EGG.get());
            // event.accept(ItemRegistry.RUNNER_SPAWN_EGG.get());
        }
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(BlockRegistry.RESIN_ITEM.get());
            event.accept(BlockRegistry.RESIN_WEB_ITEM.get());
            event.accept(BlockRegistry.RESIN_WEB_CROSS_ITEM.get());
        }
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(ItemRegistry.FLAMETHROWER.get());
            event.accept(ItemRegistry.SCANNER.get());
            event.accept(ItemRegistry.MOTION_TRACKER.get());
        }
    }

    public void addSpawnPlacements(SpawnPlacementRegisterEvent event) {
        event.register(
            EntityRegistry.OVOMORPH.get(),
            SpawnPlacements.Type.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            OvomorphEntity::canOvomorphSpawn,
            SpawnPlacementRegisterEvent.Operation.AND
        );
        event.register(
            EntityRegistry.FACEHUGGER.get(),
            SpawnPlacements.Type.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            ((entityType, world, reason, pos, random) -> world.getBiome(pos).is(BiomeTags.IS_OVERWORLD)),
            SpawnPlacementRegisterEvent.Operation.AND
        );
        event.register(
            EntityRegistry.CHESTBURSTER.get(),
            SpawnPlacements.Type.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            ((entityType, world, reason, pos, random) -> world.getBiome(pos).is(BiomeTags.IS_OVERWORLD)),
            SpawnPlacementRegisterEvent.Operation.AND
        );
        event.register(
            EntityRegistry.XENOMORPH.get(),
            SpawnPlacements.Type.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            ((entityType, world, reason, pos, random) -> world.getBiome(pos).is(BiomeTags.IS_OVERWORLD)),
            SpawnPlacementRegisterEvent.Operation.AND
        );
        event.register(
            EntityRegistry.RUNNER.get(),
            SpawnPlacements.Type.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            ((entityType, world, reason, pos, random) -> world.getBiome(pos).is(BiomeTags.IS_OVERWORLD)),
            SpawnPlacementRegisterEvent.Operation.AND
        );
    }
}
