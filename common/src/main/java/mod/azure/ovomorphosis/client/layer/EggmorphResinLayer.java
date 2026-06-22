package mod.azure.ovomorphosis.client.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;

import mod.azure.ovomorphosis.CommonMod;

public class EggmorphResinLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {

    private static final ResourceLocation RESIN_TEXTURE = CommonMod.modResource(
        "textures/block/resin_web_6.png"
    );

    public EggmorphResinLayer(RenderLayerParent<T, M> renderer) {
        super(renderer);
    }

    @Override
    public void render(
        @NotNull PoseStack poseStack,
        @NotNull MultiBufferSource bufferSource,
        int packedLight,
        T entity,
        float limbSwing,
        float limbSwingAmount,
        float partialTick,
        float ageInTicks,
        float netHeadYaw,
        float headPitch
    ) {
        if (!EggmorphRenderState.isEggmorphing(entity.getId()))
            return;

        var progress = EggmorphRenderState.get(entity.getId());
        if (progress <= 0f)
            return;

        var alpha = 0.05f + (progress * 0.80f);

        var renderType = RenderType.entityTranslucent(RESIN_TEXTURE);
        var consumer = bufferSource.getBuffer(renderType);

        poseStack.pushPose();
        getParentModel().prepareMobModel(entity, limbSwing, limbSwingAmount, partialTick);
        getParentModel().renderToBuffer(
            poseStack,
            consumer,
            packedLight,
            OverlayTexture.NO_OVERLAY,
            toArgb(alpha, 1f, 1f, 1f)
        );
        poseStack.popPose();
    }

    private static int toArgb(float a, float r, float g, float b) {
        return ((int) (a * 255) << 24) | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }
}
