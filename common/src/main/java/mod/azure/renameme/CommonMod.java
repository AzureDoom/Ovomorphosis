package mod.azure.renameme;

import net.minecraft.resources.ResourceLocation;

public class CommonMod {
    public static final String MOD_ID = "renameme";

    public static final ResourceLocation modResource(String name) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, name);
    }
}
