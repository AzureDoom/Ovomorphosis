package mod.azure.ovomorphosis.mixins.client;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import mod.azure.ovomorphosis.items.MotionTrackerItem;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
public class HeldItemRendererMixin {

    @Inject(method = "submitArmWithItem", at = @At("HEAD"))
    private void ovomorphosis$preCrossbowOffset(
        CallbackInfo ci,
        @Local(argsOnly = true, name = "hand") InteractionHand hand,
        @Local(argsOnly = true, name = "itemStack") ItemStack itemStack,
        @Local(argsOnly = true, name = "poseStack") PoseStack poseStack
    ) {
        if (!(itemStack.getItem() instanceof MotionTrackerItem))
            return;

        var mainArm = Minecraft.getInstance().options.mainHand().get();
        var arm = hand == InteractionHand.MAIN_HAND ? mainArm : mainArm.getOpposite();
        var i = arm == HumanoidArm.RIGHT ? 1F : -1F;

        poseStack.translate(i * -0.5F, -0.2F, -0.3F);
        poseStack.rotateDegrees(Axis.YP, i * 10.0F);
    }
}
