package mod.azure.xenogenesis;

import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resources.ResourceLocation;

import mod.azure.xenogenesis.client.facehugger.EntityHeadOffsetData;

public class FabricHeadOffsetReloadListener extends EntityHeadOffsetData.ReloadListener implements IdentifiableResourceReloadListener {

    @Override
    public ResourceLocation getFabricId() {
        return CommonMod.modResource("xenogenesis_head_offsets");
    }
}
