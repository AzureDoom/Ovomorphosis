package mod.azure.ovomorphosis.client.layer;

import mod.azure.azurelib.model.AzBone;
import mod.azure.azurelib.render.AzRendererPipeline;
import mod.azure.azurelib.render.AzRendererPipelineContext;
import mod.azure.azurelib.render.layer.AzRenderLayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import java.util.UUID;

import mod.azure.ovomorphosis.entities.AbstractAlienEntity;
import mod.azure.ovomorphosis.util.Growable;

public class BloodLayer<T extends AbstractAlienEntity & Growable> implements AzRenderLayer<UUID, T> {

    private static final Identifier textureLocation =
        Identifier.withDefaultNamespace("textures/block/crimson_nylium.png");

    @Override
    public void preRender(AzRendererPipelineContext<UUID, T> context) {}

    @Override
    public void render(AzRendererPipelineContext<UUID, T> context) {
        T animatable = context.animatable();

        AzRendererPipeline<UUID, T> renderPipeline = context.rendererPipeline();
        var rendertype = RenderTypes.entityTranslucentCull(textureLocation);
        var maxGrowth = animatable.getMaxGrowth() / 2;
        if (animatable.getGrowth() < maxGrowth && animatable.isAlive()) {
            context.setRenderType(rendertype);
            context.setVertexConsumer(context.multiBufferSource().getBuffer(rendertype));
            var progress = (maxGrowth - animatable.getGrowth()) / maxGrowth;
            progress = progress * progress;

            var maxAlpha = 80;
            var alpha = (int) (progress * maxAlpha) << 24;

            var bloodTint = 0x5A0A0A;
            var color = bloodTint | alpha;

            context.setRenderColor(color);
            renderPipeline.reRender(context);
        }
    }

    @Override
    public void renderForBone(AzRendererPipelineContext<UUID, T> context, AzBone bone) {}
}
