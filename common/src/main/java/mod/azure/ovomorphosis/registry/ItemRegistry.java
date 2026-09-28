package mod.azure.ovomorphosis.registry;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;

import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import mod.azure.ovomorphosis.items.InfectionScannerItem;
import mod.azure.ovomorphosis.items.MagmaSprayerItem;
import mod.azure.ovomorphosis.items.MotionTrackerItem;
import mod.azure.ovomorphosis.services.XenoServices;

public class ItemRegistry {

    public static final Supplier<SpawnEggItem> OVOMORPH_SPAWN_EGG =
        XenoServices.COMMON_REGISTRY.registerSpawnEgg(
            "ovomorph_spawn_egg",
            EntityRegistry.OVOMORPH
        );

    public static final Supplier<SpawnEggItem> FACEHUGGER_SPAWN_EGG =
        XenoServices.COMMON_REGISTRY.registerSpawnEgg(
            "facehugger_spawn_egg",
            EntityRegistry.FACEHUGGER
        );

    public static final Supplier<SpawnEggItem> CHESTBURSTER_SPAWN_EGG =
        XenoServices.COMMON_REGISTRY.registerSpawnEgg(
            "chestburster_spawn_egg",
            EntityRegistry.CHESTBURSTER
        );

    public static final Supplier<SpawnEggItem> XENOMORPH_SPAWN_EGG =
        XenoServices.COMMON_REGISTRY.registerSpawnEgg(
            "xenomorph_spawn_egg",
            EntityRegistry.XENOMORPH
        );

    public static final Supplier<SpawnEggItem> RUNNER_SPAWN_EGG =
        XenoServices.COMMON_REGISTRY.registerSpawnEgg(
            "runner_spawn_egg",
            EntityRegistry.RUNNER
        );

    public static final Supplier<Item> FLAMETHROWER = registerItem(
        "magma_sprayer",
        MagmaSprayerItem::new,
        properties -> properties.durability(100).stacksTo(1)
    );

    public static final Supplier<Item> SCANNER = registerItem(
        "infection_scanner",
        InfectionScannerItem::new,
        properties -> properties.durability(32).stacksTo(1)
    );

    public static final Supplier<Item> MOTION_TRACKER = registerItem(
        "motion_tracker",
        MotionTrackerItem::new,
        properties -> properties.durability(64).stacksTo(1)
    );

    private ItemRegistry() {}

    public static <T extends Item> Supplier<T> registerItem(
        String itemName,
        Function<Item.Properties, T> factory,
        UnaryOperator<Item.Properties> properties
    ) {
        return XenoServices.COMMON_REGISTRY.registerItem(
            itemName,
            factory,
            properties
        );
    }

    public static void initialize() {}
}
