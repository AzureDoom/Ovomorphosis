package mod.azure.ovomorphosis.entities.chestburster;

import net.minecraft.world.entity.LivingEntity;

import mod.azure.ovomorphosis.ai.actions.*;
import mod.azure.ovomorphosis.ai.actions.chestburster.EatFoodAction;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.BehaviorNode;
import mod.azure.ovomorphosis.ai.core.BehaviorResult;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.goap.PlannedGoal;

public class ChestbursterTree {

    public static BehaviorNode<ChestbursterEntity> create(EatFoodAction eatAction) {
        var idle = new IdleAction<ChestbursterEntity>(40, 100, 1);

        var wander = new WanderAction<ChestbursterEntity>(
            0.1D,
            9,
            6.0D,
            60,
            160,
            true
        );

        var flee = new FleeAction<ChestbursterEntity>(
            0.18D,
            20,
            70
        );

        var fleeExplosive = new ExplosiveFleeAction<ChestbursterEntity>(
            0.18D,
            10,
            20,
            120
        );

        var swim = new SwimAction<ChestbursterEntity>(200);

        return (chestburster, blackboard, cooldowns) -> {

            if (fleeExplosive.hasNearbyExplosive(chestburster)) {
                return BehaviorResult.run(fleeExplosive, 120);
            }

            if (chestburster.isInWater() || chestburster.isInLava()) {
                return BehaviorResult.run(swim, 200);
            }

            @SuppressWarnings("unchecked")
            var goal = (PlannedGoal<ChestbursterEntity>) blackboard.get(AiKeys.ACTIVE_GOAL, PlannedGoal.class);
            var goalType = goal != null ? goal.type() : AiGoalType.NONE;

            return switch (goalType) {

                case HIDE -> {
                    var threat = blackboard.get(AiKeys.TARGET, LivingEntity.class);
                    if (threat != null && threat.isAlive()) {
                        yield BehaviorResult.run(flee, flee.priority());
                    }
                    yield BehaviorResult.run(idle, 8);
                }

                case FIND_FOOD -> {
                    if (eatAction.canStart(chestburster)) {
                        yield BehaviorResult.run(eatAction, eatAction.priority());
                    }
                    yield BehaviorResult.run(idle, 8);
                }

                default -> {
                    if (!cooldowns.isOnCooldown(AiKeys.PASSIVE_DECISION)) {
                        cooldowns.set(AiKeys.PASSIVE_DECISION, 180);
                        if (chestburster.getRandom().nextFloat() < 0.1F) {
                            yield BehaviorResult.run(wander, 9);
                        }
                    }
                    yield BehaviorResult.run(idle, 8);
                }
            };
        };
    }
}
