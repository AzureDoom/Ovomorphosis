package mod.azure.ovomorphosis.mixins.client;

import net.minecraft.client.player.FirstPersonHandsAndItems;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import mod.azure.ovomorphosis.items.InfectionScannerItem;
import mod.azure.ovomorphosis.items.MagmaSprayerItem;
import mod.azure.ovomorphosis.items.MotionTrackerItem;

@Mixin(FirstPersonHandsAndItems.class)
public class FirstPersonHandsAndItemsMixin {

    @Inject(method = "shouldInstantlyReplaceVisibleItem", at = @At("HEAD"), cancellable = true)
    private void ovomorphosis$cancelReequipAnimation(
        ItemStack currentlyVisibleItem,
        ItemStack expectedItem,
        LocalPlayer player,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (
            ItemStack.isSameItem(currentlyVisibleItem, expectedItem)
                && ovomorphosis$hasNoReequipAnimation(expectedItem)
        ) {
            cir.setReturnValue(true);
        }
    }

    @Unique
    private static boolean ovomorphosis$hasNoReequipAnimation(ItemStack stack) {
        return stack.getItem() instanceof MotionTrackerItem
            || stack.getItem() instanceof InfectionScannerItem
            || stack.getItem() instanceof MagmaSprayerItem;
    }
}
