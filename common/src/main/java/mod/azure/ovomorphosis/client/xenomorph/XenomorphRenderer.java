package mod.azure.ovomorphosis.client.xenomorph;

import mod.azure.azurelib.render.entity.AzEntityRenderer;
import mod.azure.azurelib.render.entity.AzEntityRendererConfig;
import mod.azure.azurelib.render.lod.AzLodConfig;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.client.XenoModelRenderer;
import mod.azure.ovomorphosis.client.layer.TextureGrowthOverlayLayer;
import mod.azure.ovomorphosis.entities.xenomorph.XenomorphEntity;

public class XenomorphRenderer extends AzEntityRenderer<XenomorphEntity> {

    private static final ResourceLocation MODEL = CommonMod.modResource("geo/entity/xenomorph.geo.json");

    private static final ResourceLocation TEXTURE = CommonMod.modResource("textures/entity/xenomorph.png");

    public XenomorphRenderer(EntityRendererProvider.Context context) {
        super(
            AzEntityRendererConfig.<XenomorphEntity>builder(MODEL, TEXTURE)
                .setRenderEntry(contextPipeline -> {
                    contextPipeline.animatable().updateAnimations();

                    return contextPipeline;
                })
                .setAnimatorProvider(XenomorphAnimator::new)
                .setModelRenderer(XenoModelRenderer::new)
                .addRenderLayer(new TextureGrowthOverlayLayer<>())
                .setDeathMaxRotation(0F)
                .setShadowRadius(0.75F)
                .setScale(XenomorphEntity::getGrowthScale)
                .withLodConfig(
                    AzLodConfig.builder()
                        .boneLod(1028, 3)
                        .animLod(1028, 2)
                        .build()
                )
                .build(),
            context
        );
    }
}
