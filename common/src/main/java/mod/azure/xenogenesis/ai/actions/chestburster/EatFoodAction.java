package mod.azure.xenogenesis.ai.actions.chestburster;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

import mod.azure.xenogenesis.CommonMod;
import mod.azure.xenogenesis.ai.core.Action;
import mod.azure.xenogenesis.ai.core.ActionStatus;
import mod.azure.xenogenesis.ai.core.Blackboard;
import mod.azure.xenogenesis.ai.core.Cooldowns;
import mod.azure.xenogenesis.ai.util.AiDebugUtils;
import mod.azure.xenogenesis.ai.util.MovementUtils;
import mod.azure.xenogenesis.entities.chestburster.ChestbursterEntity;

public class EatFoodAction implements Action<ChestbursterEntity> {

    private final double searchRange;

    private final double eatDistanceSqr;

    private final double speed;

    private final int priority;

    private final int animationTicks;

    private final Consumer<ChestbursterEntity> eatAnimation;

    private final int[] steerBias = { 0 };

    private ItemEntity food;

    private int eatTicks;

    private boolean eatingStarted;

    private boolean consumed;

    public EatFoodAction(
        double searchRange,
        double eatDistance,
        double speed,
        int priority,
        int animationTicks,
        Consumer<ChestbursterEntity> eatAnimation
    ) {
        this.searchRange = searchRange;
        this.eatDistanceSqr = eatDistance * eatDistance;
        this.speed = speed;
        this.priority = priority;
        this.animationTicks = animationTicks;
        this.eatAnimation = eatAnimation;
    }

    @Override
    public void start(ChestbursterEntity mob, Blackboard blackboard, Cooldowns cooldowns) {
        this.food = findNearestCookedFood(mob);
        this.eatTicks = 0;
        this.eatingStarted = false;
        this.consumed = false;
    }

    @Override
    public ActionStatus tick(ChestbursterEntity mob, Blackboard blackboard, Cooldowns cooldowns) {
        if (!this.eatingStarted) {
            if (this.food == null || !this.food.isAlive() || this.food.getItem().isEmpty()) {
                return ActionStatus.SUCCESS;
            }

            if (mob.distanceToSqr(this.food) > this.eatDistanceSqr) {
                moveTowardFood(mob);
                return ActionStatus.RUNNING;
            }

            this.eatingStarted = true;
            this.eatTicks = 0;
            mob.setDeltaMovement(0.0D, mob.getDeltaMovement().y, 0.0D);
            mob.hasImpulse = true;
            this.eatAnimation.accept(mob);
        }

        this.eatTicks++;
        mob.setDeltaMovement(0.0D, mob.getDeltaMovement().y, 0.0D);
        mob.hasImpulse = true;

        if (!this.consumed && this.eatTicks >= 20) {
            if (this.food != null && this.food.isAlive() && !this.food.getItem().isEmpty()) {
                consumeOneFoodItem(mob, this.food);
                mob.playSound(SoundEvents.GENERIC_EAT);
                mob.grow(mob, CommonMod.getConfig().entityConfigs.chestbursterConfigs.chestbursterFoodGrowthValue);
            }
            this.consumed = true;
        }

        if (this.eatTicks >= animationTicks) {
            return ActionStatus.SUCCESS;
        }

        return ActionStatus.RUNNING;
    }

    @Override
    public void stop(ChestbursterEntity mob, Blackboard blackboard, ActionStatus reason) {
        mob.setDeltaMovement(0.0D, mob.getDeltaMovement().y, 0.0D);
        mob.hasImpulse = true;
        this.food = null;
        this.eatTicks = 0;
        this.eatingStarted = false;
        this.consumed = false;
    }

    @Override
    public boolean isInterruptible() {
        return false;
    }

    @Override
    public int priority() {
        return this.priority;
    }

    public boolean canStart(ChestbursterEntity mob) {
        return findNearestCookedFood(mob) != null;
    }

    private void moveTowardFood(ChestbursterEntity mob) {
        var destination = this.food.blockPosition();
        var destVec = Vec3.atBottomCenterOf(destination);
        var horizontal = new Vec3(
            destVec.x - mob.getX(),
            0.0D,
            destVec.z - mob.getZ()
        );

        if (horizontal.lengthSqr() < 0.0001D) {
            mob.setDeltaMovement(
                mob.getDeltaMovement().x * 0.4D,
                mob.getDeltaMovement().y,
                mob.getDeltaMovement().z * 0.4D
            );
            return;
        }

        var movement = MovementUtils.steerAwayFromDangerEntities(
            mob,
            horizontal.normalize().scale(speed)
        );
        var safe = MovementUtils.findSafeMovement(mob, movement, steerBias);

        if (safe.equals(Vec3.ZERO)) {
            mob.setDeltaMovement(0.0D, mob.getDeltaMovement().y, 0.0D);
            mob.hasImpulse = false;
            return;
        }

        mob.setDeltaMovement(safe.x, mob.getDeltaMovement().y, safe.z);
        mob.hasImpulse = true;

        var dx = destination.getX() + 0.5D - mob.getX();
        var dz = destination.getZ() + 0.5D - mob.getZ();
        var yaw = (float) (Math.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.yHeadRot = yaw;

        AiDebugUtils.sendParticlePath(mob, mob.position(), destVec);
    }

    private ItemEntity findNearestCookedFood(ChestbursterEntity mob) {
        var items = mob.level()
            .getEntitiesOfClass(
                ItemEntity.class,
                mob.getBoundingBox().inflate(this.searchRange),
                item -> item.isAlive() && !item.getItem().isEmpty() && isCookedFood(item.getItem())
            );

        ItemEntity nearest = null;
        var nearestDist = Double.MAX_VALUE;
        for (var item : items) {
            double d = mob.distanceToSqr(item);
            if (d < nearestDist) {
                nearestDist = d;
                nearest = item;
            }
        }
        return nearest;
    }

    private void consumeOneFoodItem(ChestbursterEntity mob, ItemEntity itemEntity) {
        if (mob.level().isClientSide())
            return;
        var stack = itemEntity.getItem();
        stack.shrink(1);
        if (stack.isEmpty())
            itemEntity.discard();
        else
            itemEntity.setItem(stack);
    }

    // TODO: Move to tag
    private boolean isCookedFood(ItemStack stack) {
        return stack.is(Items.COOKED_BEEF)
            || stack.is(Items.COOKED_CHICKEN)
            || stack.is(Items.COOKED_COD)
            || stack.is(Items.COOKED_MUTTON)
            || stack.is(Items.COOKED_PORKCHOP)
            || stack.is(Items.COOKED_RABBIT)
            || stack.is(Items.COOKED_SALMON)
            || stack.is(Items.BAKED_POTATO);
    }
}
