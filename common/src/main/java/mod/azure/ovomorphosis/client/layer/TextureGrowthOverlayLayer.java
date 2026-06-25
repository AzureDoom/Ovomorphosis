package mod.azure.ovomorphosis.client.layer;

import mod.azure.azurelib.common.model.AzBone;
import mod.azure.azurelib.common.render.AzRendererPipeline;
import mod.azure.azurelib.common.render.AzRendererPipelineContext;
import mod.azure.azurelib.common.render.layer.AzRenderLayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.entities.AbstractAlienEntity;
import mod.azure.ovomorphosis.entities.runner.RunnerEntity;
import mod.azure.ovomorphosis.util.Growable;

public class TextureGrowthOverlayLayer<T extends AbstractAlienEntity & Growable> implements AzRenderLayer<UUID, T> {

    @Override
    public void preRender(AzRendererPipelineContext<UUID, T> context) {}

    @Override
    public void render(AzRendererPipelineContext<UUID, T> context) {
        T animatable = context.animatable();
        AzRendererPipeline<UUID, T> renderPipeline = context.rendererPipeline();
        var rendertype = RenderType.entityTranslucentCull(getEntityTexture(animatable));

        if (animatable.getGrowth() < animatable.getMaxGrowth() && animatable.isAlive()) {
            context.setRenderType(rendertype);
            context.setVertexConsumer(context.multiBufferSource().getBuffer(rendertype));

            var progress = (animatable.getMaxGrowth() - animatable.getGrowth()) / animatable.getMaxGrowth();
            var alpha = (int) (progress * 0xFF) << 24;
            var color = (context.renderColor() & 0xFFFFFF) | alpha;

            context.setRenderColor(color);
            renderPipeline.reRender(context);
        }
    }

    @Override
    public void renderForBone(AzRendererPipelineContext<UUID, T> context, AzBone bone) {}

    private ResourceLocation getEntityTexture(AbstractAlienEntity entity) {
        var name = "xenomorph";
        if (entity instanceof RunnerEntity)
            name = "runner";

        return CommonMod.modResource("textures/entity/" + name + "_youth.png");
    }
}
