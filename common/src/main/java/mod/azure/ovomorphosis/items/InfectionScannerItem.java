package mod.azure.ovomorphosis.items;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import mod.azure.ovomorphosis.compat.AVPCompat;
import mod.azure.ovomorphosis.compat.GigeresqueCompat;
import mod.azure.ovomorphosis.infection.InfectionManager;
import mod.azure.ovomorphosis.services.XenoServices;

public class InfectionScannerItem extends Item {

    private static final int MAX_DAMAGE = 32;

    public static final int MODEL_CLEAR = 0;

    public static final int MODEL_SYMPTOMATIC = 1;

    public static final int MODEL_CRITICAL = 2;

    public InfectionScannerItem() {
        super(new Item.Properties().durability(MAX_DAMAGE).stacksTo(1));
    }

    @Override
    public @NotNull InteractionResultHolder<ItemStack> use(
        @NotNull Level level,
        @NotNull Player player,
        @NotNull InteractionHand hand
    ) {
        var stack = player.getItemInHand(hand);

        if (player.getCooldowns().isOnCooldown(this)) {
            return InteractionResultHolder.fail(stack);
        }

        if (level.isClientSide()) {
            return InteractionResultHolder.success(stack);
        }

        var target = findLookTarget(player, level);
        scanEntity(Objects.requireNonNullElse(target, player), player, stack);

        stack.hurtAndBreak(1, player, player.getEquipmentSlotForItem(stack));
        player.getCooldowns().addCooldown(this, 30);

        return InteractionResultHolder.success(stack);
    }

    private void scanEntity(LivingEntity target, Player scanner, ItemStack stack) {
        var level = scanner.level();

        if (XenoServices.COMMON_REGISTRY.isModLoaded("gigeresque")) {
            if (GigeresqueCompat.tryScanGigInfection(target, scanner, stack)) {
                return;
            }
        }

        if (XenoServices.COMMON_REGISTRY.isModLoaded("avp_alien")) {
            if (AVPCompat.tryScanAVPInfection(target, scanner, stack)) {
                return;
            }
        }

        var infected = InfectionManager.isInfected(target);
        var isSelf = target == scanner;

        if (infected) {
            var phase = InfectionManager.getPhase(target);
            if (phase == null) {
                return;
            }
            var phaseStr = switch (phase) {
                case DORMANT -> "DORMANT";
                case SYMPTOMATIC -> "SYMPTOMATIC";
                case CRITICAL -> "CRITICAL";
            };

            var modelData = switch (phase) {
                case DORMANT -> MODEL_CLEAR;
                case SYMPTOMATIC -> MODEL_SYMPTOMATIC;
                case CRITICAL -> MODEL_CRITICAL;
            };

            setScannerModel(stack, modelData);

            var who = isSelf
                ? Component.translatable("item.ovomorphosis.infection_scanner.tooltip.self")
                : target.getDisplayName();

            var phaseKey = Component.translatable(
                "item.ovomorphosis.infection_scanner.tooltip.stage." + phaseStr.toLowerCase(Locale.ROOT)
            );

            scanner.displayClientMessage(
                Component.translatable(
                    "item.ovomorphosis.infection_scanner.tooltip.infected",
                    who,
                    phaseKey
                ).withStyle(ChatFormatting.RED),
                true
            );

            level.playSound(
                null,
                scanner.blockPosition(),
                SoundEvents.NOTE_BLOCK_PLING.value(),
                SoundSource.PLAYERS,
                0.8F,
                0.5F
            );
        } else {
            setScannerModel(stack, MODEL_CLEAR);
            var who = isSelf
                ? Component.translatable("item.ovomorphosis.infection_scanner.tooltip.self")
                : target.getDisplayName();

            scanner.displayClientMessage(
                Component.translatable(
                    "item.ovomorphosis.infection_scanner.tooltip.clear",
                    who
                ).withStyle(ChatFormatting.GREEN),
                true
            );

            level.playSound(
                null,
                scanner.blockPosition(),
                SoundEvents.NOTE_BLOCK_PLING.value(),
                SoundSource.PLAYERS,
                0.6F,
                1.5F
            );
        }
    }

    /**
     * Finds the nearest living entity the player is roughly looking at within SCAN_RANGE. Returns null if none found
     * (triggers self-scan).
     */
    public static LivingEntity findLookTarget(Player player, Level level) {
        var eyePos = player.getEyePosition();
        var lookVec = player.getLookAngle();

        return level.getEntitiesOfClass(
            LivingEntity.class,
            new AABB(player.blockPosition()).inflate(4),
            e -> e != player && e.isAlive()
        )
            .stream()
            .filter(e -> {
                var toEntity = e.getEyePosition().subtract(eyePos).normalize();
                return toEntity.dot(lookVec) > 0.85;
            })
            .min((a, b) -> {
                var dA = a.getEyePosition().subtract(eyePos).cross(lookVec).lengthSqr();
                var dB = b.getEyePosition().subtract(eyePos).cross(lookVec).lengthSqr();
                return Double.compare(dA, dB);
            })
            .orElse(null);
    }

    @Override
    public void appendHoverText(
        @NotNull ItemStack stack,
        @NotNull TooltipContext context,
        @NotNull List<Component> components,
        @NotNull TooltipFlag flag
    ) {
        components.add(
            Component.translatable("item.ovomorphosis.infection_scanner.tooltip")
                .withStyle(ChatFormatting.GRAY)
        );
    }

    @Override
    public boolean isEnchantable(@NotNull ItemStack stack) {
        return false;
    }

    public static void setScannerModel(ItemStack stack, int customModelData) {
        if (customModelData <= 0) {
            stack.remove(DataComponents.CUSTOM_MODEL_DATA);
            return;
        }

        stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(customModelData));
    }
}
