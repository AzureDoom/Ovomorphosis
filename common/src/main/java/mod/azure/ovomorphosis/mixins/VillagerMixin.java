package mod.azure.ovomorphosis.mixins;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import mod.azure.ovomorphosis.items.InfectionScannerItem;
import mod.azure.ovomorphosis.items.MagmaSprayerItem;

@Mixin(Villager.class)
public class VillagerMixin {

    @Inject(at = @At("RETURN"), method = "mobInteract", cancellable = true)
    private void ovomorphosis$killVillager(
        Player player,
        InteractionHand hand,
        CallbackInfoReturnable<InteractionResult> cir
    ) {
        final ItemStack itemStack = player.getItemInHand(hand);
        if (itemStack.getItem() instanceof InfectionScannerItem || itemStack.getItem() instanceof MagmaSprayerItem) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
