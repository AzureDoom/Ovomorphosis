package mod.azure.ovomorphosis.items;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import mod.azure.ovomorphosis.util.ModTags;

public class MotionTrackerItem extends Item {

    private static final int WALL_THRESHOLD = 3;

    private static final int COOLDOWN_TICKS = 20;

    private static final int LIT_MODEL_DATA = 1;

    public MotionTrackerItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    @SuppressWarnings("deprecation")
    public @NonNull InteractionResult use(
        @NonNull Level level,
        @NonNull Player player,
        @NonNull InteractionHand hand
    ) {
        var stack = player.getItemInHand(hand);

        if (player.getCooldowns().isOnCooldown(stack)) {
            return InteractionResult.FAIL;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.CONSUME;
        }

        var nearby = serverLevel.getEntitiesOfClass(
            PathfinderMob.class,
            new AABB(player.blockPosition()).inflate(24),
            e -> e.getType().builtInRegistryHolder().is(ModTags.MOTION_TRACKABLE)
                && e.isAlive()
                && !e.isInvisible()
                && e.getDeltaMovement().lengthSqr() > 0.025
        );

        if (nearby.isEmpty()) {
            player.sendOverlayMessage(
                Component.translatable("item.ovomorphosis.motion_tracker.clear")
                    .withStyle(ChatFormatting.GREEN)
            );
        } else {
            nearby.sort(Comparator.comparingDouble(e -> e.distanceToSqr(player)));

            var results = new StringBuilder();
            var reported = 0;

            for (var xeno : nearby) {
                if (reported >= 3)
                    break;

                var wallBlocks = countWallBlocksBetween(
                    serverLevel,
                    player,
                    player.getEyePosition(),
                    xeno.getEyePosition()
                );
                var obscured = wallBlocks >= WALL_THRESHOLD;

                var dist = player.distanceTo(xeno);

                String distStr;
                if (obscured) {
                    var band = ((int) (dist / 8)) * 8;
                    distStr = "~" + band + "-" + (band + 8) + "m?";
                } else {
                    distStr = String.format("%.1fm", dist);
                }

                var dir = xeno.position().subtract(player.position());
                var cardinal = toCardinal(dir);

                results.append(distStr).append(" ").append(cardinal);
                if (obscured)
                    results.append(" [WALL]");
                results.append("  ");
                reported++;
            }

            if (nearby.size() > 3) {
                results.append("+").append(nearby.size() - 3).append(" more");
            }

            player.sendOverlayMessage(
                Component.literal("▶ " + results.toString().trim())
                    .withStyle(ChatFormatting.RED)
            );

            level.playSound(
                null,
                player.blockPosition(),
                SoundEvents.SCULK_CLICKING,
                SoundSource.PLAYERS,
                0.6F,
                2.0F
            );
        }

        stack.hurtAndBreak(1, player, hand.asEquipmentSlot());
        if (!stack.isEmpty())
            stack.set(
                DataComponents.CUSTOM_MODEL_DATA,
                new CustomModelData(List.of((float) LIT_MODEL_DATA), List.of(), List.of(), List.of())
            );
        player.getCooldowns().addCooldown(stack, COOLDOWN_TICKS);

        return InteractionResult.CONSUME;
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
    public void inventoryTick(
        @NonNull ItemStack stack,
        @NonNull ServerLevel level,
        @NonNull Entity entity,
        @Nullable EquipmentSlot slot
    ) {
        if (
            stack.has(DataComponents.CUSTOM_MODEL_DATA)
                && entity instanceof Player player
                && !player.getCooldowns().isOnCooldown(stack)
        ) {
            stack.remove(DataComponents.CUSTOM_MODEL_DATA);
        }
    }

    /**
     * Counts solid blocks between two points using raycasting.
     */
    private static int countWallBlocksBetween(ServerLevel level, Entity viewer, Vec3 from, Vec3 to) {
        var count = 0;
        var current = from;
        var direction = to.subtract(from).normalize();
        var totalDist = from.distanceTo(to);
        var stepped = 0D;

        while (stepped < totalDist) {
            stepped += 1.0D;
            current = from.add(direction.scale(stepped));

            var result = level.clip(
                new ClipContext(
                    current.subtract(direction.scale(0.1)),
                    current,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    viewer
                )
            );

            if (result.getType() == HitResult.Type.BLOCK) {
                var bs = level.getBlockState(result.getBlockPos());
                if (bs.isSolidRender()) {
                    count++;
                }
            }
        }
        return count;
    }

    private static String toCardinal(Vec3 dir) {
        var ax = Math.abs(dir.x);
        var az = Math.abs(dir.z);
        var ay = Math.abs(dir.y);

        if (ay > ax && ay > az) {
            return dir.y > 0 ? "↑" : "↓";
        }
        if (ax > az) {
            return dir.x > 0 ? "E" : "W";
        }
        return dir.z > 0 ? "S" : "N";
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
            Component.translatable("item.ovomorphosis.motion_tracker.tooltip")
                .withStyle(ChatFormatting.GRAY)
        );
        super.appendHoverText(itemStack, context, display, builder, tooltipFlag);
    }
}
