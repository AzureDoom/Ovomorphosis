package mod.azure.ovomorphosis.items;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;

import mod.azure.ovomorphosis.util.ModTags;

public class MotionTrackerItem extends Item {

    private static final int RANGE = 24;

    private static final int WALL_THRESHOLD = 3;

    private static final int COOLDOWN_TICKS = 40;

    private static final int MAX_DAMAGE = 64;

    private static final int LIT_MODEL_DATA = 1;

    public MotionTrackerItem() {
        super(new Item.Properties().durability(MAX_DAMAGE));
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
            return InteractionResultHolder.consume(stack);
        }

        var serverLevel = (ServerLevel) level;
        var serverPlayer = (ServerPlayer) player;

        var nearby = serverLevel.getEntitiesOfClass(
            PathfinderMob.class,
            new AABB(player.blockPosition()).inflate(24),
            e -> e.getType().is(ModTags.MOTION_TRACKABLE)
                && e.isAlive()
                && !e.isInvisible()
                && e.getDeltaMovement().lengthSqr() > 0.025
        );

        if (nearby.isEmpty()) {
            player.displayClientMessage(
                Component.translatable("item.ovomorphosis.motion_tracker.clear")
                    .withStyle(ChatFormatting.GREEN),
                true
            );
        } else {
            nearby.sort(Comparator.comparingDouble(e -> e.distanceToSqr(player)));

            var results = new StringBuilder();
            var reported = 0;

            for (var xeno : nearby) {
                if (reported >= 3)
                    break;

                var wallBlocks = countWallBlocksBetween(serverLevel, player.getEyePosition(), xeno.getEyePosition());
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

            player.displayClientMessage(
                Component.literal("▶ " + results.toString().trim())
                    .withStyle(ChatFormatting.RED),
                true
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

        stack.hurtAndBreak(1, serverPlayer, s -> player.broadcastBreakEvent(player.getUsedItemHand()));

        var tag = stack.getOrCreateTag();
        tag.putBoolean("lit", true);
        stack.getOrCreateTag().putInt("CustomModelData", LIT_MODEL_DATA);
        player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);

        return InteractionResultHolder.consume(stack);
    }

    @Override
    public @NotNull UseAnim getUseAnimation(@NotNull ItemStack stack) {
        return UseAnim.NONE;
    }

    @Override
    public int getUseDuration(@NotNull ItemStack stack) {
        return 72000;
    }

    @Override
    public void inventoryTick(@NotNull ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level.isClientSide())
            return;

        var tag = stack.getOrCreateTag();
        if (tag.getBoolean("lit") && entity instanceof Player player) {
            if (!player.getCooldowns().isOnCooldown(this)) {
                tag.putBoolean("lit", false);
                stack.getOrCreateTag().putInt("CustomModelData", 0);
            }
        }
    }

    /**
     * Counts solid blocks between two points using raycasting.
     */
    private static int countWallBlocksBetween(ServerLevel level, Vec3 from, Vec3 to) {
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
                    null
                )
            );

            if (result.getType() == HitResult.Type.BLOCK) {
                var bs = level.getBlockState(result.getBlockPos());
                if (bs.isSolidRender(level, result.getBlockPos())) {
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
    public void appendHoverText(
        @NotNull ItemStack stack,
        @Nullable Level level,
        List<Component> components,
        @NotNull TooltipFlag isAdvanced
    ) {
        components.add(
            Component.translatable("item.ovomorphosis.motion_tracker.tooltip")
                .withStyle(ChatFormatting.GRAY)
        );
        super.appendHoverText(stack, level, components, isAdvanced);
    }

    @Override
    public boolean isEnchantable(@NotNull ItemStack stack) {
        return false;
    }
}
