package mod.azure.ovomorphosis.client.layer;

import mod.azure.azurelib.model.AzBone;
import mod.azure.azurelib.render.AzRendererPipelineContext;
import mod.azure.azurelib.render.layer.AzRenderLayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

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
        var animatable = context.animatable();

        if (!animatable.isAlive() || animatable.getGrowth() >= animatable.getMaxGrowth())
            return;

        var renderType = RenderTypes.entityTranslucentCull(getEntityTexture(animatable));

        var prevType = context.renderType();
        var prevConsumer = context.vertexConsumer();
        var prevColor = context.renderColor();

        float progress = (animatable.getMaxGrowth() - animatable.getGrowth()) / animatable.getMaxGrowth();
        int alpha = Math.round(progress * 0xFF) & 0xFF;
        int color = (prevColor & 0xFFFFFF) | (alpha << 24);

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

    private Identifier getEntityTexture(AbstractAlienEntity entity) {
        var name = "xenomorph";
        if (entity instanceof RunnerEntity)
            name = "runner";

        return CommonMod.modResource("textures/entity/" + name + "_youth.png");
    }
}
