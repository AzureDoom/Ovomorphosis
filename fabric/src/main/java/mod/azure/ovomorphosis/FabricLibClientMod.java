package mod.azure.ovomorphosis;

import mod.azure.azurelib.fabric.platform.FabricAzureLibNetwork;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.RenderType;

import mod.azure.ovomorphosis.client.AcidEntityRender;
import mod.azure.ovomorphosis.client.chestbuster.ChestbusterRenderer;
import mod.azure.ovomorphosis.client.facehugger.FacehuggerRenderer;
import mod.azure.ovomorphosis.client.ovomorph.OvomorphRenderer;
import mod.azure.ovomorphosis.client.runner.RunnerRenderer;
import mod.azure.ovomorphosis.client.xenomorph.XenomorphRenderer;
import mod.azure.ovomorphosis.network.EggmorphProgressPacket;
import mod.azure.ovomorphosis.registry.BlockRegistry;
import mod.azure.ovomorphosis.registry.EntityRegistry;

public class FabricLibClientMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        BlockRenderLayerMap.INSTANCE.putBlock(BlockRegistry.RESIN_WEB.get(), RenderType.cutout());
        BlockRenderLayerMap.INSTANCE.putBlock(BlockRegistry.RESIN_WEB_CROSS.get(), RenderType.translucent());
        EntityRendererRegistry.register(EntityRegistry.OVOMORPH.get(), OvomorphRenderer::new);
        EntityRendererRegistry.register(EntityRegistry.FACEHUGGER.get(), FacehuggerRenderer::new);
        EntityRendererRegistry.register(EntityRegistry.CHESTBURSTER.get(), ChestbusterRenderer::new);
        EntityRendererRegistry.register(EntityRegistry.XENOMORPH.get(), XenomorphRenderer::new);
        EntityRendererRegistry.register(EntityRegistry.RUNNER.get(), RunnerRenderer::new);
        EntityRendererRegistry.register(EntityRegistry.ACID.get(), AcidEntityRender::new);
        FabricAzureLibNetwork.registerPacket(
            EggmorphProgressPacket.TYPE,
            EggmorphProgressPacket.CODEC
        );
    }
}
