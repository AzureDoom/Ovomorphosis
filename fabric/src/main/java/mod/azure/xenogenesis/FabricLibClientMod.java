package mod.azure.xenogenesis;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

import mod.azure.xenogenesis.client.AcidEntityRender;
import mod.azure.xenogenesis.client.chestbuster.ChestbusterRenderer;
import mod.azure.xenogenesis.client.facehugger.FacehuggerRenderer;
import mod.azure.xenogenesis.client.ovomorph.OvomorphRenderer;
import mod.azure.xenogenesis.client.queen.QueenRenderer;
import mod.azure.xenogenesis.client.xenomorph.XenomorphRenderer;
import mod.azure.xenogenesis.registry.EntityRegistry;

public class FabricLibClientMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(EntityRegistry.OVOMORPH.get(), OvomorphRenderer::new);
        EntityRendererRegistry.register(EntityRegistry.FACEHUGGER.get(), FacehuggerRenderer::new);
        EntityRendererRegistry.register(EntityRegistry.CHESTBURSTER.get(), ChestbusterRenderer::new);
        EntityRendererRegistry.register(EntityRegistry.XENOMORPH.get(), XenomorphRenderer::new);
        EntityRendererRegistry.register(EntityRegistry.QUEEN.get(), QueenRenderer::new);
        EntityRendererRegistry.register(EntityRegistry.ACID.get(), AcidEntityRender::new);
    }
}
