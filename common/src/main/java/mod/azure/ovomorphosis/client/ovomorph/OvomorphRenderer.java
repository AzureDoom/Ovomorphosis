package mod.azure.ovomorphosis.client.ovomorph;

import mod.azure.azurelib.common.render.entity.AzEntityRenderer;
import mod.azure.azurelib.common.render.entity.AzEntityRendererConfig;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.entities.ovomorph.OvomorphEntity;

public class OvomorphRenderer extends AzEntityRenderer<OvomorphEntity> {

    private static final ResourceLocation MODEL = CommonMod.modResource("geo/entity/ovomorph.geo.json");

    private static final ResourceLocation TEXTURE = CommonMod.modResource("textures/entity/ovomorph.png");

    private static final RenderType EGG_RENDER_TYPE = RenderType.entityTranslucent(TEXTURE);

    public OvomorphRenderer(EntityRendererProvider.Context context) {
        super(
            AzEntityRendererConfig.<OvomorphEntity>builder(MODEL, TEXTURE)
                .setRenderEntry(contextPipeline -> {
                    contextPipeline.animatable().updateAnimations();

                    return contextPipeline;
                })
                .setRenderType(
                    (nullEntity, animatable) -> EGG_RENDER_TYPE
                )
                .setAnimatorProvider(OvomorphAnimator::new)
                .setDeathMaxRotation(0F)
                .setShadowRadius(0.75F)
                .build(),
            context
        );
    }
}
