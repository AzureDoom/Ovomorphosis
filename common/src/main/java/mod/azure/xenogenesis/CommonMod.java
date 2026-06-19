package mod.azure.xenogenesis;

import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import mod.azure.xenogenesis.registry.EntityRegistry;
import mod.azure.xenogenesis.registry.ItemRegistry;
import mod.azure.xenogenesis.registry.SoundRegistry;

public class CommonMod {

    public static final String MOD_ID = "xenogenesis";

    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public static ResourceLocation modResource(String name) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, name);
    }

    public static void initRegistries() {
        EntityRegistry.initialize();
        SoundRegistry.initialize();
        ItemRegistry.initialize();
    }
}
