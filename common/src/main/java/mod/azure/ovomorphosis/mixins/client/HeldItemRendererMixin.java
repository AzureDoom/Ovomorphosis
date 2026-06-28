package mod.azure.ovomorphosis.mixins.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import mod.azure.ovomorphosis.items.MotionTrackerItem;

@Mixin(ItemInHandRenderer.class)
public class HeldItemRendererMixin {

    @Mutable
    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    private float mainHandHeight;

    @Shadow
    private float offHandHeight;

    @Shadow
    private ItemStack mainHandItem;

    @Shadow
    private ItemStack offHandItem;

    @Inject(method = "tick", at = @At("TAIL"))
    private void ovomorphosis$cancelReequipAnimation(CallbackInfo ci) {
        var player = minecraft.player;
        if (player == null)
            return;

        var main = player.getMainHandItem();
        var off = player.getOffhandItem();

        if (
            mainHandItem.getItem() instanceof MotionTrackerItem
                && ItemStack.isSameItem(mainHandItem, main)
        ) {
            mainHandHeight = 1.0f;
            mainHandItem = main;
        }
        if (
            offHandItem.getItem() instanceof MotionTrackerItem
                && ItemStack.isSameItem(offHandItem, off)
        ) {
            offHandHeight = 1.0f;
            offHandItem = off;
        }
    }

    @Inject(method = "renderArmWithItem", at = @At("HEAD"))
    private void ovomorphosis$preCrossbowOffset(
        AbstractClientPlayer player,
        float partialTicks,
        float pitch,
        InteractionHand hand,
        float swingProgress,
        ItemStack stack,
        float equippedProgress,
        PoseStack poseStack,
        MultiBufferSource buffer,
        int combinedLight,
        CallbackInfo ci
    ) {
        if (!(stack.getItem() instanceof MotionTrackerItem))
            return;

        var isMainHand = hand == InteractionHand.MAIN_HAND;
        var arm = isMainHand ? player.getMainArm() : player.getMainArm().getOpposite();
        var i = arm == HumanoidArm.RIGHT ? 1F : -1F;

        poseStack.translate(i * -0.5F, -0.2F, -0.3F);
        poseStack.mulPose(Axis.YP.rotationDegrees(i * 10.0F));
    }
}
