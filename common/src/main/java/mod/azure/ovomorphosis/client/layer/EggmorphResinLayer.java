package mod.azure.ovomorphosis.client.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

import mod.azure.ovomorphosis.CommonMod;

public class EggmorphResinLayer<S extends LivingEntityRenderState, M extends EntityModel<? super S>> extends RenderLayer<S, M> {

    private static final Identifier RESIN_TEXTURE = CommonMod.modResource("textures/block/resin_web_6.png");

    public EggmorphResinLayer(RenderLayerParent<S, M> renderer) {
        super(renderer);
    }

    @Override
    public void submit(
        @NonNull PoseStack poseStack,
        @NonNull SubmitNodeCollector submitNodeCollector,
        int lightCoords,
        @NonNull S state,
        float yRot,
        float xRot
    ) {
        if (state.isInvisible)
            return;

        var progress = ((EggmorphRenderStateAccess) state).ovomorphosis$getEggmorphProgress();
        if (progress <= 0f)
            return;

        var alpha = 0.05f + (progress * 0.80f);

        submitNodeCollector.order(1)
            .submitModel(
                getParentModel(),
                state,
                poseStack,
                RenderTypes.entityTranslucent(RESIN_TEXTURE),
                lightCoords,
                OverlayTexture.NO_OVERLAY,
                whiteWithAlpha(alpha),
                null,
                state.outlineColor
            );
    }

    private static int whiteWithAlpha(float alpha) {
        return ((int) (alpha * 255f) << 24) | 0x00FFFFFF;
    }
}
