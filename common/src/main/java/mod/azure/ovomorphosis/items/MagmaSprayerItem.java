package mod.azure.ovomorphosis.items;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

import mod.azure.ovomorphosis.entities.runner.RunnerEntity;
import mod.azure.ovomorphosis.entities.xenomorph.XenomorphEntity;

public class MagmaSprayerItem extends Item {

    private static final String FUEL_TAG = "Fuel";

    private static final int MAX_FUEL = 100;

    private static final int FUEL_PER_REFILL = 25;

    private static final int RANGE = 6;

    private static final int TICK_INTERVAL = 4;

    private static final double CONE_DOT = 0.75;

    private static final int MODEL_NORMAL = 0;

    private static final int MODEL_ON = 1;

    public MagmaSprayerItem(Item.Properties itemProperties) {
        super(itemProperties);
    }

    @Override
    public @NonNull InteractionResult use(
        @NonNull Level level,
        @NonNull Player player,
        @NonNull InteractionHand hand
    ) {
        var stack = player.getItemInHand(hand);

        if (player.isShiftKeyDown()) {
            return tryRefill(level, player, stack);
        }

        if (noFuel(stack)) {
            setSprayerModel(stack, MODEL_NORMAL);
            return InteractionResult.FAIL;
        }

        setSprayerModel(stack, MODEL_ON);
        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    @Override
    public boolean releaseUsing(
        @NonNull ItemStack stack,
        @NonNull Level level,
        @NonNull LivingEntity entity,
        int timeLeft
    ) {
        setSprayerModel(stack, MODEL_NORMAL);
        return super.releaseUsing(stack, level, entity, timeLeft);
    }

    @Override
    public void inventoryTick(
        @NonNull ItemStack stack,
        @NonNull ServerLevel level,
        @NonNull Entity entity,
        @Nullable EquipmentSlot slot
    ) {
        if (
            entity instanceof LivingEntity livingEntity
                && stack.has(DataComponents.CUSTOM_MODEL_DATA)
                && livingEntity.getUseItem() != stack
        ) {
            setSprayerModel(stack, MODEL_NORMAL);
        }

        super.inventoryTick(stack, level, entity, slot);
    }

    private InteractionResult tryRefill(Level level, Player player, ItemStack sprayer) {
        var currentFuel = getFuel(sprayer);

        if (currentFuel >= MAX_FUEL) {
            return InteractionResult.FAIL;
        }

        var fuelStack = findFuel(player);

        if (fuelStack.isEmpty()) {
            return InteractionResult.FAIL;
        }

        if (!level.isClientSide()) {
            if (!player.getAbilities().instabuild) {
                fuelStack.shrink(1);
            }

            setFuel(sprayer, currentFuel + FUEL_PER_REFILL);

            level.playSound(
                null,
                player.blockPosition(),
                SoundEvents.BOTTLE_FILL,
                SoundSource.PLAYERS,
                0.7F,
                0.8F + level.getRandom().nextFloat() * 0.3F
            );
        }

        return InteractionResult.SUCCESS;
    }

    private ItemStack findFuel(Player player) {
        for (var i = 0; i < player.getInventory().getContainerSize(); i++) {
            var stack = player.getInventory().getItem(i);

            if (stack.is(Items.MAGMA_CREAM)) {
                return stack;
            }
        }

        return ItemStack.EMPTY;
    }

    @Override
    public void onUseTick(
        @NonNull Level level,
        @NonNull LivingEntity entity,
        @NonNull ItemStack stack,
        int remainingUseDuration
    ) {
        if (!(entity instanceof Player player))
            return;

        if (noFuel(stack)) {
            player.stopUsingItem();
            return;
        }

        int elapsed = getUseDuration(stack, entity) - remainingUseDuration;
        if (elapsed % TICK_INTERVAL != 0)
            return;

        if (!(level instanceof ServerLevel serverLevel)) {
            spawnFlameParticles(level, player);
            return;
        }

        level.playSound(
            null,
            player.blockPosition(),
            SoundEvents.FIRE_AMBIENT,
            SoundSource.PLAYERS,
            0.4F,
            0.8F + level.getRandom().nextFloat() * 0.4F
        );

        var eyePos = player.getEyePosition();
        var lookVec = player.getLookAngle();

        serverLevel.getEntitiesOfClass(
            LivingEntity.class,
            new AABB(player.blockPosition()).inflate(RANGE),
            e -> e != player && e.isAlive()
        ).forEach(e -> {
            var toEntity = e.getEyePosition().subtract(eyePos).normalize();
            if (toEntity.dot(lookVec) >= CONE_DOT) {
                e.setRemainingFireTicks(120);

                if (e instanceof XenomorphEntity || e instanceof RunnerEntity) {
                    var push = lookVec.scale(0.6).add(0, 0.2, 0);
                    e.setDeltaMovement(e.getDeltaMovement().add(push));
                    e.syncVelocity = true;
                }
            }
        });

        for (var i = 1; i <= RANGE; i++) {
            var checkPos = BlockPos.containing(eyePos.add(lookVec.scale(i)));
            var bs = serverLevel.getBlockState(checkPos);

            if (!bs.isAir()) {
                var facePos = checkPos.relative(
                    Direction.getNearest(
                        (int) -lookVec.x,
                        (int) -lookVec.y,
                        (int) -lookVec.z,
                        Direction.UP
                    )
                );
                if (
                    serverLevel.getBlockState(facePos).isAir()
                        && BaseFireBlock.canBePlacedAt(serverLevel, facePos, player.getDirection())
                ) {
                    serverLevel.setBlockAndUpdate(
                        facePos,
                        BaseFireBlock.getState(serverLevel, facePos)
                    );
                }
                break;
            }
        }

        if (!player.getAbilities().instabuild) {
            consumeFuel(stack);
        }

        if (player.getRandom().nextFloat() < 0.25F) {
            stack.hurtAndBreak(1, player, player.getEquipmentSlotForItem(stack));
        }
    }

    private void spawnFlameParticles(Level level, Player player) {
        var eyePos = player.getEyePosition();
        var lookVec = player.getLookAngle();
        var rng = level.getRandom();

        for (var i = 0; i <= RANGE * 3; i++) {
            var distance = i / 3.0D;
            var spread = distance * 0.08D;

            var pos = eyePos.add(lookVec.scale(i))
                .add(
                    (rng.nextDouble() - 0.5D) * spread,
                    (rng.nextDouble() - 0.5D) * spread,
                    (rng.nextDouble() - 0.5D) * spread
                );

            level.addParticle(
                ParticleTypes.FLAME,
                pos.x,
                pos.y,
                pos.z,
                lookVec.x * 0.15D,
                lookVec.y * 0.15D,
                lookVec.z * 0.15D
            );

            if (rng.nextFloat() < 0.3F) {
                level.addParticle(
                    ParticleTypes.SMOKE,
                    pos.x,
                    pos.y,
                    pos.z,
                    lookVec.x * 0.05D,
                    lookVec.y * 0.05D + 0.02D,
                    lookVec.z * 0.05D
                );
            }
        }
    }

    @Override
    public @NonNull ItemUseAnimation getUseAnimation(@NonNull ItemStack stack) {
        return ItemUseAnimation.NONE;
    }

    @Override
    public int getUseDuration(@NonNull ItemStack stack, @NonNull LivingEntity entity) {
        return APPROXIMATELY_INFINITE_USE_DURATION;
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
            Component.translatable("item.ovomorphosis.magma_sprayer.tooltip")
                .withStyle(ChatFormatting.GRAY)
        );
        builder.accept(
            Component.translatable("item.ovomorphosis.magma_sprayer.tooltip.refill")
                .withStyle(ChatFormatting.GRAY)
        );
        var fuel = getFuel(itemStack);
        builder.accept(
            Component.translatable(
                "item.ovomorphosis.magma_sprayer.tooltip.fuel",
                fuel,
                MAX_FUEL
            ).withStyle(fuel < 20 ? ChatFormatting.RED : ChatFormatting.YELLOW)
        );
        var durability = itemStack.getMaxDamage() - itemStack.getDamageValue();
        builder.accept(
            Component.translatable(
                "item.ovomorphosis.magma_sprayer.tooltip.condition",
                durability,
                itemStack.getMaxDamage()
            ).withStyle(ChatFormatting.DARK_GRAY)
        );
        super.appendHoverText(itemStack, context, display, builder, tooltipFlag);
    }

    /**
     * A stack with no stored fuel value is treated as full. This replaces the old verifyComponentsAfterLoad hook, which
     * no longer exists on Item.
     */
    private int getFuel(ItemStack stack) {
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return Math.clamp(tag.getIntOr(FUEL_TAG, MAX_FUEL), 0, MAX_FUEL);
    }

    private void setFuel(ItemStack stack, int fuel) {
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putInt(FUEL_TAG, Math.clamp(fuel, 0, MAX_FUEL));
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private boolean noFuel(ItemStack stack) {
        return getFuel(stack) <= 0;
    }

    private void consumeFuel(ItemStack stack) {
        setFuel(stack, getFuel(stack) - 1);
    }

    private static void setSprayerModel(ItemStack stack, int customModelData) {
        if (customModelData <= 0) {
            stack.remove(DataComponents.CUSTOM_MODEL_DATA);
            return;
        }

        stack.set(
            DataComponents.CUSTOM_MODEL_DATA,
            new CustomModelData(List.of((float) customModelData), List.of(), List.of(), List.of())
        );
    }
}
