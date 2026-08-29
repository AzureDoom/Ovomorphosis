package mod.azure.ovomorphosis.ai.actions.chestburster;

import com.azure.azurecortex.api.action.Action;
import com.azure.azurecortex.api.action.ActionOutcome;
import com.azure.azurecortex.api.action.ActionStatus;
import com.azure.azurecortex.api.blackboard.Blackboard;
import com.azure.azurecortex.api.blackboard.CommonBlackboardKeys;
import com.azure.azurecortex.config.CortexConfig;
import com.azure.azurecortex.navigation.movement.MovementController;
import com.azure.azurecortex.runtime.CooldownTracker;
import com.azure.azurecortex.runtime.CortexDebug;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.ai.core.*;
import mod.azure.ovomorphosis.entities.chestburster.ChestbursterEntity;
import mod.azure.ovomorphosis.util.ModTags;

public class EatFoodAction<G> implements Action<ChestbursterEntity, G> {

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
    public void start(ChestbursterEntity mob, Blackboard blackboard, CooldownTracker cooldowns) {
        cooldowns.set(CommonBlackboardKeys.PASSIVE_DECISION, 1);
        this.food = findNearestCookedFood(mob, ignoredFood(blackboard));
        this.eatTicks = 0;
        this.eatingStarted = false;
        this.consumed = false;
    }

    @Override
    public ActionOutcome<G> tick(ChestbursterEntity mob, Blackboard blackboard, CooldownTracker cooldowns) {
        if (!this.eatingStarted) {
            if (this.food == null || !this.food.isAlive() || this.food.getItem().isEmpty()) {
                return ActionOutcome.success();
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
                    cooldowns.set(CommonBlackboardKeys.PASSIVE_DECISION, 60);
                    return ActionOutcome.success();
                } else {
                    moveTowardFood(mob);
                    return ActionOutcome.running();
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
            return ActionOutcome.success();
        }

        return ActionOutcome.running();
    }

    @Override
    public void stop(ChestbursterEntity mob, Blackboard blackboard, CooldownTracker cooldowns, ActionStatus reason) {
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

        var movement = MovementController.steerAwayFromDangerEntities(
            mob,
            horizontal.normalize().scale(speed)
        );
        var safe = MovementController.findSafeMovement(mob, movement, steerBias);

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

        if (CortexConfig.get().enablePathfindingDebug)
            CortexDebug.sendParticlePath(mob, mob.position(), destVec);
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
        if (itemEntity.getItem().has(DataComponents.FOOD)) {
            var foodComponent = itemEntity.getItem().get(DataComponents.FOOD);
            var growthValue = foodComponent != null ? foodComponent.nutrition() : 1;
            mob.grow(
                mob,
                growthValue + CommonMod.getConfig().entityConfigs.chestbursterConfigs.chestbursterFoodGrowthValue
            );
            itemEntity.getItem().finishUsingItem(mob.level(), mob);
        } else {
            if (stack.is(ModTags.POTIONS)) {
                stack.finishUsingItem(mob.level(), mob);
                stack.consume(1, mob);
            } else {
                stack.consume(1, mob);
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
