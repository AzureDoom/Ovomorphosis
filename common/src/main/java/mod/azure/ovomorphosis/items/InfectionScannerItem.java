package mod.azure.ovomorphosis.items;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
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
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.api.scanner.InfectionScanners;
import mod.azure.ovomorphosis.infection.InfectionManager;

public class InfectionScannerItem extends Item {

    private static final int MAX_DAMAGE = 32;

    private static final int MODEL_CLEAR = 0;

    private static final int MODEL_SYMPTOMATIC = 1;

    private static final int MODEL_CRITICAL = 2;

    /** A locked-in target further away than this when the scan completes counts as lost. */
    private static final double MAX_TARGET_DISTANCE = 8.0D;

    public InfectionScannerItem() {
        super(new Item.Properties().durability(MAX_DAMAGE));
    }

    @Override
    public @NotNull InteractionResultHolder<ItemStack> use(
        @NotNull Level level,
        @NotNull Player player,
        @NotNull InteractionHand hand
    ) {
        var stack = player.getItemInHand(hand);

        if (player.getCooldowns().isOnCooldown(this) || isScanning(stack)) {
            return InteractionResultHolder.fail(stack);
        }

        if (level.isClientSide()) {
            return InteractionResultHolder.success(stack);
        }

        var target = findLookTarget(player, level);
        var targetId = Objects.requireNonNullElse(target, player).getUUID();

        var tag = stack.getOrCreateTag();
        tag.putLong("ScanStart", level.getGameTime());
        tag.putUUID("ScanTarget", targetId);

        level.playSound(
            null,
            player.blockPosition(),
            SoundEvents.NOTE_BLOCK_PLING.value(),
            SoundSource.PLAYERS,
            CommonMod.getConfig().itemConfigs.infectionScannerSoundVolume,
            0.7F
        );

        return InteractionResultHolder.success(stack);
    }

    @Override
    public void inventoryTick(
        @NotNull ItemStack stack,
        @NotNull Level level,
        @NotNull Entity entity,
        int slotId,
        boolean isSelected
    ) {
        super.inventoryTick(stack, level, entity, slotId, isSelected);

        if (level.isClientSide()) {
            return;
        }

        if (entity != null)
            tickScanProgress(stack, level, entity);
        tickDecay(stack, level);
    }

    /**
     * Advances an in-progress scan: plays periodic beeps while charging, and once SCAN_DELAY_TICKS has elapsed,
     * resolves the locked-in target and reports the result.
     */
    private void tickScanProgress(ItemStack stack, Level level, Entity entity) {
        if (!isScanning(stack)) {
            return;
        }

        if (!(entity instanceof Player player)) {
            return;
        }

        var elapsed = level.getGameTime() - stack.getOrCreateTag().getLong("ScanStart");

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
    private void finishScan(ItemStack stack, Player player, Level level) {
        var tag = stack.getOrCreateTag();
        var targetId = tag.hasUUID("ScanTarget") ? tag.getUUID("ScanTarget") : null;

        tag.remove("ScanStart");
        tag.remove("ScanTarget");
        clearTagIfEmpty(stack);

        var target = resolveScanTarget(targetId, player, level);

        if (target == null) {
            reportTargetLost(stack, Objects.requireNonNull(player), level);
        } else {
            scanEntity(target, player, stack);
        }

        damageScanner(stack, player);
        player.getCooldowns().addCooldown(this, 30);
    }

    /**
     * Resolves the target locked in when the scan started.
     *
     * @return the player for a self-scan, the target if it is still alive, in this level and within
     *         {@link #MAX_TARGET_DISTANCE}, or {@code null} if the target was lost during the scan
     */
    private static @Nullable LivingEntity resolveScanTarget(@Nullable UUID targetId, Player player, Level level) {
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

        player.displayClientMessage(
            Component.translatable("item.ovomorphosis.infection_scanner.tooltip.target_lost")
                .withStyle(ChatFormatting.YELLOW),
            true
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
     * <p>
     * The entity-based {@code hurtAndBreak} already skips creative players, shrinks the stack and awards the
     * item-broken stat.
     */
    private static void damageScanner(ItemStack stack, Player player) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        var heldSlot = player.getMainHandItem() == stack
            ? EquipmentSlot.MAINHAND
            : player.getOffhandItem() == stack ? EquipmentSlot.OFFHAND : null;

        stack.hurtAndBreak(1, player, p -> {
            if (heldSlot != null) {
                p.broadcastBreakEvent(heldSlot);
            } else {
                serverLevel.playSound(
                    null,
                    p.blockPosition(),
                    SoundEvents.ITEM_BREAK,
                    p.getSoundSource(),
                    0.8F,
                    0.8F + serverLevel.getRandom().nextFloat() * 0.4F
                );
            }
        });
    }

    /**
     * Ticks the decay timer for a completed reading. Once the reading has been displayed for DECAY_TICKS, the model
     * resets to neutral (MODEL_CLEAR / no CustomModelData).
     */
    private void tickDecay(ItemStack stack, Level level) {
        var tag = stack.getTag();
        if (tag == null || !tag.contains("CustomModelData") || tag.getInt("CustomModelData") <= MODEL_CLEAR) {
            return;
        }

        if (!tag.contains("ScanTime")) {
            return;
        }

        if (level.getGameTime() - tag.getLong("ScanTime") >= 100L) {
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

        // Ovomorphosis' own xenomorph infection
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

        // Readings contributed by addons (e.g. Pathogenesis exposure / infection)
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
            stack.getOrCreateTag().putLong("ScanTime", level.getGameTime());
        } else {
            clearScanTime(stack);
        }

        scanner.displayClientMessage(message, true);
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
    public static @Nullable LivingEntity findLookTarget(Player player, Level level) {
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
    public void appendHoverText(
        @NotNull ItemStack stack,
        @Nullable Level level,
        List<Component> components,
        @NotNull TooltipFlag isAdvanced
    ) {
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

    private static boolean isScanning(ItemStack stack) {
        var tag = stack.getTag();
        return tag != null && tag.contains("ScanStart");
    }

    public static void setScannerModel(ItemStack stack, int customModelData) {
        if (customModelData <= 0) {
            var tag = stack.getTag();
            if (tag != null) {
                tag.remove("CustomModelData");
                clearTagIfEmpty(stack);
            }
            return;
        }

        stack.getOrCreateTag().putInt("CustomModelData", customModelData);
    }

    /**
     * Removes the reading timestamp without creating a tag on a clean stack. {@code getOrCreateTag().remove(...)} here
     * would leave an empty {@code {}} tag behind on every clear scan, because {@link #setScannerModel} may already have
     * nulled the tag.
     */
    private static void clearScanTime(ItemStack stack) {
        var tag = stack.getTag();
        if (tag == null) {
            return;
        }
        tag.remove("ScanTime");
        clearTagIfEmpty(stack);
    }

    private static void clearTagIfEmpty(ItemStack stack) {
        var tag = stack.getTag();
        if (tag != null && tag.isEmpty()) {
            stack.setTag(null);
        }
    }
}
