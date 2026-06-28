package mod.azure.ovomorphosis.items;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import mod.azure.ovomorphosis.entities.runner.RunnerEntity;
import mod.azure.ovomorphosis.entities.xenomorph.XenomorphEntity;

public class MagmaSprayerItem extends Item {

    private static final String FUEL_TAG = "Fuel";

    private static final int RANGE = 6;

    private static final int MAX_DAMAGE = 100;

    private static final int TICK_INTERVAL = 4;

    private static final double CONE_DOT = 0.75;

    public MagmaSprayerItem() {
        super(new Item.Properties().durability(MAX_DAMAGE));
    }

    @Override
    public @NotNull InteractionResultHolder<ItemStack> use(
        @NotNull Level level,
        @NotNull Player player,
        @NotNull InteractionHand hand
    ) {
        var stack = player.getItemInHand(hand);

        if (player.isShiftKeyDown()) {
            return tryRefill(level, player, stack);
        }

        if (noFuel(stack)) {
            return InteractionResultHolder.fail(stack);
        }

        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    private InteractionResultHolder<ItemStack> tryRefill(
        Level level,
        Player player,
        ItemStack incinerator
    ) {
        var currentFuel = getFuel(incinerator);

        if (currentFuel >= 100) {
            return InteractionResultHolder.fail(incinerator);
        }

        var fuelStack = findFuel(player);

        if (fuelStack.isEmpty()) {
            return InteractionResultHolder.fail(incinerator);
        }

        if (!level.isClientSide()) {
            if (!player.getAbilities().instabuild) {
                fuelStack.shrink(1);
            }

            setFuel(incinerator, currentFuel + 25);

            level.playSound(
                null,
                player.blockPosition(),
                SoundEvents.BOTTLE_FILL,
                SoundSource.PLAYERS,
                0.7F,
                0.8F + level.random.nextFloat() * 0.3F
            );
        }

        return InteractionResultHolder.sidedSuccess(incinerator, level.isClientSide());
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
        @NotNull Level level,
        @NotNull LivingEntity entity,
        @NotNull ItemStack stack,
        int remainingUseDuration
    ) {
        if (!(entity instanceof Player player))
            return;

        if (noFuel(stack)) {
            player.stopUsingItem();
            return;
        }

        int elapsed = getUseDuration(stack) - remainingUseDuration;
        if (elapsed % TICK_INTERVAL != 0)
            return;

        if (level.isClientSide()) {
            spawnFlameParticles(level, player);
            return;
        }

        var serverLevel = (ServerLevel) level;

        level.playSound(
            null,
            player.blockPosition(),
            SoundEvents.FIRE_AMBIENT,
            SoundSource.PLAYERS,
            0.4F,
            0.8F + level.random.nextFloat() * 0.4F
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
                    e.hurtMarked = true;
                }
            }
        });

        for (var i = 1; i <= RANGE; i++) {
            var checkPos = BlockPos.containing(eyePos.add(lookVec.scale(i)));
            var bs = serverLevel.getBlockState(checkPos);

            if (!bs.isAir()) {
                var facePos = checkPos.relative(
                    Direction.getNearest(
                        (float) -lookVec.x,
                        (float) -lookVec.y,
                        (float) -lookVec.z
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

        consumeFuel(stack);

        if (player.getRandom().nextFloat() < 0.25F) {
            stack.hurtAndBreak(
                1,
                player,
                s -> player.broadcastBreakEvent(player.getUsedItemHand())
            );
        }
    }

    private void spawnFlameParticles(Level level, Player player) {
        var eyePos = player.getEyePosition();
        var lookVec = player.getLookAngle();
        var rng = level.random;

        for (var i = 1; i <= RANGE; i++) {
            var spread = i * 0.08;
            var pos = eyePos.add(lookVec.scale(i))
                .add(
                    (rng.nextDouble() - 0.5) * spread,
                    (rng.nextDouble() - 0.5) * spread,
                    (rng.nextDouble() - 0.5) * spread
                );

            level.addParticle(
                ParticleTypes.FLAME,
                pos.x,
                pos.y,
                pos.z,
                lookVec.x * 0.15,
                lookVec.y * 0.15,
                lookVec.z * 0.15
            );

            if (rng.nextFloat() < 0.3f) {
                level.addParticle(
                    ParticleTypes.SMOKE,
                    pos.x,
                    pos.y,
                    pos.z,
                    lookVec.x * 0.05,
                    lookVec.y * 0.05 + 0.02,
                    lookVec.z * 0.05
                );
            }
        }
    }

    @Override
    public @NotNull UseAnim getUseAnimation(@NotNull ItemStack stack) {
        return UseAnim.BOW;
    }

    @Override
    public int getUseDuration(@NotNull ItemStack stack) {
        return 72000;
    }

    @Override
    public void appendHoverText(
        @NotNull ItemStack stack,
        Level level,
        @NotNull List<Component> components,
        @NotNull TooltipFlag flag
    ) {
        components.add(
            Component.translatable("item.ovomorphosis.magma_sprayer.tooltip")
                .withStyle(ChatFormatting.GRAY)
        );
        components.add(
                Component.translatable("item.ovomorphosis.magma_sprayer.tooltip.refill")
                        .withStyle(ChatFormatting.GRAY)
        );
        var fuel = getFuel(stack);
        components.add(
            Component.literal("Fuel: " + fuel + "/" + 100)
                .withStyle(fuel < 20 ? ChatFormatting.RED : ChatFormatting.YELLOW)
        );
        var durability = stack.getMaxDamage() - stack.getDamageValue();
        components.add(
            Component.literal("Condition: " + durability + "/" + stack.getMaxDamage())
                .withStyle(ChatFormatting.DARK_GRAY)
        );
        super.appendHoverText(stack, level, components, flag);
    }

    @Override
    public boolean isEnchantable(@NotNull ItemStack stack) {
        return false;
    }

    @Override
    public boolean isValidRepairItem(@NotNull ItemStack stack, ItemStack repairCandidate) {
        return repairCandidate.is(Items.IRON_INGOT) || super.isValidRepairItem(stack, repairCandidate);
    }

    private int getFuel(ItemStack stack) {
        var tag = stack.getTag();

        if (tag == null || !tag.contains(FUEL_TAG)) {
            return 100;
        }

        return Mth.clamp(tag.getInt(FUEL_TAG), 0, 100);
    }

    private void setFuel(ItemStack stack, int fuel) {
        var tag = stack.getOrCreateTag();
        tag.putInt(FUEL_TAG, Mth.clamp(fuel, 0, 100));
    }

    private boolean noFuel(ItemStack stack) {
        return getFuel(stack) <= 0;
    }

    private void consumeFuel(ItemStack stack) {
        setFuel(stack, getFuel(stack) - 1);
    }
}
