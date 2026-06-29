package mod.azure.ovomorphosis.mixins;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import mod.azure.ovomorphosis.compat.GigeresqueCompat;
import mod.azure.ovomorphosis.services.XenoServices;

@Mixin(Item.class)
public class MixinItem_GigeresqueSurgeryKit {

    @Inject(method = "interactLivingEntity", at = @At("HEAD"))
    private void ovomorphosis$removeParasiteOnEntity(
        ItemStack stack,
        Player player,
        LivingEntity interactionTarget,
        InteractionHand usedHand,
        CallbackInfoReturnable<InteractionResult> cir
    ) {
        if (XenoServices.COMMON_REGISTRY.isModLoaded("gigeresque")) {
            GigeresqueCompat.removeParasite(player, interactionTarget, stack);
        }
    }

    @Inject(method = "use", at = @At("HEAD"))
    private void ovomorphosis$removeParasiteOnUse(
        Level level,
        Player player,
        InteractionHand usedHand,
        CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir
    ) {
        if (XenoServices.COMMON_REGISTRY.isModLoaded("gigeresque")) {
            GigeresqueCompat.removeParasite(player, player, player.getItemInHand(usedHand));
        }
    }
}
