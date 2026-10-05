package mod.azure.ovomorphosis.client.layer;

import mod.azure.azurelib.core.object.Color;
import mod.azure.azurelib.model.AzBone;
import mod.azure.azurelib.render.AzRendererPipelineContext;
import mod.azure.azurelib.render.layer.AzRenderLayer;
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
        var animatable = context.animatable();

        if (!animatable.isAlive() || animatable.getGrowth() >= animatable.getMaxGrowth())
            return;

        var progress = (animatable.getMaxGrowth() - animatable.getGrowth()) / animatable.getMaxGrowth();

        var renderType = RenderType.entityTranslucentCull(getEntityTexture(animatable));

        var prevType = context.renderType();
        var prevConsumer = context.vertexConsumer();
        float prevR = context.red(), prevG = context.green(), prevB = context.blue(), prevA = context.alpha();

        context.setRenderType(renderType);
        context.setVertexConsumer(context.multiBufferSource().getBuffer(renderType));
        context.setColor(Color.WHITE);
        context.setAlpha(progress);
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

    private ResourceLocation getEntityTexture(AbstractAlienEntity entity) {
        var name = "xenomorph";
        if (entity instanceof RunnerEntity)
            name = "runner";

        return CommonMod.modResource("textures/entity/" + name + "_youth.png");
    }
}
