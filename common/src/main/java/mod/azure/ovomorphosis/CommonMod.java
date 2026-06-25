package mod.azure.ovomorphosis;

import mod.azure.azurelib.AzureLibMod;
import mod.azure.azurelib.config.format.ConfigFormats;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import mod.azure.ovomorphosis.config.OvomorphosisConfig;
import mod.azure.ovomorphosis.registry.*;

public class CommonMod {

    public static final String MOD_ID = "ovomorphosis";

    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public static OvomorphosisConfig config;

    public static ResourceLocation modResource(String name) {
        return new ResourceLocation(MOD_ID, name);
    }

    public static void initRegistries() {
        config = AzureLibMod.registerConfig(OvomorphosisConfig.class, ConfigFormats.json()).getConfigInstance();
        EntityRegistry.initialize();
        BlockEntityRegistry.initialize();
        SoundRegistry.initialize();
        BlockRegistry.initialize();
        ItemRegistry.initialize();
    }

    public static OvomorphosisConfig getConfig() {
        return config;
    }
}
