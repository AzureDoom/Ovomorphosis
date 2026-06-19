package mod.azure.xenogenesis;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

import mod.azure.xenogenesis.client.facehugger.EntityHeadOffsetData;
import mod.azure.xenogenesis.entities.chestburster.ChestbursterEntity;
import mod.azure.xenogenesis.entities.facehugger.FacehuggerEntity;
import mod.azure.xenogenesis.entities.ovomorph.OvomorphEntity;
import mod.azure.xenogenesis.entities.queen.QueenEntity;
import mod.azure.xenogenesis.entities.xenomorph.XenomorphEntity;
import mod.azure.xenogenesis.registry.EntityRegistry;
import mod.azure.xenogenesis.registry.ItemRegistry;

@Mod(CommonMod.MOD_ID)
public final class NeoForgeMod {

    public static DeferredRegister<EntityType<?>> entityTypeDeferredRegister = DeferredRegister.create(
        BuiltInRegistries.ENTITY_TYPE,
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
        if (entityTypeDeferredRegister != null)
            entityTypeDeferredRegister.register(modEventBus);
        if (itemDeferredRegister != null)
            itemDeferredRegister.register(modEventBus);
        if (soundEventDeferredRegister != null)
            soundEventDeferredRegister.register(modEventBus);
        NeoForge.EVENT_BUS.addListener(
            (AddReloadListenerEvent event) -> event.addListener(new EntityHeadOffsetData.ReloadListener())
        );
        modEventBus.addListener(this::createEntityAttributes);
        modEventBus.addListener(this::addSpawnPlacements);
        ModEntitySpawn.SERIALIZER.register(modEventBus);
        modEventBus.addListener(this::addCreativeTabs);
    }

    public void createEntityAttributes(final EntityAttributeCreationEvent event) {
        event.put(EntityRegistry.OVOMORPH.get(), OvomorphEntity.createAttributes().build());
        event.put(EntityRegistry.FACEHUGGER.get(), FacehuggerEntity.createAttributes().build());
        event.put(EntityRegistry.CHESTBURSTER.get(), ChestbursterEntity.createAttributes().build());
        event.put(EntityRegistry.XENOMORPH.get(), XenomorphEntity.createAttributes().build());
        event.put(EntityRegistry.QUEEN.get(), QueenEntity.createAttributes().build());
    }

    public void addCreativeTabs(final BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(ItemRegistry.OVOMORPH_SPAWN_EGG.get());
            event.accept(ItemRegistry.FACEHUGGER_SPAWN_EGG.get());
            event.accept(ItemRegistry.CHESTBURSTER_SPAWN_EGG.get());
            event.accept(ItemRegistry.XENOMORPH_SPAWN_EGG.get());
            event.accept(ItemRegistry.QUEEN_SPAWN_EGG.get());
        }
    }

    public void addSpawnPlacements(RegisterSpawnPlacementsEvent event) {
        event.register(
            EntityRegistry.OVOMORPH.get(),
            SpawnPlacementTypes.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            ((entityType, world, reason, pos, random) -> world.getBiome(pos).is(BiomeTags.IS_OVERWORLD)),
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
        event.register(
            EntityRegistry.QUEEN.get(),
            SpawnPlacementTypes.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            ((entityType, world, reason, pos, random) -> world.getBiome(pos).is(BiomeTags.IS_OVERWORLD)),
            RegisterSpawnPlacementsEvent.Operation.AND
        );
    }

    record ModEntitySpawn(
        HolderSet<Biome> biomes,
        MobSpawnSettings.SpawnerData spawn
    ) implements BiomeModifier {

        public static DeferredRegister<MapCodec<? extends BiomeModifier>> SERIALIZER = DeferredRegister.create(
            NeoForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS,
            CommonMod.MOD_ID
        );

        static Supplier<MapCodec<ModEntitySpawn>> SPAWN_CODEC = SERIALIZER.register(
            "mobspawns",
            () -> RecordCodecBuilder.mapCodec(
                builder -> builder.group(
                    Biome.LIST_CODEC.fieldOf("biomes").forGetter(ModEntitySpawn::biomes),
                    MobSpawnSettings.SpawnerData.CODEC.fieldOf("spawn")
                        .forGetter(
                            ModEntitySpawn::spawn
                        )
                ).apply(builder, ModEntitySpawn::new)
            )
        );

        @Override
        public void modify(
            @NotNull Holder<Biome> biome,
            @NotNull Phase phase,
            ModifiableBiomeInfo.BiomeInfo.@NotNull Builder builder
        ) {
            if (phase == Phase.ADD && this.biomes.contains(biome)) {
                builder.getMobSpawnSettings().addSpawn(MobCategory.MONSTER, this.spawn);
            }
        }

        @Override
        public @NotNull MapCodec<? extends BiomeModifier> codec() {
            return SPAWN_CODEC.get();
        }
    }
}
