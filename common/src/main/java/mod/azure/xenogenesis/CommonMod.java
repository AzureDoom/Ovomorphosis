package mod.azure.xenogenesis;

import mod.azure.azurelib.AzureLibMod;
import mod.azure.azurelib.common.config.format.ConfigFormats;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import mod.azure.xenogenesis.config.XenogenesisConfig;
import mod.azure.xenogenesis.registry.BlockRegistry;
import mod.azure.xenogenesis.registry.EntityRegistry;
import mod.azure.xenogenesis.registry.ItemRegistry;
import mod.azure.xenogenesis.registry.SoundRegistry;

public class CommonMod {

    public static final String MOD_ID = "xenogenesis";

    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public static XenogenesisConfig config;

    public static ResourceLocation modResource(String name) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, name);
    }

    public static void initRegistries() {
        config = AzureLibMod.registerConfig(XenogenesisConfig.class, ConfigFormats.json()).getConfigInstance();
        EntityRegistry.initialize();
        SoundRegistry.initialize();
        BlockRegistry.initialize();
        ItemRegistry.initialize();
    }

    public static XenogenesisConfig getConfig() {
        return config;
    }
}
