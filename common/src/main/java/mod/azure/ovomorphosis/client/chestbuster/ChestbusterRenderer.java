package mod.azure.ovomorphosis.client.chestbuster;

import mod.azure.azurelib.common.render.entity.AzEntityRenderer;
import mod.azure.azurelib.common.render.entity.AzEntityRendererConfig;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.client.layer.BloodLayer;
import mod.azure.ovomorphosis.entities.chestburster.ChestbursterEntity;

public class ChestbusterRenderer extends AzEntityRenderer<ChestbursterEntity> {

    private static final ResourceLocation MODEL = CommonMod.modResource("geo/entity/chestburster.geo.json");

    private static final ResourceLocation TEXTURE = CommonMod.modResource("textures/entity/chestburster.png");

    public ChestbusterRenderer(EntityRendererProvider.Context context) {
        super(
            AzEntityRendererConfig.<ChestbursterEntity>builder(MODEL, TEXTURE)
                .setRenderEntry(contextPipeline -> {
                    contextPipeline.animatable().updateAnimations();

                    return contextPipeline;
                })
                .setAnimatorProvider(ChestbursterAnimator::new)
                .addRenderLayer(new BloodLayer<>())
                .setDeathMaxRotation(0F)
                .setShadowRadius(0.3F)
                .build(),
            context
        );
    }
}
