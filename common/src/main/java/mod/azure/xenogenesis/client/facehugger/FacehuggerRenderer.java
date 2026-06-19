package mod.azure.xenogenesis.client.facehugger;

import mod.azure.azurelib.common.render.entity.AzEntityRenderer;
import mod.azure.azurelib.common.render.entity.AzEntityRendererConfig;
import mod.azure.azurelib.common.render.entity.AzEntityRendererPipeline;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

import mod.azure.xenogenesis.CommonMod;
import mod.azure.xenogenesis.entities.facehugger.FacehuggerEntity;

public class FacehuggerRenderer extends AzEntityRenderer<FacehuggerEntity> {

    private static final ResourceLocation MODEL = CommonMod.modResource("geo/entity/facehugger.geo.json");

    private static final ResourceLocation TEXTURE = CommonMod.modResource("textures/entity/facehugger.png");

    public FacehuggerRenderer(EntityRendererProvider.Context context) {
        super(
            AzEntityRendererConfig.<FacehuggerEntity>builder(MODEL, TEXTURE)
                .setRenderEntry(contextPipeline -> {
                    contextPipeline.animatable().updateAnimations();

                    return contextPipeline;
                })
                .setModelRenderer(
                    (
                        pipelineContext,
                        layerRenderer
                    ) -> new FacehuggerModelRenderer(
                        (AzEntityRendererPipeline<FacehuggerEntity>) pipelineContext,
                        layerRenderer
                    )
                )
                .setAnimatorProvider(FacehuggerAnimator::new)
                .setDeathMaxRotation(0F)
                .setShadowRadius(0.25F)
                .build(),
            context
        );
    }
}
