package mod.azure.ovomorphosis;

import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resources.ResourceLocation;

import mod.azure.ovomorphosis.client.facehugger.EntityHeadData;

public class FabricHeadDataListener extends EntityHeadData.ReloadListener implements IdentifiableResourceReloadListener {

    @Override
    public ResourceLocation getFabricId() {
        return CommonMod.modResource("ovomorphosis_head_data");
    }
}
