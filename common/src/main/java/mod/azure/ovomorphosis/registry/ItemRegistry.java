package mod.azure.ovomorphosis.registry;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;

import java.util.function.Supplier;

import mod.azure.ovomorphosis.items.InfectionScannerItem;
import mod.azure.ovomorphosis.items.MagmaSprayerItem;
import mod.azure.ovomorphosis.items.MotionTrackerItem;
import mod.azure.ovomorphosis.services.XenoServices;

public class ItemRegistry {

    public static final Supplier<SpawnEggItem> OVOMORPH_SPAWN_EGG = registerItem(
        "ovomorph_spawn_egg",
        XenoServices.COMMON_REGISTRY.makeSpawnEggFor(
            EntityRegistry.OVOMORPH,
            0x746e4c,
            0x0f0f0d,
            new Item.Properties()
        )
    );

    public static final Supplier<SpawnEggItem> FACEHUGGER_SPAWN_EGG = registerItem(
        "facehugger_spawn_egg",
        XenoServices.COMMON_REGISTRY.makeSpawnEggFor(
            EntityRegistry.FACEHUGGER,
            0xbc9667,
            0x765833,
            new Item.Properties()
        )
    );

    public static final Supplier<SpawnEggItem> CHESTBURSTER_SPAWN_EGG = registerItem(
        "chestburster_spawn_egg",
        XenoServices.COMMON_REGISTRY.makeSpawnEggFor(
            EntityRegistry.CHESTBURSTER,
            0x666666,
            0x987242,
            new Item.Properties()
        )
    );

    public static final Supplier<SpawnEggItem> XENOMORPH_SPAWN_EGG = registerItem(
        "xenomorph_spawn_egg",
        XenoServices.COMMON_REGISTRY.makeSpawnEggFor(
            EntityRegistry.XENOMORPH,
            0x131416,
            0x362b1e,
            new Item.Properties()
        )
    );

    public static final Supplier<SpawnEggItem> RUNNER_SPAWN_EGG = registerItem(
        "runner_spawn_egg",
        XenoServices.COMMON_REGISTRY.makeSpawnEggFor(
            EntityRegistry.RUNNER,
            0x131416,
            0x362b1e,
            new Item.Properties()
        )
    );

    public static final Supplier<Item> FLAMETHROWER = registerItem("magma_sprayer", MagmaSprayerItem::new);

    public static final Supplier<Item> SCANNER = registerItem("infection_scanner", InfectionScannerItem::new);

    public static final Supplier<Item> MOTION_TRACKER = registerItem("motion_tracker", MotionTrackerItem::new);

    private ItemRegistry() {}

    public static <T extends Item> Supplier<T> registerItem(String itemName, Supplier<T> item) {
        return XenoServices.COMMON_REGISTRY.register(BuiltInRegistries.ITEM, itemName, item);
    }

    public static void initialize() {}
}
