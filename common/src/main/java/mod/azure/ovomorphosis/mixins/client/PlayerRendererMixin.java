package mod.azure.ovomorphosis.mixins.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import mod.azure.ovomorphosis.items.MotionTrackerItem;

@Mixin(PlayerRenderer.class)
public class PlayerRendererMixin {

    @Inject(method = "getArmPose", at = @At(value = "TAIL"), cancellable = true)
    private static void tryItemPose(
        AbstractClientPlayer player,
        InteractionHand hand,
        CallbackInfoReturnable<HumanoidModel.ArmPose> ci
    ) {
        var itemstack = player.getItemInHand(hand);
        if (itemstack.getItem() instanceof MotionTrackerItem)
            ci.setReturnValue(HumanoidModel.ArmPose.CROSSBOW_HOLD);
    }
}
