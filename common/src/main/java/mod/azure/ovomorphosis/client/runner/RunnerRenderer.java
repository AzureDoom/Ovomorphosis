package mod.azure.ovomorphosis.client.runner;

import mod.azure.azurelib.common.render.entity.AzEntityRenderer;
import mod.azure.azurelib.common.render.entity.AzEntityRendererConfig;
import mod.azure.azurelib.common.render.lod.AzLodConfig;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.client.layer.BloodLayer;
import mod.azure.ovomorphosis.entities.runner.RunnerEntity;

public class RunnerRenderer extends AzEntityRenderer<RunnerEntity> {

    private static final ResourceLocation MODEL = CommonMod.modResource("geo/entity/runner.geo.json");

    private static final ResourceLocation TEXTURE = CommonMod.modResource("textures/entity/runner.png");

    public RunnerRenderer(EntityRendererProvider.Context context) {
        super(
            AzEntityRendererConfig.<RunnerEntity>builder(MODEL, TEXTURE)
                .setRenderEntry(contextPipeline -> {
                    contextPipeline.animatable().updateAnimations();

                    return contextPipeline;
                })
                .setAnimatorProvider(RunnerAnimator::new)
                .addRenderLayer(new BloodLayer<>())
                .setDeathMaxRotation(0F)
                .setShadowRadius(0.75F)
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
