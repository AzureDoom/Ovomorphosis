package mod.azure.ovomorphosis.entities.facehugger;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.ai.core.Cooldowns;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.goap.GoalApplicator;
import mod.azure.ovomorphosis.ai.goap.GoalPlanner;
import mod.azure.ovomorphosis.ai.goap.GoalUrgency;
import mod.azure.ovomorphosis.ai.goap.PlanFailureReason;
import mod.azure.ovomorphosis.ai.goap.PlanFeedback;
import mod.azure.ovomorphosis.ai.goap.PlannedGoal;
import mod.azure.ovomorphosis.ai.util.TargetingUtils;

/**
 * GOAP planner for {@link FacehuggerEntity}.
 * <p>
 * Evaluated once per planning interval, this planner scores the facehugger goals and commits the highest-scoring one to
 * the blackboard via {@link GoalApplicator}:
 * <ul>
 * <li>{@link AiGoalType#INFECT_HOST} — sprint-leap at a target in range and line of sight.</li>
 * <li>{@link AiGoalType#STALK_HOST} — cautiously close distance on a sensed target.</li>
 * <li>{@link AiGoalType#RETREAT_AND_HIDE} — flee to darkness when injured or outnumbered.</li>
 * <li>{@link AiGoalType#INVESTIGATE} — move to last-known target position after losing sight.</li>
 * <li>{@link AiGoalType#WANDER} — default low-priority idle roam.</li>
 * </ul>
 * <h3>Failure feedback</h3> Actions write a {@link PlanFeedback} to {@link AiKeys#LAST_PLAN_FEEDBACK} when they fail.
 * This planner reads that feedback <em>before</em> scoring and applies additive score modifiers:
 * <ul>
 * <li>{@link PlanFailureReason#FAILED_TARGET_LOST} → boost {@link AiGoalType#INVESTIGATE}</li>
 * <li>{@link PlanFailureReason#FAILED_NO_PATH} / {@link PlanFailureReason#FAILED_STUCK} → suppress the failing goal;
 * boost INVESTIGATE to try a different approach angle</li>
 * <li>{@link PlanFailureReason#FAILED_DANGER} → boost {@link AiGoalType#RETREAT_AND_HIDE}</li>
 * <li>{@link PlanFailureReason#FAILED_COOLDOWN} → suppress the goal that was on cooldown</li>
 * </ul>
 * Feedback is cleared by {@link GoalApplicator#apply} after a new goal is committed.
 */
public final class FacehuggerGoalPlanner implements GoalPlanner<FacehuggerEntity> {

    private static final int MIN_COMMIT_TICKS = 40;

    private static final int MAX_COMMIT_TICKS = 200;

    private static final double INFECT_RANGE_SQ = 10.0 * 10.0;

    private static final float RETREAT_HEALTH_FRACTION = 0.35f;

    private static final float BOOST_INVESTIGATE = 45f;

    private static final float BOOST_RETREAT = 55f;

    private static final float PENALTY_FAILED_GOAL = 40f;

    @Override
    public PlannedGoal<FacehuggerEntity> chooseGoal(
        FacehuggerEntity mob,
        Blackboard blackboard,
        Cooldowns cooldowns
    ) {
        int tick = (int) mob.level().getGameTime();

        if (mob.isAttachedToHost()) {
            return PlannedGoal.of(
                AiGoalType.INFECT_HOST,
                100f,
                tick,
                MIN_COMMIT_TICKS,
                MAX_COMMIT_TICKS,
                null,
                null,
                GoalUrgency.EMERGENCY,
                false,
                "Already attached to host"
            );
        }

        var feedback = readFeedback(blackboard, tick);

        var target = blackboard.get(AiKeys.TARGET, LivingEntity.class);
        if (target != null && (!target.isAlive() || !TargetingUtils.faceHuggerTest(mob, target))) {
            target = null;
            blackboard.set(AiKeys.TARGET, null);
            mob.setTarget(null);
        }

        var healthFraction = mob.getHealth() / mob.getMaxHealth();
        var lowHealth = healthFraction <= RETREAT_HEALTH_FRACTION;

        var infectScore = 0f;
        var stalkScore = 0f;
        var retreatScore = 0f;
        var investigateScore = 0f;
        var wanderScore = 1f;

        LivingEntity infectTarget = null;
        LivingEntity stalkTarget = null;
        BlockPos stalkDest = null;
        BlockPos retreatDest = null;
        BlockPos investigateDest = null;

        if (target != null) {
            double distSq = mob.distanceToSqr(target);
            boolean hasLos = mob.hasLineOfSight(target);

            if (hasLos && distSq <= INFECT_RANGE_SQ) {
                infectScore = 80f + (float) (INFECT_RANGE_SQ - distSq) / (float) INFECT_RANGE_SQ * 15f;
                infectTarget = target;
            } else {
                stalkScore = 60f;
                stalkTarget = target;
                stalkDest = target.blockPosition();
            }
        }

        if (lowHealth) {
            retreatScore = target != null ? 90f : 50f;
            retreatDest = findHidePosition(mob);
        }

        var lastSeenPos = blackboard.get(AiKeys.LAST_SEEN_POS, BlockPos.class);
        if (lastSeenPos != null && target == null) {
            investigateScore = 30f;
            investigateDest = lastSeenPos;
        }

        if (feedback != null && feedback.isFresh(tick)) {
            var reason = feedback.reason();
            var failedGoal = feedback.failedGoalType();

            switch (reason) {
                case FAILED_TARGET_LOST -> {
                    investigateScore += BOOST_INVESTIGATE;
                    if (feedback.failurePos() != null) {
                        investigateDest = feedback.failurePos();
                    }
                }
                case FAILED_NO_PATH, FAILED_STUCK, FAILED_BLOCKED -> {
                    if (failedGoal == AiGoalType.INFECT_HOST)
                        infectScore -= PENALTY_FAILED_GOAL;
                    if (failedGoal == AiGoalType.STALK_HOST)
                        stalkScore -= PENALTY_FAILED_GOAL;
                    if (failedGoal == AiGoalType.RETREAT_AND_HIDE)
                        retreatScore -= PENALTY_FAILED_GOAL;
                    investigateScore += BOOST_INVESTIGATE * 0.5f;
                }
                case FAILED_DANGER -> {
                    retreatScore += BOOST_RETREAT;
                    retreatDest = retreatDest != null ? retreatDest : findHidePosition(mob);
                    infectScore -= PENALTY_FAILED_GOAL;
                    stalkScore -= PENALTY_FAILED_GOAL;
                }
                case FAILED_COOLDOWN -> {
                    if (failedGoal == AiGoalType.INFECT_HOST)
                        infectScore -= PENALTY_FAILED_GOAL;
                    if (failedGoal == AiGoalType.STALK_HOST)
                        stalkScore -= PENALTY_FAILED_GOAL;
                }
                default -> { /* NONE, FAILED_PRECONDITION, etc. — no adjustment */ }
            }
        }

        infectScore = Math.max(0f, infectScore);
        stalkScore = Math.max(0f, stalkScore);
        retreatScore = Math.max(0f, retreatScore);
        investigateScore = Math.max(0f, investigateScore);

        AiGoalType chosen;
        float chosenScore;
        LivingEntity chosenTarget;
        BlockPos chosenDest;
        GoalUrgency chosenUrgency;
        boolean interruptible;
        String reason;

        if (
            retreatScore >= infectScore && retreatScore >= stalkScore
                && retreatScore >= investigateScore && retreatScore >= wanderScore
        ) {
            chosen = AiGoalType.RETREAT_AND_HIDE;
            chosenScore = retreatScore;
            chosenTarget = null;
            chosenDest = retreatDest;
            chosenUrgency = target != null ? GoalUrgency.EMERGENCY : GoalUrgency.HIGH;
            interruptible = false;
            reason = buildReason("Low health, retreating", feedback);
        } else if (infectScore >= stalkScore && infectScore >= investigateScore && infectScore >= wanderScore) {
            chosen = AiGoalType.INFECT_HOST;
            chosenScore = infectScore;
            chosenTarget = infectTarget;
            chosenDest = infectTarget.blockPosition();
            chosenUrgency = GoalUrgency.HIGH;
            interruptible = false;
            reason = "Target in range, attempting infection";
        } else if (stalkScore >= investigateScore && stalkScore >= wanderScore) {
            chosen = AiGoalType.STALK_HOST;
            chosenScore = stalkScore;
            chosenTarget = stalkTarget;
            chosenDest = stalkDest;
            chosenUrgency = GoalUrgency.NORMAL;
            interruptible = true;
            reason = "Stalking visible target";
        } else if (investigateScore >= wanderScore) {
            chosen = AiGoalType.INVESTIGATE;
            chosenScore = investigateScore;
            chosenTarget = null;
            chosenDest = investigateDest;
            chosenUrgency = GoalUrgency.NORMAL;
            interruptible = true;
            reason = buildReason("Investigating last known position", feedback);
        } else {
            chosen = AiGoalType.WANDER;
            chosenScore = wanderScore;
            chosenTarget = null;
            chosenDest = null;
            chosenUrgency = GoalUrgency.LOW;
            interruptible = true;
            reason = "Nothing of interest";
        }

        return PlannedGoal.of(
            chosen,
            chosenScore,
            tick,
            MIN_COMMIT_TICKS,
            MAX_COMMIT_TICKS,
            chosenTarget,
            chosenDest,
            chosenUrgency,
            interruptible,
            reason
        );
    }

    /**
     * Reads {@link PlanFeedback} from the blackboard, falling back to constructing one from the raw
     * {@link PlanFailureReason} shorthand key if the full record isn't present.
     */
    private static PlanFeedback readFeedback(Blackboard blackboard, int tick) {
        var full = blackboard.get(AiKeys.LAST_PLAN_FEEDBACK, PlanFeedback.class);
        if (full != null)
            return full;

        var raw = blackboard.get(AiKeys.LAST_FAILURE_REASON, PlanFailureReason.class);
        if (raw != null && raw != PlanFailureReason.NONE) {
            var activeGoalType = blackboard.get(AiKeys.ACTIVE_GOAL_TYPE, AiGoalType.class);
            return PlanFeedback.of(
                raw,
                tick,
                null,
                activeGoalType != null ? activeGoalType : AiGoalType.NONE
            );
        }
        return null;
    }

    private static String buildReason(String base, PlanFeedback feedback) {
        if (feedback == null || feedback.isNone())
            return base;
        return base + " [after " + feedback.reason().name() + "]";
    }

    private static BlockPos findHidePosition(FacehuggerEntity mob) {
        var origin = mob.blockPosition();
        var level = mob.level();
        var rng = mob.getRandom();

        BlockPos best = null;
        var bestLight = Integer.MAX_VALUE;

        for (var i = 0; i < 12; i++) {
            var dx = rng.nextIntBetweenInclusive(-10, 10);
            var dy = rng.nextIntBetweenInclusive(-3, 3);
            var dz = rng.nextIntBetweenInclusive(-10, 10);
            var candidate = origin.offset(dx, dy, dz);

            if (!level.getBlockState(candidate).isAir())
                continue;
            if (!level.getBlockState(candidate.below()).isSolidRender(mob.level(), candidate))
                continue;

            var light = level.getLightEmission(candidate);
            if (light < bestLight) {
                bestLight = light;
                best = candidate;
            }
        }

        return best != null
            ? best
            : origin.offset(
                rng.nextIntBetweenInclusive(-6, 6),
                0,
                rng.nextIntBetweenInclusive(-6, 6)
            );
    }
}
