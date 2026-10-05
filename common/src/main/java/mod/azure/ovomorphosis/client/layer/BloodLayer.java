package mod.azure.ovomorphosis.client.layer;

import mod.azure.azurelib.core.object.Color;
import mod.azure.azurelib.model.AzBone;
import mod.azure.azurelib.render.AzRendererPipelineContext;
import mod.azure.azurelib.render.layer.AzRenderLayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

import mod.azure.ovomorphosis.entities.AbstractAlienEntity;
import mod.azure.ovomorphosis.util.Growable;

public class BloodLayer<T extends AbstractAlienEntity & Growable> implements AzRenderLayer<UUID, T> {

    private static final ResourceLocation textureLocation =
        new ResourceLocation("textures/block/crimson_nylium.png");

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

        var renderType = RenderType.entityTranslucentCull(textureLocation);

        var prevType = context.renderType();
        var prevConsumer = context.vertexConsumer();
        float prevR = context.red(), prevG = context.green(), prevB = context.blue(), prevA = context.alpha();

        context.setRenderType(renderType);
        context.setVertexConsumer(context.multiBufferSource().getBuffer(renderType));
        context.setColor(Color.ofOpaque(0xFFC8C8)); // RGB only
        context.setAlpha(progress * (0xB0 / 255F));
        context.rendererPipeline().reRender(context);

        context.setRenderType(prevType);
        context.setVertexConsumer(prevConsumer);
        context.setRed(prevR);
        context.setGreen(prevG);
        context.setBlue(prevB);
        context.setAlpha(prevA);
    }

    @Override
    public void renderForBone(AzRendererPipelineContext<UUID, T> context, AzBone bone) {}
}
