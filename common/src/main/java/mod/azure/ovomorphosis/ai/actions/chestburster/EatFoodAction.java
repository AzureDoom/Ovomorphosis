package mod.azure.ovomorphosis.ai.actions.chestburster;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.ai.core.*;
import mod.azure.ovomorphosis.ai.util.AiDebugUtils;
import mod.azure.ovomorphosis.ai.util.MovementUtils;
import mod.azure.ovomorphosis.entities.chestburster.ChestbursterEntity;
import mod.azure.ovomorphosis.util.ModTags;

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

    private double bestDistSqr = Double.MAX_VALUE;

    private int noProgressTicks;

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
        cooldowns.set(AiKeys.PASSIVE_DECISION, 1);
        this.food = findNearestCookedFood(mob, ignoredFood(blackboard));
        this.eatTicks = 0;
        this.eatingStarted = false;
        this.consumed = false;
    }

    @Override
    public ActionOutcome tick(ChestbursterEntity mob, Blackboard blackboard, Cooldowns cooldowns) {
        if (!this.eatingStarted) {
            if (this.food == null || !this.food.isAlive() || this.food.getItem().isEmpty()) {
                return ActionOutcome.SUCCESS;
            }

            var d = mob.distanceToSqr(this.food);

            if (d <= this.eatDistanceSqr) {
                beginEating(mob);
            } else {
                if (d < this.bestDistSqr - 0.02D) {
                    this.bestDistSqr = d;
                    this.noProgressTicks = 0;
                } else {
                    this.noProgressTicks++;
                }

                if (this.noProgressTicks > 20 && d <= 1.44) {
                    beginEating(mob);
                } else if (this.noProgressTicks > 40) {
                    ignoredFood(blackboard).put(this.food.getId(), mob.level().getGameTime() + 200);
                    cooldowns.set(AiKeys.PASSIVE_DECISION, 60);
                    return ActionOutcome.SUCCESS;
                } else {
                    moveTowardFood(mob);
                    return ActionOutcome.RUNNING;
                }
            }
        }

        this.eatTicks++;
        mob.setDeltaMovement(0.0D, mob.getDeltaMovement().y, 0.0D);
        mob.hasImpulse = true;

        if (!this.consumed && this.eatTicks >= 20) {
            if (this.food != null && this.food.isAlive() && !this.food.getItem().isEmpty()) {
                consumeOneFoodItem(mob, this.food);
                if (this.food.getItem().is(ModTags.POTIONS)) {
                    mob.playSound(SoundEvents.GLASS_BREAK);
                } else {
                    mob.playSound(SoundEvents.GENERIC_EAT);
                }
            }
            this.consumed = true;
        }

        if (this.eatTicks >= animationTicks) {
            return ActionOutcome.SUCCESS;
        }

        return ActionOutcome.RUNNING;
    }

    @Override
    public void stop(ChestbursterEntity mob, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {
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

    private void beginEating(ChestbursterEntity mob) {
        this.eatingStarted = true;
        this.eatTicks = 0;
        mob.setDeltaMovement(0.0D, mob.getDeltaMovement().y, 0.0D);
        mob.hasImpulse = true;
        this.eatAnimation.accept(mob);
    }

    public boolean canStart(ChestbursterEntity mob, Blackboard blackboard) {
        return findNearestCookedFood(mob, ignoredFood(blackboard)) != null;
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

    private ItemEntity findNearestCookedFood(ChestbursterEntity mob, Map<Integer, Long> ignored) {
        var now = mob.level().getGameTime();
        ignored.values().removeIf(expiry -> expiry <= now);

        var items = mob.level()
            .getEntitiesOfClass(
                ItemEntity.class,
                mob.getBoundingBox().inflate(this.searchRange),
                item -> item.isAlive()
                    && !item.getItem().isEmpty()
                    && item.getItem().is(ModTags.BURSTER_FOOD)
                    && !ignored.containsKey(item.getId())
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
        if (stack.isEdible()) {
            var food = stack.getItem().getFoodProperties();
            var nutrition = food != null ? food.getNutrition() : 1;

            var growthValue =
                nutrition + CommonMod.getConfig().entityConfigs.chestbursterConfigs.chestbursterFoodGrowthValue;

            mob.grow(mob, growthValue);

            stack.finishUsingItem(mob.level(), mob);
        } else {
            if (stack.is(ModTags.POTIONS)) {
                stack.finishUsingItem(mob.level(), mob);
                stack.shrink(1);
            } else {
                stack.shrink(1);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, Long> ignoredFood(Blackboard blackboard) {
        var map = blackboard.get(AiKeys.IGNORED_FOOD, Map.class);
        if (map == null) {
            map = new HashMap<Integer, Long>();
            blackboard.set(AiKeys.IGNORED_FOOD, map);
        }
        return (Map<Integer, Long>) map;
    }
}
