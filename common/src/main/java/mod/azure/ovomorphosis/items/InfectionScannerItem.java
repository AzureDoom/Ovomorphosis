package mod.azure.ovomorphosis.items;

import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.api.scanner.InfectionScanners;
import mod.azure.ovomorphosis.infection.InfectionManager;

public class InfectionScannerItem extends Item {

    public static final int MODEL_CLEAR = 0;

    public static final int MODEL_SYMPTOMATIC = 1;

    public static final int MODEL_CRITICAL = 2;

    private static final String SCAN_TIME = "ScanTime";

    private static final String SCAN_START = "ScanStart";

    private static final String SCAN_TARGET = "ScanTarget";

    private static final double MAX_TARGET_DISTANCE = 8.0D;

    public InfectionScannerItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public @NonNull InteractionResult use(
        @NonNull Level level,
        @NonNull Player player,
        @NonNull InteractionHand hand
    ) {
        var stack = player.getItemInHand(hand);

        if (player.getCooldowns().isOnCooldown(stack) || isScanning(stack)) {
            return InteractionResult.FAIL;
        }

        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        var target = findLookTarget(player, level);
        var targetId = Objects.requireNonNullElse(target, player).getUUID();

        beginScan(stack, targetId, level.getGameTime());

        level.playSound(
            null,
            player.blockPosition(),
            SoundEvents.NOTE_BLOCK_PLING.value(),
            SoundSource.PLAYERS,
            CommonMod.getConfig().itemConfigs.infectionScannerSoundVolume,
            0.7F
        );

        return InteractionResult.SUCCESS;
    }

    @Override
    public void inventoryTick(
        @NonNull ItemStack stack,
        @NonNull ServerLevel level,
        @NonNull Entity entity,
        @Nullable EquipmentSlot slot
    ) {
        super.inventoryTick(stack, level, entity, slot);
        tickScanProgress(stack, level, entity);
        tickDecay(stack, level);
    }

    /**
     * Advances an in-progress scan: plays periodic beeps while charging, and once the scan delay has elapsed, resolves
     * the locked-in target and reports the result.
     */
    private void tickScanProgress(ItemStack stack, ServerLevel level, Entity entity) {
        if (!isScanning(stack) || !(entity instanceof Player player)) {
            return;
        }

        var elapsed = level.getGameTime() - getLong(stack, SCAN_START);

        if (elapsed >= 60) {
            finishScan(stack, player, level);
            return;
        }

        if (elapsed % 5 == 0) {
            level.playSound(
                null,
                player.blockPosition(),
                SoundEvents.NOTE_BLOCK_PLING.value(),
                SoundSource.PLAYERS,
                CommonMod.getConfig().itemConfigs.infectionScannerSoundVolume,
                2.0F
            );
        }
    }

    /**
     * Resolves the target that was locked in when the scan started, runs the actual infection check, and applies
     * durability/cooldown now that the reading is complete.
     */
    private void finishScan(ItemStack stack, Player player, ServerLevel level) {
        var targetId = getScanTarget(stack);
        clearScanState(stack);

        var target = resolveScanTarget(targetId, player, level);

        if (target == null) {
            reportTargetLost(stack, player, level);
        } else {
            scanEntity(target, player, stack);
        }

        damageScanner(stack, player);
        player.getCooldowns().addCooldown(stack, 30);
    }

    /**
     * Resolves the target locked in when the scan started.
     *
     * @return the player for a self-scan, the target if it is still alive, in this level and within
     *         {@link #MAX_TARGET_DISTANCE}, or {@code null} if the target was lost during the scan
     */
    private static LivingEntity resolveScanTarget(UUID targetId, Player player, Level level) {
        if (targetId == null || targetId.equals(player.getUUID())) {
            return player;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }

        if (
            serverLevel.getEntity(targetId) instanceof LivingEntity living
                && living.isAlive()
                && living.distanceToSqr(player) <= MAX_TARGET_DISTANCE * MAX_TARGET_DISTANCE
        ) {
            return living;
        }

        return null;
    }

    private void reportTargetLost(ItemStack stack, Player player, Level level) {
        setScannerModel(stack, MODEL_CLEAR);
        clearScanTime(stack);

        player.sendOverlayMessage(
            Component.translatable("item.ovomorphosis.infection_scanner.tooltip.target_lost")
                .withStyle(ChatFormatting.YELLOW)
        );

        level.playSound(
            null,
            player.blockPosition(),
            SoundEvents.NOTE_BLOCK_BASS.value(),
            SoundSource.PLAYERS,
            CommonMod.getConfig().itemConfigs.infectionScannerSoundVolume,
            0.8F
        );
    }

    /**
     * Applies one point of durability. Scans keep running from {@link #inventoryTick} even after the player switches
     * away, so the scanner isn't necessarily in the main hand. The break event is attributed to whichever hand actually
     * holds it, and if it's in neither hand, only the break sound is played.
     */
    private static void damageScanner(ItemStack stack, Player player) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        var heldSlot = player.getMainHandItem() == stack
            ? EquipmentSlot.MAINHAND
            : player.getOffhandItem() == stack ? EquipmentSlot.OFFHAND : null;

        stack.hurtAndBreak(
            1,
            serverLevel,
            player instanceof ServerPlayer serverPlayer ? serverPlayer : null,
            item -> {
                if (heldSlot != null) {
                    player.onEquippedItemBroken(item, heldSlot);
                } else {
                    serverLevel.playSound(
                        null,
                        player.blockPosition(),
                        SoundEvents.ITEM_BREAK.value(),
                        player.getSoundSource(),
                        0.8F,
                        0.8F + serverLevel.getRandom().nextFloat() * 0.4F
                    );
                }
            }
        );
    }

    /**
     * Ticks the decay timer for a completed reading. Once the reading has been displayed long enough, the model resets
     * to neutral (no CustomModelData).
     */
    private void tickDecay(ItemStack stack, Level level) {
        if (getScannerModel(stack) <= MODEL_CLEAR) {
            return;
        }

        var scanTime = getScanTime(stack);
        if (scanTime <= 0) {
            return;
        }

        if (level.getGameTime() - scanTime >= 100L) {
            setScannerModel(stack, MODEL_CLEAR);
            clearScanTime(stack);
        }
    }

    private void scanEntity(LivingEntity target, Player scanner, ItemStack stack) {
        var level = scanner.level();

        var detailed = !CommonMod.getConfig().itemConfigs.disableInfectionScannerTimeOutput;
        var who = target == scanner
            ? Component.translatable("item.ovomorphosis.infection_scanner.tooltip.self")
            : target.getDisplayName();

        MutableComponent message = null;
        var model = MODEL_CLEAR;
        var pitch = 1.5F;

        if (InfectionManager.isInfected(target)) {
            var phase = InfectionManager.getPhase(target);
            if (phase != null) {
                var phaseKey = Component.translatable(
                    "item.ovomorphosis.infection_scanner.tooltip.stage." + phase.name().toLowerCase(Locale.ROOT)
                );
                message = detailed
                    ? Component.translatable(
                        "item.ovomorphosis.infection_scanner.tooltip.infected",
                        who,
                        phaseKey,
                        InfectionManager.getInfectionRemainingTime(target) / 20
                    )
                    : Component.translatable(
                        "item.ovomorphosis.infection_scanner.tooltip.infected_no_time",
                        who,
                        phaseKey
                    );
                message.withStyle(ChatFormatting.RED);
                model = switch (phase) {
                    case DORMANT -> MODEL_CLEAR;
                    case SYMPTOMATIC -> MODEL_SYMPTOMATIC;
                    case CRITICAL -> MODEL_CRITICAL;
                };
                pitch = 0.5F;
            }
        }

        for (var reading : InfectionScanners.collect(target, scanner, detailed)) {
            var line = reading.detail().copy().withStyle(reading.severity().color);
            if (message == null) {
                message = Component.translatable("item.ovomorphosis.infection_scanner.tooltip.reading", who, line);
            } else {
                message.append(Component.literal(" | ").withStyle(ChatFormatting.GRAY)).append(line);
            }
            model = Math.max(model, reading.severity().model);
            pitch = Math.min(pitch, reading.severity().pitch);
        }

        if (message == null) {
            message = Component.translatable("item.ovomorphosis.infection_scanner.tooltip.clear", who)
                .withStyle(ChatFormatting.GREEN);
        }

        setScannerModel(stack, model);
        if (model > MODEL_CLEAR) {
            setScanTime(stack, level.getGameTime());
        } else {
            clearScanTime(stack);
        }

        scanner.sendOverlayMessage(message);
        level.playSound(
            null,
            scanner.blockPosition(),
            SoundEvents.NOTE_BLOCK_PLING.value(),
            SoundSource.PLAYERS,
            CommonMod.getConfig().itemConfigs.infectionScannerSoundVolume,
            pitch
        );
    }

    /**
     * Finds the living entity closest to the player's line of sight within range, ignoring anything behind walls.
     * Returns null if none found (triggers self-scan).
     */
    public static LivingEntity findLookTarget(Player player, Level level) {
        var eyePos = player.getEyePosition();
        var lookVec = player.getLookAngle();

        return level.getEntitiesOfClass(
            LivingEntity.class,
            new AABB(player.blockPosition()).inflate(4),
            e -> e != player && e.isAlive() && player.hasLineOfSight(e)
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
    @SuppressWarnings("deprecation")
    public void appendHoverText(
        @NonNull ItemStack itemStack,
        @NonNull TooltipContext context,
        @NonNull TooltipDisplay display,
        @NonNull Consumer<Component> builder,
        @NonNull TooltipFlag tooltipFlag
    ) {
        builder.accept(
            Component.translatable("item.ovomorphosis.infection_scanner.tooltip")
                .withStyle(ChatFormatting.GRAY)
        );
        super.appendHoverText(itemStack, context, display, builder, tooltipFlag);
    }

    public static void setScannerModel(ItemStack stack, int customModelData) {
        if (customModelData <= 0) {
            stack.remove(DataComponents.CUSTOM_MODEL_DATA);
            return;
        }

        stack.set(
            DataComponents.CUSTOM_MODEL_DATA,
            new CustomModelData(List.of((float) customModelData), List.of(), List.of(), List.of())
        );
    }

    public static int getScannerModel(ItemStack stack) {
        var cmd = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        if (cmd == null) {
            return MODEL_CLEAR;
        }
        var value = cmd.getFloat(0);
        return value == null ? MODEL_CLEAR : value.intValue();
    }

    public static void setScanTime(ItemStack stack, long time) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, data -> {
            var tag = data.copyTag();
            tag.putLong(SCAN_TIME, time);
            return CustomData.of(tag);
        });
    }

    public static long getScanTime(ItemStack stack) {
        return getLong(stack, SCAN_TIME);
    }

    public static void clearScanTime(ItemStack stack) {
        removeKeys(stack, SCAN_TIME);
    }

    private static boolean isScanning(ItemStack stack) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().contains(SCAN_START);
    }

    private static void beginScan(ItemStack stack, UUID targetId, long gameTime) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, data -> {
            var tag = data.copyTag();
            tag.putLong(SCAN_START, gameTime);
            tag.store(SCAN_TARGET, UUIDUtil.CODEC, targetId);
            return CustomData.of(tag);
        });
    }

    private static @Nullable UUID getScanTarget(ItemStack stack) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        return data.copyTag().read(SCAN_TARGET, UUIDUtil.CODEC).orElse(null);
    }

    private static void clearScanState(ItemStack stack) {
        removeKeys(stack, SCAN_START, SCAN_TARGET);
    }

    private static long getLong(ItemStack stack, String key) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? 0L : data.copyTag().getLongOr(key, 0L);
    }

    private static void removeKeys(ItemStack stack, String... keys) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return;
        }
        var tag = data.copyTag();
        var changed = false;
        for (var key : keys) {
            if (tag.contains(key)) {
                tag.remove(key);
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        if (tag.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }
}
