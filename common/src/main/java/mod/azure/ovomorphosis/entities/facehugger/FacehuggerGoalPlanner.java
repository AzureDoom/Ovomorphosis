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
import mod.azure.ovomorphosis.ai.goap.PlannedGoal;
import mod.azure.ovomorphosis.ai.util.TargetingUtils;

/**
 * GOAP planner for {@link FacehuggerEntity}.
 * <p>
 * Evaluated once per planning interval, this planner scores the three facehugger goals and commits the highest-scoring
 * one to the blackboard via {@link GoalApplicator}:
 * <ul>
 * <li>{@link AiGoalType#INFECT_HOST} — sprint-leap at a target in range and line of sight.</li>
 * <li>{@link AiGoalType#STALK_HOST} — cautiously close distance on a sensed target.</li>
 * <li>{@link AiGoalType#RETREAT_AND_HIDE} — flee to darkness when injured or outnumbered.</li>
 * <li>{@link AiGoalType#WANDER} — default low-priority idle roam.</li>
 * </ul>
 * Goals are committed for a minimum of {@code minCommitTicks} before a replan is allowed, preventing thrashing between
 * states each tick.
 */
public final class FacehuggerGoalPlanner implements GoalPlanner<FacehuggerEntity> {

    private static final int MIN_COMMIT_TICKS = 40;

    private static final int MAX_COMMIT_TICKS = 200;

    private static final double INFECT_RANGE_SQ = 10.0 * 10.0;

    private static final float RETREAT_HEALTH_FRACTION = 0.35f;

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
        var wanderScore = 1f;

        LivingEntity infectTarget = null;
        LivingEntity stalkTarget = null;
        BlockPos stalkDest = null;
        BlockPos retreatDest = null;

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

        if (lowHealth && target != null) {
            retreatScore = 90f;
            retreatDest = findHidePosition(mob);
        } else if (lowHealth) {
            retreatScore = 50f;
            retreatDest = findHidePosition(mob);
        }

        AiGoalType chosen;
        float chosenScore;
        LivingEntity chosenTarget;
        BlockPos chosenDest;
        GoalUrgency chosenUrgency;
        boolean interruptible;
        String reason;

        if (retreatScore >= infectScore && retreatScore >= stalkScore && retreatScore >= wanderScore) {
            chosen = AiGoalType.RETREAT_AND_HIDE;
            chosenScore = retreatScore;
            chosenTarget = null;
            chosenDest = retreatDest;
            chosenUrgency = target != null ? GoalUrgency.EMERGENCY : GoalUrgency.HIGH;
            interruptible = false;
            reason = "Low health, retreating";
        } else if (infectScore >= stalkScore && infectScore >= wanderScore) {
            chosen = AiGoalType.INFECT_HOST;
            chosenScore = infectScore;
            chosenTarget = infectTarget;
            chosenDest = infectTarget.blockPosition();
            chosenUrgency = GoalUrgency.HIGH;
            interruptible = false;
            reason = "Target in range, attempting infection";
        } else if (stalkScore >= wanderScore) {
            chosen = AiGoalType.STALK_HOST;
            chosenScore = stalkScore;
            chosenTarget = stalkTarget;
            chosenDest = stalkDest;
            chosenUrgency = GoalUrgency.NORMAL;
            interruptible = true;
            reason = "Stalking visible target";
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
     * Finds a nearby dark block position to retreat toward. Scans a small radius for blocks with low light level.
     * Returns the mob's current position as a fallback if nothing better is found.
     */
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
