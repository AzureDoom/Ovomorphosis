package mod.azure.ovomorphosis.items;

import mod.azure.ovomorphosis.infection.InfectionManager;
import mod.azure.ovomorphosis.infection.InfectionState;
import net.minecraft.ChatFormatting;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

public class InfectionScannerItem extends Item {

    private static final int COOLDOWN_TICKS = 60;

    private static final int MAX_DAMAGE = 32;

    private static final double SCAN_RANGE = 4.0;

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
        scanEntity(Objects.requireNonNullElse(target, player), player);

        stack.hurtAndBreak(1, player, s -> player.broadcastBreakEvent(player.getUsedItemHand()));
        player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);

        return InteractionResultHolder.success(stack);
    }

    private void scanEntity(LivingEntity target, Player scanner) {
        var level = scanner.level();
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

            String who = isSelf ? "SELF" : target.getDisplayName().getString().toUpperCase();

            scanner.displayClientMessage(
                Component.literal("► [" + who + "] INFECTED — Stage: " + phaseStr)
                    .withStyle(ChatFormatting.RED),
                    true
            );

            scanner.displayClientMessage(
                Component.literal("Infection: " + phaseStr)
                    .withStyle(
                        phase == InfectionState.Phase.CRITICAL
                            ? ChatFormatting.DARK_RED
                            : ChatFormatting.YELLOW
                    ),
                true
            );

            level.playSound(
                null,
                scanner.blockPosition(),
                SoundEvents.NOTE_BLOCK_BASS.value(),
                SoundSource.PLAYERS,
                0.8F,
                0.5F
            );
        } else {
            var who = isSelf ? "SELF" : target.getDisplayName().getString().toUpperCase();
            scanner.displayClientMessage(
                Component.literal("► [" + who + "] CLEAR")
                    .withStyle(ChatFormatting.GREEN),
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
    private static LivingEntity findLookTarget(Player player, Level level) {
        var eyePos = player.getEyePosition();
        var lookVec = player.getLookAngle();

        return level.getEntitiesOfClass(
            LivingEntity.class,
            new AABB(player.blockPosition()).inflate(SCAN_RANGE),
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
    public void appendHoverText(@NotNull ItemStack stack, @Nullable Level level, List<Component> components, @NotNull TooltipFlag isAdvanced) {
        components.add(
                Component.translatable("item.ovomorphosis.infection_scanner.tooltip")
                        .withStyle(ChatFormatting.GRAY)
        );
        super.appendHoverText(stack, level, components, isAdvanced);
    }

    @Override
    public boolean isEnchantable(@NotNull ItemStack stack) {
        return false;
    }
}
