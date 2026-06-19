package mod.azure.xenogenesis.client.facehugger;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import mod.azure.azurelib.common.render.AzLayerRenderer;
import mod.azure.azurelib.common.render.entity.AzEntityRendererPipeline;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

import mod.azure.xenogenesis.client.XenoModelRenderer;
import mod.azure.xenogenesis.entities.facehugger.FacehuggerEntity;

/**
 * Credit to Boston for this code
 */
public class FacehuggerModelRenderer extends XenoModelRenderer<FacehuggerEntity> {

    public FacehuggerModelRenderer(
        AzEntityRendererPipeline<FacehuggerEntity> entityRendererPipeline,
        AzLayerRenderer<UUID, FacehuggerEntity> layerRenderer
    ) {
        super(entityRendererPipeline, layerRenderer);
    }

    @Override
    protected void applyRotations(
        FacehuggerEntity animatable,
        PoseStack poseStack,
        float ageInTicks,
        float rotationYaw,
        float partialTick,
        float nativeScale
    ) {
        if (!animatable.isPassenger()) {
            super.applyRotations(animatable, poseStack, ageInTicks, rotationYaw, partialTick, nativeScale);
            return;
        }

        var host = (LivingEntity) animatable.getVehicle();

        if (host == null) {
            return;
        }

        var data = EntityHeadData.ENTITY_HEAD_DATA_BY_TYPE.get(host.getType());

        if (data == null) {
            return;
        }

        applyFaceRotations(animatable, poseStack, partialTick, host, data);
    }

    private void applyFaceRotations(
        FacehuggerEntity facehuggerEntity,
        PoseStack poseStack,
        float partialTick,
        LivingEntity host,
        EntityHeadData data
    ) {
        var bodyYaw = Mth.rotLerp(partialTick, host.yBodyRotO, host.yBodyRot);
        var headYaw = Mth.rotLerp(partialTick, host.yHeadRotO, host.yHeadRot) - bodyYaw;
        var headPitch = Mth.rotLerp(partialTick, host.getXRot(), host.xRotO);

        var xPivot = data.pivot().x;
        var yPivot = data.pivot().y;
        var zPivot = data.pivot().z;
        var ySize = data.size().y;
        var zSize = data.size().z;

        poseStack.mulPose(Axis.YN.rotationDegrees(bodyYaw));

        poseStack.translate(xPivot, yPivot - host.getBbHeight(), -zPivot);
        poseStack.mulPose(Axis.YN.rotationDegrees(headYaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(headPitch));
        poseStack.translate(-xPivot, -yPivot + host.getBbHeight(), zPivot);

        var result = EntityHeadOffsetData.resolve(host.getType(), data, facehuggerEntity);

        if (result != null) {
            poseStack.translate(0, result.vertical(), result.face());
        } else {
            poseStack.translate(0, -ySize, zSize);
        }
    }
}
