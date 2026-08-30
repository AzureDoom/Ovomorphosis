package mod.azure.ovomorphosis.entities.facehugger;

import com.azure.azurecortex.api.behavior.BehaviorNode;
import com.azure.azurecortex.api.behavior.BehaviorResult;
import com.azure.azurecortex.api.blackboard.CommonBlackboardKeys;
import com.azure.azurecortex.goap.PlannedGoal;
import com.azure.azurecortex.runtime.InterruptCategory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import mod.azure.ovomorphosis.ai.actions.*;
import mod.azure.ovomorphosis.ai.actions.facehugger.LeapAndAttachAction;
import mod.azure.ovomorphosis.ai.actions.facehugger.RetreatAndHideAction;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.util.TargetingUtils;

public class FacehuggerTree {

    /**
     * Health fraction at or below which health is treated as a life-threatening emergency capable of preempting a
     * {@link InterruptCategory#LOCKED} action (e.g. mid-{@code RetreatAndHideAction}'s own {@code FLEEING} phase, which
     * is itself {@code LOCKED} once it enters {@code HIDING}). Mirrors {@code XenomorphTree.CRITICAL_HEALTH_FRACTION} —
     * deliberately lower than the planner's softer {@code RETREAT_HEALTH_FRACTION} (35%) so the two don't fight: the
     * planner's normal RETREAT_AND_HIDE goal handles the common case, and this only engages when things are dire enough
     * to justify breaking through a lock.
     */
    private static final float CRITICAL_HEALTH_FRACTION = 0.15f;

    public static BehaviorNode<FacehuggerEntity, AiGoalType> create() {
        var wander = new WanderAction<FacehuggerEntity, AiGoalType>(
            0.14D,
            9,
            6.0D,
            60,
            160
        );

        var idle = new IdleAction<FacehuggerEntity, AiGoalType>(40, 100, 1);

        var moveToDestination = new MoveToDestinationAction<FacehuggerEntity, AiGoalType>(
            0.6D,
            0.32D,
            10,
            true
        );

        var leapAndAttach = new LeapAndAttachAction<FacehuggerEntity, AiGoalType>();
        var retreatAndHide = new RetreatAndHideAction<AiGoalType>();
        var swim = new SwimAction<FacehuggerEntity, AiGoalType>(200);
        var fleeFire = new FleeFireAction<FacehuggerEntity, AiGoalType>(110);

        return (facehugger, blackboard, cooldowns) -> {

            if (facehugger.isInfertile() || !facehugger.isAlive()) {
                return BehaviorResult.run(idle, 5);
            }

            if (facehugger.isAttachedToHost()) {
                return BehaviorResult.run(leapAndAttach, 30);
            }

            if (FleeFireAction.shouldFleefire(facehugger, blackboard, cooldowns) || facehugger.isOnFire()) {
                return BehaviorResult.run(fleeFire, fleeFire.priority());
            }

            if (
                facehugger.getMaxHealth() > 0f
                    && facehugger.getHealth() <= facehugger.getMaxHealth() * CRITICAL_HEALTH_FRACTION
            ) {
                var haven = FacehuggerGoalPlanner.findHidePosition(facehugger);
                if (haven != null) {
                    blackboard.set(CommonBlackboardKeys.GOAL_DESTINATION, haven);
                    return BehaviorResult.runEmergency(retreatAndHide, 108);
                }
            }

            if (facehugger.isInWater() || facehugger.isInLava()) {
                return BehaviorResult.run(swim, 200);
            }

            @SuppressWarnings("unchecked")
            var goal = (PlannedGoal<FacehuggerEntity, AiGoalType>) blackboard.get(CommonBlackboardKeys.ACTIVE_GOAL);
            var goalType = goal != null ? goal.type() : AiGoalType.NONE;

            return switch (goalType) {

                case INFECT_HOST -> {
                    var target = blackboard.get(CommonBlackboardKeys.TARGET);
                    if (target == null) {
                        target = resolveFallbackTarget(facehugger, goal);
                    }

                    if (target != null && target.isAlive()) {
                        var distSq = facehugger.distanceToSqr(target);

                        if (distSq <= 1.25 * 1.25 || (!facehugger.onGround() && distSq <= 4.0 * 4.0)) {
                            yield BehaviorResult.run(leapAndAttach, 30);
                        }
                        blackboard.set(CommonBlackboardKeys.DESTINATION, target.blockPosition());
                        yield BehaviorResult.run(moveToDestination, 10);
                    }

                    yield BehaviorResult.run(idle, 8);
                }

                case STALK_HOST -> {
                    var target = blackboard.get(CommonBlackboardKeys.TARGET);
                    if (target == null) {
                        target = resolveFallbackTarget(facehugger, goal);
                    }

                    if (target != null && target.isAlive()) {
                        var distSq = facehugger.distanceToSqr(target);

                        if (distSq <= 8.0 * 8.0) {
                            var angle = (facehugger.level().getGameTime() * 0.03)
                                + (facehugger.getId() * 1.3);
                            var radius = 4.5;
                            var tx = target.getX() + Math.cos(angle) * radius;
                            var tz = target.getZ() + Math.sin(angle) * radius;
                            var circlePos = BlockPos.containing(tx, target.getY(), tz);
                            var circleState = facehugger.level().getBlockState(circlePos);
                            var circleAboveState = facehugger.level().getBlockState(circlePos.above());
                            var circleIsOpen = circleState.getCollisionShape(facehugger.level(), circlePos).isEmpty()
                                && circleAboveState.getCollisionShape(facehugger.level(), circlePos.above()).isEmpty();
                            var circleIsReachable = circleIsOpen
                                && facehugger.level()
                                    .clip(
                                        new ClipContext(
                                            facehugger.position(),
                                            Vec3.atBottomCenterOf(circlePos),
                                            ClipContext.Block.COLLIDER,
                                            ClipContext.Fluid.NONE,
                                            facehugger
                                        )
                                    )
                                    .getType() == HitResult.Type.MISS;

                            if (circleIsReachable) {
                                blackboard.set(CommonBlackboardKeys.DESTINATION, circlePos);
                                yield BehaviorResult.run(moveToDestination, 20);
                            }
                        }

                        blackboard.set(CommonBlackboardKeys.DESTINATION, target.blockPosition());
                        yield BehaviorResult.run(moveToDestination, 10);
                    }

                    var dest = blackboard.get(CommonBlackboardKeys.GOAL_DESTINATION);
                    if (dest != null) {
                        blackboard.set(CommonBlackboardKeys.DESTINATION, dest);
                        yield BehaviorResult.run(moveToDestination, 10);
                    }

                    yield BehaviorResult.run(idle, 8);
                }

                case RETREAT_AND_HIDE -> BehaviorResult.run(retreatAndHide, retreatAndHide.priority());

                case WANDER -> {
                    var dest = blackboard.get(CommonBlackboardKeys.DESTINATION);
                    if (dest != null) {
                        yield BehaviorResult.run(moveToDestination, 10);
                    }
                    yield BehaviorResult.run(wander, 9);
                }

                default -> {
                    if (!cooldowns.isOnCooldown(CommonBlackboardKeys.PASSIVE_DECISION)) {
                        cooldowns.set(CommonBlackboardKeys.PASSIVE_DECISION, 180);
                        if (facehugger.getRandom().nextFloat() < 0.1F) {
                            yield BehaviorResult.run(wander, 9);
                        }
                    }
                    yield BehaviorResult.run(idle, 8);
                }
            };
        };
    }

    /**
     * Re-validates the goal-captured target before trusting it as a fallback.
     * <p>
     * {@link PlannedGoal#target()} is a snapshot taken at plan time and does not track subsequent state changes — most
     * importantly, another facehugger attaching to the same host mid-commitment-window. Without this check, a
     * facehugger whose {@link CommonBlackboardKeys#TARGET} blackboard entry has already been correctly nulled out by
     * {@code FacehuggerGoalPlanner} could still resurrect a now-claimed host from the stale goal snapshot and
     * repeatedly attempt to leap at it, causing the mob to freeze/lunge in place near a crowded host.
     */
    private static LivingEntity resolveFallbackTarget(
        FacehuggerEntity facehugger,
        PlannedGoal<FacehuggerEntity, AiGoalType> goal
    ) {
        if (goal == null)
            return null;

        return goal.target()
            .filter(LivingEntity::isAlive)
            .filter(t -> TargetingUtils.faceHuggerTest(facehugger, t))
            .orElse(null);
    }
}
