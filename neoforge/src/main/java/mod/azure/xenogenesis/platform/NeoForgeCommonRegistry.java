package mod.azure.xenogenesis.platform;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;

import java.util.function.Supplier;

import mod.azure.xenogenesis.NeoForgeMod;
import mod.azure.xenogenesis.services.CommonRegistry;

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
    public <E extends Mob> Supplier<SpawnEggItem> makeSpawnEggFor(
        Supplier<EntityType<E>> entityType,
        int primaryEggColour,
        int secondaryEggColour,
        Item.Properties itemProperties
    ) {
        return () -> new DeferredSpawnEggItem(entityType, primaryEggColour, secondaryEggColour, itemProperties);
    }
}
