package mod.azure.ovomorphosis;

import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

import mod.azure.ovomorphosis.client.AcidEntityRender;
import mod.azure.ovomorphosis.client.chestbuster.ChestbusterRenderer;
import mod.azure.ovomorphosis.client.facehugger.FacehuggerRenderer;
import mod.azure.ovomorphosis.client.ovomorph.OvomorphRenderer;
import mod.azure.ovomorphosis.client.runner.RunnerRenderer;
import mod.azure.ovomorphosis.client.xenomorph.XenomorphRenderer;
import mod.azure.ovomorphosis.registry.BlockRegistry;
import mod.azure.ovomorphosis.registry.EntityRegistry;

@EventBusSubscriber(modid = CommonMod.MOD_ID, value = Dist.CLIENT)
public class NeoForgeClientMod {

    @SuppressWarnings("deprecation")
    @SubscribeEvent
    public static void onClientSetup(final FMLClientSetupEvent event) {
        ItemBlockRenderTypes.setRenderLayer(BlockRegistry.RESIN_WEB.get(), RenderType.cutout());
        ItemBlockRenderTypes.setRenderLayer(BlockRegistry.RESIN_WEB_CROSS.get(), RenderType.translucent());
    }

    @SubscribeEvent
    public static void registerRenderers(final EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(EntityRegistry.OVOMORPH.get(), OvomorphRenderer::new);
        event.registerEntityRenderer(EntityRegistry.FACEHUGGER.get(), FacehuggerRenderer::new);
        event.registerEntityRenderer(EntityRegistry.CHESTBURSTER.get(), ChestbusterRenderer::new);
        event.registerEntityRenderer(EntityRegistry.XENOMORPH.get(), XenomorphRenderer::new);
        event.registerEntityRenderer(EntityRegistry.RUNNER.get(), RunnerRenderer::new);
        event.registerEntityRenderer(EntityRegistry.ACID.get(), AcidEntityRender::new);
    }
}
