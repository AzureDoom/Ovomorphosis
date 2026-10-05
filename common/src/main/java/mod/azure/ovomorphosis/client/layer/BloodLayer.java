package mod.azure.ovomorphosis.client.layer;

import mod.azure.azurelib.common.model.AzBone;
import mod.azure.azurelib.common.render.AzRendererPipelineContext;
import mod.azure.azurelib.common.render.layer.AzRenderLayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

import mod.azure.ovomorphosis.entities.AbstractAlienEntity;
import mod.azure.ovomorphosis.util.Growable;

public class BloodLayer<T extends AbstractAlienEntity & Growable> implements AzRenderLayer<UUID, T> {

    private static final ResourceLocation textureLocation =
        ResourceLocation.withDefaultNamespace("textures/block/crimson_nylium.png");

    @Override
    public void preRender(AzRendererPipelineContext<UUID, T> context) {}

    @Override
    public void render(AzRendererPipelineContext<UUID, T> context) {
        var animatable = context.animatable();
        var half = animatable.getMaxGrowth() / 2F;
        var growth = animatable.getGrowth();

        if (!animatable.isAlive() || growth >= half)
            return;

        var progress = (half - growth) / half;
        progress *= progress;

        var alpha = Math.round(progress * 0xB0) & 0xFF;
        var bloodTint = 0xFFC8C8;
        var color = (alpha << 24) | bloodTint;

        var renderType = RenderType.entityTranslucentCull(textureLocation);

        var prevType = context.renderType();
        var prevConsumer = context.vertexConsumer();
        var prevColor = context.renderColor();

        context.setRenderType(renderType);
        context.setVertexConsumer(context.multiBufferSource().getBuffer(renderType));
        context.setRenderColor(color);
        context.rendererPipeline().reRender(context);

        context.setRenderType(prevType);
        context.setVertexConsumer(prevConsumer);
        context.setRenderColor(prevColor);
    }

    @Override
    public void renderForBone(AzRendererPipelineContext<UUID, T> context, AzBone bone) {}
}
