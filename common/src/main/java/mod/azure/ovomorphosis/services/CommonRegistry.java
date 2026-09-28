package mod.azure.ovomorphosis.services;

import net.minecraft.core.Registry;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public interface CommonRegistry {

    <T> Supplier<T> register(
        Registry<? super T> registry,
        String registryName,
        Supplier<? extends T> supplier
    );

    <T extends Block> Supplier<T> registerBlock(
        String registryName,
        Function<BlockBehaviour.Properties, T> factory,
        BlockBehaviour.Properties properties
    );

    <T extends Item> Supplier<T> registerItem(
        String registryName,
        Function<Item.Properties, T> factory,
        UnaryOperator<Item.Properties> properties
    );

    <E extends Mob> Supplier<SpawnEggItem> registerSpawnEgg(
        String registryName,
        Supplier<EntityType<E>> entityType
    );

    boolean isDevelopmentEnvironment();

    boolean isModLoaded(String modId);
}
