package mod.azure.ovomorphosis.compat;

import com.alien.common.model.alien.Host;
import com.alien.common.util.AlienPredicates;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import mod.azure.ovomorphosis.items.InfectionScannerItem;

public class AVPCompat {

    public static boolean tryScanAVPInfection(LivingEntity target, Player scanner, ItemStack stack) {
        if (
            !(target instanceof Host)
                || scanner.level().isClientSide()
                || !AlienPredicates.hasEmbryo(target)
        ) {
            return false;
        }
        var isSelf = target == scanner;
        var who = isSelf
            ? Component.translatable("item.ovomorphosis.infection_scanner.tooltip.self")
            : target.getDisplayName();

        scanner.displayClientMessage(
            Component.translatable(
                "item.ovomorphosis.infection_scanner.tooltip.avp_impregnated",
                who
            ).withStyle(ChatFormatting.DARK_RED),
            true
        );

        scanner.level()
            .playSound(
                null,
                scanner.blockPosition(),
                SoundEvents.NOTE_BLOCK_PLING.value(),
                SoundSource.PLAYERS,
                0.8F,
                0.3F
            );

        InfectionScannerItem.setScannerModel(stack, InfectionScannerItem.MODEL_SYMPTOMATIC);

        return AlienPredicates.hasEmbryo(target);
    }
}
