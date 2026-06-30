package mod.azure.ovomorphosis.compat;

import mods.cybercat.gigeresque.CommonMod;
import mods.cybercat.gigeresque.common.item.GigItems;
import mods.cybercat.gigeresque.common.status.effect.GigStatusEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import mod.azure.ovomorphosis.entities.chestburster.ChestbursterEntity;
import mod.azure.ovomorphosis.entities.runner.RunnerEntity;
import mod.azure.ovomorphosis.infection.InfectionManager;
import mod.azure.ovomorphosis.items.InfectionScannerItem;
import mod.azure.ovomorphosis.registry.EntityRegistry;
import mod.azure.ovomorphosis.util.ModTags;

public class GigeresqueCompat {

    public static boolean hasGigBurster(LivingEntity livingEntity) {
        return livingEntity.hasEffect(GigStatusEffects.IMPREGNATION);
    }

    public static boolean tryScanGigInfection(LivingEntity target, Player scanner, ItemStack stack) {
        var isSelf = target == scanner;
        var who = isSelf
            ? Component.translatable("item.ovomorphosis.infection_scanner.tooltip.self")
            : target.getDisplayName();

        scanner.displayClientMessage(
            Component.translatable(
                "item.ovomorphosis.infection_scanner.tooltip.gig_impregnated",
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

        return hasGigBurster(target);
    }

    public static void removeParasite(Player player, LivingEntity livingEntity, ItemStack itemStack) {
        if (
            player.level().isClientSide() || !InfectionManager.isInfected(livingEntity) || !itemStack.is(
                GigItems.SURGERY_KIT.get()
            )
        ) {
            return;
        }

        if (livingEntity.getType().is(ModTags.XENOMORPH_HOST)) {
            InfectionManager.spawnMob(
                livingEntity,
                (ServerLevel) player.level(),
                new ChestbursterEntity(EntityRegistry.CHESTBURSTER.get(), livingEntity.level())
            );
        } else if (livingEntity.getType().is(ModTags.RUNNER_HOST)) {
            InfectionManager.spawnMob(
                livingEntity,
                (ServerLevel) player.level(),
                new RunnerEntity(EntityRegistry.RUNNER.get(), livingEntity.level())
            );
        }
        applySurgeryKitBehavior(player, livingEntity, itemStack);
    }

    private static void applySurgeryKitBehavior(Player player, LivingEntity livingEntity, ItemStack itemStack) {
        player.getCooldowns().addCooldown(itemStack.getItem(), CommonMod.config.generalConfigs.surgeryKitCooldownTicks);

        if (!player.isCreative() || !player.isSpectator()) {
            itemStack.hurtAndBreak(1, player, livingEntity.getEquipmentSlotForItem(itemStack));
        }

        if (player instanceof ServerPlayer serverPlayer) {
            var advancement = serverPlayer.server.getAdvancements()
                .get(ResourceLocation.fromNamespaceAndPath("gigeresque", "surgery_kit"));

            if (advancement != null && !serverPlayer.getAdvancements().getOrStartProgress(advancement).isDone()) {
                for (var s : serverPlayer.getAdvancements().getOrStartProgress(advancement).getRemainingCriteria()) {
                    serverPlayer.getAdvancements().award(advancement, s);
                }
            }
        }
    }
}
