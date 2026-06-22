package mod.azure.ovomorphosis;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredRegister;

import mod.azure.ovomorphosis.client.facehugger.EntityHeadOffsetData;
import mod.azure.ovomorphosis.entities.chestburster.ChestbursterEntity;
import mod.azure.ovomorphosis.entities.facehugger.FacehuggerEntity;
import mod.azure.ovomorphosis.entities.ovomorph.OvomorphEntity;
import mod.azure.ovomorphosis.entities.xenomorph.XenomorphEntity;
import mod.azure.ovomorphosis.network.EggmorphProgressPacket;
import mod.azure.ovomorphosis.registry.BlockRegistry;
import mod.azure.ovomorphosis.registry.EntityRegistry;
import mod.azure.ovomorphosis.registry.ItemRegistry;

@Mod(CommonMod.MOD_ID)
public final class NeoForgeMod {

    public static DeferredRegister<EntityType<?>> entityTypeDeferredRegister = DeferredRegister.create(
        BuiltInRegistries.ENTITY_TYPE,
        CommonMod.MOD_ID
    );

    public static DeferredRegister<Block> blockDeferredRegister = DeferredRegister.create(
        BuiltInRegistries.BLOCK,
        CommonMod.MOD_ID
    );

    public static DeferredRegister<BlockEntityType<?>> blockEntityDeferredRegister = DeferredRegister.create(
        BuiltInRegistries.BLOCK_ENTITY_TYPE,
        CommonMod.MOD_ID
    );

    public static DeferredRegister<Item> itemDeferredRegister = DeferredRegister.create(
        BuiltInRegistries.ITEM,
        CommonMod.MOD_ID
    );

    public static DeferredRegister<SoundEvent> soundEventDeferredRegister = DeferredRegister.create(
        BuiltInRegistries.SOUND_EVENT,
        CommonMod.MOD_ID
    );

    public NeoForgeMod(IEventBus modEventBus) {
        CommonMod.initRegistries();
        blockDeferredRegister.register(modEventBus);
        entityTypeDeferredRegister.register(modEventBus);
        blockEntityDeferredRegister.register(modEventBus);
        itemDeferredRegister.register(modEventBus);
        soundEventDeferredRegister.register(modEventBus);
        NeoForge.EVENT_BUS.addListener(
            (AddReloadListenerEvent event) -> event.addListener(new EntityHeadOffsetData.ReloadListener())
        );
        modEventBus.addListener(this::createEntityAttributes);
        modEventBus.addListener(this::addSpawnPlacements);
        modEventBus.addListener(this::addCreativeTabs);
        modEventBus.addListener(this::registerMessages);
    }

    public void registerMessages(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(CommonMod.MOD_ID);

        registrar.playToClient(
            EggmorphProgressPacket.TYPE,
            EggmorphProgressPacket.CODEC,
            (msg, ctx) -> msg.handle()
        );
    }

    public void createEntityAttributes(final EntityAttributeCreationEvent event) {
        event.put(EntityRegistry.OVOMORPH.get(), OvomorphEntity.createAttributes().build());
        event.put(EntityRegistry.FACEHUGGER.get(), FacehuggerEntity.createAttributes().build());
        event.put(EntityRegistry.CHESTBURSTER.get(), ChestbursterEntity.createAttributes().build());
        event.put(EntityRegistry.XENOMORPH.get(), XenomorphEntity.createAttributes().build());
    }

    public void addCreativeTabs(final BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(ItemRegistry.OVOMORPH_SPAWN_EGG.get());
            event.accept(ItemRegistry.FACEHUGGER_SPAWN_EGG.get());
            event.accept(ItemRegistry.CHESTBURSTER_SPAWN_EGG.get());
            event.accept(ItemRegistry.XENOMORPH_SPAWN_EGG.get());
        }
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(BlockRegistry.RESIN_ITEM.get());
            event.accept(BlockRegistry.RESIN_BLOCK_ITEM.get());
            event.accept(BlockRegistry.RESIN_WEB_ITEM.get());
            event.accept(BlockRegistry.RESIN_WEB_CROSS_ITEM.get());
        }
    }

    public void addSpawnPlacements(RegisterSpawnPlacementsEvent event) {
        event.register(
            EntityRegistry.OVOMORPH.get(),
            SpawnPlacementTypes.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            OvomorphEntity::canOvomorphSpawn,
            RegisterSpawnPlacementsEvent.Operation.AND
        );
        event.register(
            EntityRegistry.FACEHUGGER.get(),
            SpawnPlacementTypes.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            ((entityType, world, reason, pos, random) -> world.getBiome(pos).is(BiomeTags.IS_OVERWORLD)),
            RegisterSpawnPlacementsEvent.Operation.AND
        );
        event.register(
            EntityRegistry.CHESTBURSTER.get(),
            SpawnPlacementTypes.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            ((entityType, world, reason, pos, random) -> world.getBiome(pos).is(BiomeTags.IS_OVERWORLD)),
            RegisterSpawnPlacementsEvent.Operation.AND
        );
        event.register(
            EntityRegistry.XENOMORPH.get(),
            SpawnPlacementTypes.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            ((entityType, world, reason, pos, random) -> world.getBiome(pos).is(BiomeTags.IS_OVERWORLD)),
            RegisterSpawnPlacementsEvent.Operation.AND
        );
    }
}
