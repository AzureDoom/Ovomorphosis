package mod.azure.ovomorphosis.entities.chestburster;

import com.azure.azurecortex.api.behavior.BehaviorNode;
import com.azure.azurecortex.api.behavior.BehaviorResult;
import com.azure.azurecortex.api.blackboard.CommonBlackboardKeys;
import com.azure.azurecortex.goap.PlannedGoal;

import mod.azure.ovomorphosis.ai.actions.*;
import mod.azure.ovomorphosis.ai.actions.chestburster.EatFoodAction;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;

public class ChestbursterTree {

    public static BehaviorNode<ChestbursterEntity, AiGoalType> create(EatFoodAction eatAction) {
        var idle = new IdleAction<ChestbursterEntity, AiGoalType>(40, 100, 1);

        var wander = new WanderAction<ChestbursterEntity, AiGoalType>(
            0.1D,
            9,
            6.0D,
            60,
            160,
            true
        );

        var flee = new FleeAction<ChestbursterEntity, AiGoalType>(
            0.18D,
            20,
            70
        );

        var fleeExplosive = new ExplosiveFleeAction<ChestbursterEntity, AiGoalType>(
            0.18D,
            10,
            20,
            120
        );

        var fleeFire = new FleeFireAction<ChestbursterEntity, AiGoalType>(110);

        var swim = new SwimAction<ChestbursterEntity, AiGoalType>(200);

        return (chestburster, blackboard, cooldowns) -> {

            if (fleeExplosive.hasNearbyExplosive(chestburster)) {
                return BehaviorResult.run(fleeExplosive, 120);
            }

            if (FleeFireAction.shouldFleefire(chestburster) || chestburster.isOnFire()) {
                return BehaviorResult.run(fleeFire, fleeFire.priority());
            }

            if (chestburster.isInWater() || chestburster.isInLava()) {
                return BehaviorResult.run(swim, 200);
            }

            @SuppressWarnings("unchecked")
            var goal = (PlannedGoal<ChestbursterEntity, AiGoalType>) blackboard.get(CommonBlackboardKeys.ACTIVE_GOAL);
            var goalType = goal != null ? goal.type() : AiGoalType.NONE;

            return switch (goalType) {

                case HIDE -> {
                    var threat = blackboard.get(CommonBlackboardKeys.TARGET);
                    if (threat != null && threat.isAlive()) {
                        yield BehaviorResult.run(flee, flee.priority());
                    }
                    yield BehaviorResult.run(idle, 8);
                }

                case FIND_FOOD -> {
                    if (eatAction.canStart(chestburster, blackboard)) {
                        yield BehaviorResult.run(eatAction, eatAction.priority());
                    }
                    yield BehaviorResult.run(idle, 8);
                }

                default -> {
                    if (!cooldowns.isOnCooldown(CommonBlackboardKeys.PASSIVE_DECISION)) {
                        cooldowns.set(CommonBlackboardKeys.PASSIVE_DECISION, 180);
                        if (chestburster.getRandom().nextFloat() < 0.65F) {
                            yield BehaviorResult.run(wander, 10);
                        }
                        yield BehaviorResult.run(idle, 8);
                    }

                    yield BehaviorResult.run(idle, 8);
                }
            };
        };
    }
}
