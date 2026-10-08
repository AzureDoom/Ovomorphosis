package mod.azure.ovomorphosis.client.facehugger;

import mod.azure.azurelib.render.entity.AzEntityRenderer;
import mod.azure.azurelib.render.entity.AzEntityRendererConfig;
import mod.azure.azurelib.render.entity.AzEntityRendererPipeline;
import mod.azure.azurelib.render.lod.AzLodConfig;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.Identifier;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.entities.facehugger.FacehuggerEntity;

public class FacehuggerRenderer extends AzEntityRenderer<FacehuggerEntity> {

    private static final Identifier MODEL = CommonMod.modResource("geo/entity/facehugger.geo.json");

    private static final Identifier TEXTURE = CommonMod.modResource("textures/entity/facehugger.png");

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
                .withLodConfig(
                    AzLodConfig.builder()
                        .boneLod(32, 3)
                        .animLod(48, 2)
                        .build()
                )
                .build(),
            context
        );
    }
}
