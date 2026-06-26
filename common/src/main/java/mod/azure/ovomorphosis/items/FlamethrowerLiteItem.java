package mod.azure.ovomorphosis.items;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import mod.azure.ovomorphosis.entities.AbstractAlienEntity;

public class FlamethrowerLiteItem extends Item {

    private static final int RANGE = 6;

    private static final int MAX_DAMAGE = 100;

    private static final int TICK_INTERVAL = 4;

    private static final int FIRE_TICKS = 60;

    private static final double CONE_DOT = 0.75;

    public FlamethrowerLiteItem() {
        super(new Item.Properties().durability(MAX_DAMAGE).stacksTo(1));
    }

    @Override
    public @NotNull InteractionResultHolder<ItemStack> use(
        @NotNull Level level,
        @NotNull Player player,
        @NotNull InteractionHand hand
    ) {
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
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
                e.setRemainingFireTicks(FIRE_TICKS / 20);

                if (e instanceof AbstractAlienEntity) {
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
                    net.minecraft.core.Direction.getNearest(
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

        stack.hurtAndBreak(
            1,
            player,
            s -> player.broadcastBreakEvent(player.getUsedItemHand())
        );
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
        ItemStack stack,
        @Nullable Level level,
        List<Component> components,
        @NotNull TooltipFlag isAdvanced
    ) {
        components.add(
            Component.translatable("item.ovomorphosis.flamethrower_lite.tooltip")
                .withStyle(ChatFormatting.GRAY)
        );
        var fuel = stack.getMaxDamage() - stack.getDamageValue();
        components.add(
            Component.literal("Fuel: " + fuel + "/" + stack.getMaxDamage())
                .withStyle(fuel < 20 ? ChatFormatting.RED : ChatFormatting.YELLOW)
        );
        super.appendHoverText(stack, level, components, isAdvanced);
    }

    @Override
    public boolean isEnchantable(@NotNull ItemStack stack) {
        return false;
    }
}
