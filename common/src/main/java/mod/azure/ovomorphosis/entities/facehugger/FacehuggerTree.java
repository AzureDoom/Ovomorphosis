package mod.azure.ovomorphosis.entities.facehugger;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import mod.azure.ovomorphosis.ai.actions.*;
import mod.azure.ovomorphosis.ai.actions.facehugger.LeapAndAttachAction;
import mod.azure.ovomorphosis.ai.actions.facehugger.RetreatAndHideAction;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.BehaviorNode;
import mod.azure.ovomorphosis.ai.core.BehaviorResult;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.goap.PlannedGoal;

public class FacehuggerTree {

    public static BehaviorNode<FacehuggerEntity> create() {
        var wander = new WanderAction<FacehuggerEntity>(
            0.14D,
            9,
            6.0D,
            60,
            160
        );

        var idle = new IdleAction<FacehuggerEntity>(40, 100, 1);

        var moveToDestination = new MoveToDestinationAction<FacehuggerEntity>(
            0.6D,
            0.32D,
            10,
            true
        );

        var leapAndAttach = new LeapAndAttachAction<>();
        var retreatAndHide = new RetreatAndHideAction();
        var swim = new SwimAction<FacehuggerEntity>(200);
        var fleeFire = new FleeFireAction<FacehuggerEntity>(110);

        return (facehugger, blackboard, cooldowns) -> {

            if (facehugger.isInfertile() || !facehugger.isAlive()) {
                return BehaviorResult.run(idle, 5);
            }

            if (facehugger.isAttachedToHost()) {
                return BehaviorResult.run(leapAndAttach, 30);
            }

            if (FleeFireAction.shouldFleefire(facehugger) || facehugger.isOnFire()) {
                return BehaviorResult.run(fleeFire, fleeFire.priority());
            }

            if (facehugger.isInWater() || facehugger.isInLava()) {
                return BehaviorResult.run(swim, 200);
            }

            @SuppressWarnings("unchecked")
            var goal = (PlannedGoal<FacehuggerEntity>) blackboard.get(AiKeys.ACTIVE_GOAL, PlannedGoal.class);
            var goalType = goal != null ? goal.type() : AiGoalType.NONE;

            return switch (goalType) {

                case INFECT_HOST -> {
                    var target = blackboard.get(AiKeys.TARGET, LivingEntity.class);
                    if (target == null) {
                        target = goal.target().filter(LivingEntity::isAlive).orElse(null);
                    }

                    if (target != null && target.isAlive()) {
                        var distSq = facehugger.distanceToSqr(target);
                        if (distSq <= 1.25 * 1.25 || !facehugger.onGround()) {
                            yield BehaviorResult.run(leapAndAttach, 30);
                        }
                        blackboard.set(AiKeys.DESTINATION, target.blockPosition());
                        yield BehaviorResult.run(moveToDestination, 10);
                    }

                    yield BehaviorResult.run(idle, 8);
                }

                case STALK_HOST -> {
                    var target = blackboard.get(AiKeys.TARGET, LivingEntity.class);
                    if (target == null) {
                        target = goal.target().filter(LivingEntity::isAlive).orElse(null);
                    }

                    if (target != null && target.isAlive()) {
                        var distSq = facehugger.distanceToSqr(target);

                        if (distSq <= 8.0 * 8.0) {
                            var angle = (facehugger.level().getGameTime() * 0.03)
                                + (facehugger.getId() * 1.3);
                            var radius = 4.5;
                            var tx = target.getX() + Math.cos(angle) * radius;
                            var tz = target.getZ() + Math.sin(angle) * radius;
                            var circlePos = net.minecraft.core.BlockPos.containing(tx, target.getY(), tz);
                            blackboard.set(AiKeys.DESTINATION, circlePos);
                            yield BehaviorResult.run(moveToDestination, 20);
                        }

                        blackboard.set(AiKeys.DESTINATION, target.blockPosition());
                        yield BehaviorResult.run(moveToDestination, 10);
                    }

                    var dest = blackboard.get(AiKeys.GOAL_DESTINATION, BlockPos.class);
                    if (dest != null) {
                        blackboard.set(AiKeys.DESTINATION, dest);
                        yield BehaviorResult.run(moveToDestination, 10);
                    }

                    yield BehaviorResult.run(idle, 8);
                }

                case RETREAT_AND_HIDE -> BehaviorResult.run(retreatAndHide, retreatAndHide.priority());

                case WANDER -> {
                    var dest = blackboard.get(AiKeys.DESTINATION, BlockPos.class);
                    if (dest != null) {
                        yield BehaviorResult.run(moveToDestination, 10);
                    }
                    yield BehaviorResult.run(wander, 9);
                }

                default -> {
                    if (!cooldowns.isOnCooldown(AiKeys.PASSIVE_DECISION)) {
                        cooldowns.set(AiKeys.PASSIVE_DECISION, 180);
                        if (facehugger.getRandom().nextFloat() < 0.1F) {
                            yield BehaviorResult.run(wander, 9);
                        }
                    }
                    yield BehaviorResult.run(idle, 8);
                }
            };
        };
    }
}
