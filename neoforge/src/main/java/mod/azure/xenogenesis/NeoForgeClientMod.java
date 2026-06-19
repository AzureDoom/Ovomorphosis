package mod.azure.xenogenesis;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

import mod.azure.xenogenesis.client.AcidEntityRender;
import mod.azure.xenogenesis.client.chestbuster.ChestbusterRenderer;
import mod.azure.xenogenesis.client.facehugger.FacehuggerRenderer;
import mod.azure.xenogenesis.client.ovomorph.OvomorphRenderer;
import mod.azure.xenogenesis.client.queen.QueenRenderer;
import mod.azure.xenogenesis.client.xenomorph.XenomorphRenderer;
import mod.azure.xenogenesis.registry.EntityRegistry;

@EventBusSubscriber(modid = CommonMod.MOD_ID, value = Dist.CLIENT)
public class NeoForgeClientMod {

    @SubscribeEvent
    public static void registerRenderers(final EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(EntityRegistry.OVOMORPH.get(), OvomorphRenderer::new);
        event.registerEntityRenderer(EntityRegistry.FACEHUGGER.get(), FacehuggerRenderer::new);
        event.registerEntityRenderer(EntityRegistry.CHESTBURSTER.get(), ChestbusterRenderer::new);
        event.registerEntityRenderer(EntityRegistry.XENOMORPH.get(), XenomorphRenderer::new);
        event.registerEntityRenderer(EntityRegistry.QUEEN.get(), QueenRenderer::new);
        event.registerEntityRenderer(EntityRegistry.ACID.get(), AcidEntityRender::new);
    }
}
