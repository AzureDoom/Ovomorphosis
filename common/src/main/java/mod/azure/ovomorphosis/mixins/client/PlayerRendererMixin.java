package mod.azure.ovomorphosis.mixins.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import mod.azure.ovomorphosis.items.MagmaSprayerItem;
import mod.azure.ovomorphosis.items.MotionTrackerItem;

@Mixin(AvatarRenderer.class)
public class PlayerRendererMixin {

    @Inject(
        method = "getArmPose(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/client/model/HumanoidModel$ArmPose;",
        at = @At(value = "TAIL"), cancellable = true
    )
    private static void tryItemPose(
        Avatar avatar,
        ItemStack itemInHand,
        InteractionHand hand,
        CallbackInfoReturnable<HumanoidModel.ArmPose> cir
    ) {
        var itemstack = avatar.getItemInHand(hand);
        if (itemstack.getItem() instanceof MotionTrackerItem)
            cir.setReturnValue(HumanoidModel.ArmPose.CROSSBOW_HOLD);
        if (itemstack.getItem() instanceof MagmaSprayerItem)
            cir.setReturnValue(HumanoidModel.ArmPose.BOW_AND_ARROW);
    }
}
