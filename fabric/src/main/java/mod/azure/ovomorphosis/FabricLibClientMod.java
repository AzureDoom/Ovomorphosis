package mod.azure.ovomorphosis;

import mod.azure.azurelib.fabric.platform.FabricAzureLibNetwork;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.entity.EntityRenderers;

import mod.azure.ovomorphosis.client.AcidEntityRender;
import mod.azure.ovomorphosis.client.chestbuster.ChestbusterRenderer;
import mod.azure.ovomorphosis.client.facehugger.FacehuggerRenderer;
import mod.azure.ovomorphosis.client.ovomorph.OvomorphRenderer;
import mod.azure.ovomorphosis.client.runner.RunnerRenderer;
import mod.azure.ovomorphosis.client.xenomorph.XenomorphRenderer;
import mod.azure.ovomorphosis.network.EggmorphProgressPacket;
import mod.azure.ovomorphosis.registry.EntityRegistry;

public class FabricLibClientMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        EntityRenderers.register(EntityRegistry.OVOMORPH.get(), OvomorphRenderer::new);
        EntityRenderers.register(EntityRegistry.FACEHUGGER.get(), FacehuggerRenderer::new);
        EntityRenderers.register(EntityRegistry.CHESTBURSTER.get(), ChestbusterRenderer::new);
        EntityRenderers.register(EntityRegistry.XENOMORPH.get(), XenomorphRenderer::new);
        EntityRenderers.register(EntityRegistry.RUNNER.get(), RunnerRenderer::new);
        EntityRenderers.register(EntityRegistry.ACID.get(), AcidEntityRender::new);
        FabricAzureLibNetwork.registerPacket(
            EggmorphProgressPacket.TYPE,
            EggmorphProgressPacket.CODEC
        );
    }
}
