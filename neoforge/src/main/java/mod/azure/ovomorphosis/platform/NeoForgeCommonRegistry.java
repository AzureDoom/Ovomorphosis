package mod.azure.ovomorphosis.platform;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.event.EventHooks;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import mod.azure.ovomorphosis.NeoForgeMod;
import mod.azure.ovomorphosis.services.CommonRegistry;

public class NeoForgeCommonRegistry implements CommonRegistry {

    @SuppressWarnings("unchecked")
    @Override
    public <T> Supplier<T> register(Registry<? super T> registry, String registryName, Supplier<? extends T> supplier) {
        if (registry == BuiltInRegistries.BLOCK) {
            return (Supplier<T>) NeoForgeMod.blockDeferredRegister.register(registryName, (Supplier<Block>) supplier);
        } else if (registry == BuiltInRegistries.ITEM) {
            return (Supplier<T>) NeoForgeMod.itemDeferredRegister.register(registryName, (Supplier<Item>) supplier);
        } else if (registry == BuiltInRegistries.ENTITY_TYPE) {
            return (Supplier<T>) NeoForgeMod.entityTypeDeferredRegister.register(
                registryName,
                (Supplier<EntityType<?>>) supplier
            );
        } else if (registry == BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            return (Supplier<T>) NeoForgeMod.blockEntityDeferredRegister.register(
                registryName,
                (Supplier<BlockEntityType<?>>) supplier
            );
        } else if (registry == BuiltInRegistries.SOUND_EVENT) {
            return (Supplier<T>) NeoForgeMod.soundEventDeferredRegister.register(
                registryName,
                (Supplier<SoundEvent>) supplier
            );
        }

        throw new IllegalArgumentException(
            "Received registration attempt for an unhandled registry. Registry: " + registry
        );
    }

    @Override
    public <T extends Block> Supplier<T> registerBlock(
        String registryName,
        Function<BlockBehaviour.Properties, T> factory,
        BlockBehaviour.Properties properties
    ) {
        return NeoForgeMod.blockDeferredRegister.register(
            registryName,
            name -> factory.apply(
                properties.setId(
                    ResourceKey.create(
                        Registries.BLOCK,
                        name
                    )
                )
            )
        );
    }

    @Override
    public <T extends Item> Supplier<T> registerItem(
        String registryName,
        Function<Item.Properties, T> factory,
        UnaryOperator<Item.Properties> properties
    ) {
        return NeoForgeMod.itemDeferredRegister.registerItem(
            registryName,
            factory,
            properties
        );
    }

    @Override
    public <E extends Mob> Supplier<SpawnEggItem> registerSpawnEgg(
        String registryName,
        Supplier<EntityType<E>> entityType
    ) {
        return NeoForgeMod.itemDeferredRegister.registerItem(
            registryName,
            properties -> new SpawnEggItem(
                properties.spawnEgg(entityType.get())
            )
        );
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.getCurrent().isProduction();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean canEntityGrief(ServerLevel level, @Nullable Entity entity) {
        return EventHooks.canEntityGrief(level, entity);
    }
}
