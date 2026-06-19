package mod.azure.xenogenesis.client.queen;

import mod.azure.azurelib.common.render.entity.AzEntityRenderer;
import mod.azure.azurelib.common.render.entity.AzEntityRendererConfig;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

import mod.azure.xenogenesis.CommonMod;
import mod.azure.xenogenesis.entities.queen.QueenEntity;

public class QueenRenderer extends AzEntityRenderer<QueenEntity> {

    private static final ResourceLocation MODEL = CommonMod.modResource("geo/entity/queen.geo.json");

    private static final ResourceLocation TEXTURE = CommonMod.modResource("textures/entity/queen.png");

    public QueenRenderer(EntityRendererProvider.Context context) {
        super(
            AzEntityRendererConfig.<QueenEntity>builder(MODEL, TEXTURE)
                .setRenderEntry(contextPipeline -> {
                    contextPipeline.animatable().updateAnimations();

                    return contextPipeline;
                })
                .setAnimatorProvider(QueenAnimator::new)
                .setDeathMaxRotation(0F)
                .setShadowRadius(0.3F)
                .build(),
            context
        );
    }
}
