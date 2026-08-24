package mod.azure.ovomorphosis.entities.xenomorph;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import mod.azure.ovomorphosis.ai.actions.FleeFireAction;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.ai.core.Cooldowns;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.goap.GoalApplicator;
import mod.azure.ovomorphosis.ai.goap.GoalFailureCooldowns;
import mod.azure.ovomorphosis.ai.goap.GoalPlanner;
import mod.azure.ovomorphosis.ai.goap.GoalUrgency;
import mod.azure.ovomorphosis.ai.goap.PlanFailureReason;
import mod.azure.ovomorphosis.ai.goap.PlanFeedback;
import mod.azure.ovomorphosis.ai.goap.PlannedGoal;
import mod.azure.ovomorphosis.ai.roles.XenoRole;
import mod.azure.ovomorphosis.ai.util.HiveMemory;
import mod.azure.ovomorphosis.ai.util.TargetClassifier;
import mod.azure.ovomorphosis.util.ModTags;

/**
 * GOAP planner for {@link XenomorphEntity}.
 * <h3>Anti-thrash design</h3> Four mechanisms prevent goal oscillation:
 * <ol>
 * <li><b>Min-commit lock</b> — {@link GoalApplicator#shouldReplan} suppresses the planner until
 * {@link PlannedGoal#canReplan} is true (default 40 ticks). The entity's tick method must call {@code shouldReplan}
 * before invoking this planner.</li>
 * <li><b>Hysteresis bonus</b> — the currently active goal type receives a flat {@link #HYSTERESIS_BONUS} score
 * addition, raising the bar a challenger must clear to displace it. Emergency-tier situations still win because their
 * base scores are much higher than the bonus.</li>
 * <li><b>Per-goal failure cooldowns</b> — {@link GoalFailureCooldowns} tracks which goal types have recently failed and
 * applies a linearly decaying penalty to their scores, persisting well beyond the 80-tick {@link PlanFeedback}
 * freshness window.</li>
 * <li><b>Emergency override tier</b> — goals scored at or above {@link #EMERGENCY_SCORE_THRESHOLD} are tagged
 * {@link GoalUrgency#EMERGENCY}, which causes {@link GoalApplicator#shouldReplan} to bypass the min-commit lock on the
 * caller side.</li>
 * </ol>
 * <h3>Failure recording</h3> When the feedback switch fires for path/stuck/blocked failures the planner now calls
 * {@link GoalFailureCooldowns#recordFailure} in addition to the existing score penalty. This means a goal that fails
 * repeatedly is suppressed for a full {@link GoalFailureCooldowns#DEFAULT_DURATION} (200 ticks) rather than just the
 * 80-tick feedback window.
 */
public final class XenomorphGoalPlanner implements GoalPlanner<XenomorphEntity> {

    /** Health fraction at/below which survival overrides everything else ("critical" tier). */
    private static final float RETREAT_HEALTH_FRACTION = 0.30f;

    /**
     * Health fraction above which the mob is considered "wounded" rather than critical (between this and
     * {@link #RETREAT_HEALTH_FRACTION} is the contextual-retreat band). Above this fraction the mob is healthy enough
     * to stay aggressive regardless of opponent.
     */
    private static final float WOUNDED_HEALTH_FRACTION = 0.60f;

    /** Max local light level a hive position can have and still count as a viable dark hideout. */
    private static final int DARK_HAVEN_MAX_LIGHT = 4;

    /** Squared distance at which the mob is considered to have "arrived" at its dark hideout. */
    private static final double DARK_HAVEN_ARRIVAL_RANGE_SQR = 6.0 * 6.0;

    private static final float BOOST_BREAK = 55f;

    private static final float BOOST_INVEST = 40f;

    private static final float BOOST_LIGHTS = 50f;

    private static final float BOOST_HIVE = 45f;

    private static final float BOOST_RETREAT = 60f;

    private static final float PENALTY_FAILED = 40f;

    private static final float HYSTERESIS_BONUS = 20f;

    private static final float EMERGENCY_SCORE_THRESHOLD = 85f;

    @Override
    public PlannedGoal<XenomorphEntity> chooseGoal(
        XenomorphEntity mob,
        Blackboard blackboard,
        Cooldowns cooldowns
    ) {
        int tick = (int) mob.level().getGameTime();

        var gfc = GoalFailureCooldowns.getOrCreate(blackboard);
        gfc.evictExpired(tick);

        var feedback = readFeedback(blackboard, tick);

        var target = blackboard.get(AiKeys.TARGET, LivingEntity.class);
        var hasTarget = target != null && target.isAlive();

        var healthFraction = mob.getHealth() / mob.getMaxHealth();

        // Three-tier health posture:
        // > 60% -> healthyAggressive: press the fight regardless of opponent.
        // 30% - 60% -> woundedHealth: contextual — retreat only if this particular opponent warrants it.
        // < 30% -> criticalHealth: survival overrides everything; strongly favor escape/darkness/hive.
        var criticalHealth = healthFraction <= RETREAT_HEALTH_FRACTION;
        var woundedHealth = !criticalHealth && healthFraction <= WOUNDED_HEALTH_FRACTION;
        var healthyAggressive = healthFraction > WOUNDED_HEALTH_FRACTION;

        var memory = blackboard.get(AiKeys.HIVE_MEMORY, HiveMemory.class);
        if (memory != null) {
            memory.recomputeNeedsIfDue(mob.level(), tick);
        }
        var nearWeb = memory != null
            && memory.findNearestOwnedWebCross(mob.level(), mob.blockPosition(), 20.0D).isPresent();
        var hasWebInRange = memory != null
            && memory.findNearestOwnedWebCross(mob.level(), mob.blockPosition(), 80.0D).isPresent();

        // Known dark hive position — this is the piece a Gigeresque mob has no equivalent of: it isn't just fleeing
        // blindly, it's routing toward a remembered hideout in its own territory.
        var darkHaven = memory != null
            ? memory.findNearestDarkOwnedWebCross(mob.level(), mob.blockPosition(), 80.0D, DARK_HAVEN_MAX_LIGHT)
            : java.util.Optional.<BlockPos>empty();
        var atDarkHaven = darkHaven.isPresent()
            && mob.blockPosition().distSqr(darkHaven.get()) <= DARK_HAVEN_ARRIVAL_RANGE_SQR;

        var ambientLight = mob.level().getMaxLocalRawBrightness(mob.blockPosition());
        var tooBright = ambientLight > 4;

        TargetClassifier.classify(mob, blackboard);
        var targetIsRanged = Boolean.TRUE.equals(blackboard.get(AiKeys.TARGET_IS_RANGED, Boolean.class));
        var targetIsIsolated = Boolean.TRUE.equals(blackboard.get(AiKeys.TARGET_IS_ISOLATED, Boolean.class));
        var targetIsNearHive = Boolean.TRUE.equals(blackboard.get(AiKeys.TARGET_IS_NEAR_HIVE, Boolean.class));
        var targetIsValidHost = Boolean.TRUE.equals(blackboard.get(AiKeys.TARGET_IS_VALID_HOST, Boolean.class));
        var targetTooDangerous = Boolean.TRUE.equals(
            blackboard.get(AiKeys.TARGET_IS_TOO_DANGEROUS_TO_GRAB, Boolean.class)
        );

        var huntScore = 0f;
        var ambushScore = 0f;
        var breakScore = 0f;
        var lightsScore = tooBright ? 35f : 0f;
        var hiveScore = 10f;
        var defendScore = 0f;
        var retreatScore = 0f;
        var investigateScore = 0f;
        var seekDarknessScore = 0f;
        var ambushFromDarknessScore = 0f;
        var wanderScore = 5f;

        if (hasTarget) {
            var distSq = mob.distanceToSqr(target);
            var targetFacingMob = isTargetFacingMob(target, mob);

            huntScore = 70f + (targetFacingMob ? 20f : 0f);

            if (targetIsRanged) {
                huntScore -= 20f;
                ambushScore += 25f;
            }
            if (targetIsIsolated && targetIsValidHost && !targetTooDangerous) {
                hiveScore += 20f;
            }
            if (targetIsNearHive || nearWeb) {
                defendScore = 85f;
                if (memory != null) {
                    // A hostile target inside hive territory is an incursion — record it so repeated attacks (as
                    // opposed to one-off skirmishes) can raise the hive's baseline defensive aggression below.
                    memory.recordThreat(target.blockPosition(), tick);
                }
            }
            if (!targetFacingMob && distSq > 8.0 * 8.0) {
                ambushScore += 25f;
                huntScore -= 15f;
            }
            if (distSq <= 12.0 * 12.0) {
                breakScore = 20f;
            }
        }

        if (tooBright && !hasTarget) {
            seekDarknessScore = 30f + ambientLight * 2f;
        }
        if (hasTarget && tooBright) {
            ambushFromDarknessScore = 45f + ambientLight * 2f;
            huntScore -= 20f;
        }

        // --- Health-tiered retreat/darkness posture ------------------------------------------------------------
        // healthyAggressive : health alone never pulls toward retreat/darkness; hunt/ambush stand on their own.
        // woundedHealth : contextual — only pull toward retreat if *this particular* opponent warrants it.
        // criticalHealth : survival overrides everything; strongly favor escape, darkness, and the known hive.
        // A target counts as "threatening" if it's already flagged too dangerous to grab, uses ranged attacks, or
        // carries the danger-entity tag; a target flagged isolated and NOT threatening counts as "weak" prey.
        var opponentIsThreatening = hasTarget
            && (targetTooDangerous || targetIsRanged || target.getType().is(ModTags.DANGER_ENTITIES));
        var opponentIsWeak = hasTarget && targetIsIsolated && !opponentIsThreatening;

        if (healthyAggressive) {
            // No health-driven retreat/darkness pull at all — huntScore/ambushScore stand on their own merits.
        } else if (woundedHealth) {
            if (opponentIsThreatening) {
                retreatScore = hasWebInRange ? 55f : 30f;
                seekDarknessScore += ambientLight > 2 ? 20f : 10f;
                huntScore -= 15f;
            } else if (hasTarget && !opponentIsWeak) {
                // Ambiguous opponent while merely wounded: a small hedge, easily outscored by hunt/ambush so it only
                // matters in an otherwise close call.
                retreatScore = hasWebInRange ? 20f : 10f;
            }
            // opponentIsWeak, or no target at all: stay aggressive — wounded is not yet critical.
        } else if (criticalHealth) {
            retreatScore = darkHaven.isPresent() ? 95f : (hasWebInRange ? 80f : 45f);
            seekDarknessScore += 35f + (ambientLight > 2 ? 15f : 0f);
            huntScore = Math.max(0f, huntScore - 40f);
            ambushScore = Math.max(0f, ambushScore - 20f);

            if (atDarkHaven && hasTarget) {
                // Reached the known dark hideout with a pursuer still on its tail. Rather than blindly continuing to
                // flee into what may be a dead end, let it *possibly* turn and strike from cover instead — this only
                // outscores the (very high) retreatScore above when the pursuer looks weak/isolated enough to risk
                // it, so a genuinely dangerous pursuer still gets outrun rather than fought.
                ambushFromDarknessScore += opponentIsWeak ? 60f : 25f;
            }
        }

        var lastSeenPos = blackboard.get(AiKeys.LAST_SEEN_POS, BlockPos.class);
        if (lastSeenPos != null && !hasTarget) {
            investigateScore = 35f;
        }

        if (!hasTarget && !tooBright) {
            hiveScore = 20f;
        }

        if (tooBright) {
            lightsScore = 20f + ambientLight * 3f;
        }

        if (feedback != null && feedback.isFresh(tick)) {
            var reason = feedback.reason();
            var failedGoal = feedback.failedGoalType();

            switch (reason) {
                case FAILED_NO_PATH, FAILED_STUCK, FAILED_BLOCKED -> {
                    breakScore += BOOST_BREAK;
                    investigateScore += BOOST_INVEST * 0.5f;
                    if (failedGoal == AiGoalType.HUNT_TARGET) {
                        huntScore -= PENALTY_FAILED;
                        gfc.recordFailure(AiGoalType.HUNT_TARGET, tick, 160);
                    }
                    if (failedGoal == AiGoalType.AMBUSH_TARGET) {
                        ambushScore -= PENALTY_FAILED;
                        gfc.recordFailure(AiGoalType.AMBUSH_TARGET, tick, 120);
                    }
                    if (failedGoal == AiGoalType.EXPAND_HIVE) {
                        gfc.recordFailure(AiGoalType.EXPAND_HIVE, tick, 100);
                    }
                }
                case FAILED_TARGET_LOST -> {
                    investigateScore += BOOST_INVEST;
                    huntScore -= PENALTY_FAILED * 0.5f;
                    gfc.recordFailure(AiGoalType.HUNT_TARGET, tick, 80);
                }
                case FAILED_TOO_BRIGHT -> {
                    lightsScore += BOOST_LIGHTS;
                    if (failedGoal == AiGoalType.EXPAND_HIVE) {
                        // Resin placement rejected this spot for being too bright: prioritize killing the light
                        // instead of immediately recommitting to hive expansion here.
                        hiveScore -= PENALTY_FAILED;
                        wanderScore += 15f;
                        gfc.recordFailure(AiGoalType.EXPAND_HIVE, tick, 150);
                    } else {
                        huntScore -= PENALTY_FAILED * 0.5f;
                        gfc.recordFailure(AiGoalType.HUNT_TARGET, tick, 60);
                    }
                }
                case FAILED_NO_VALID_PLACEMENT -> {
                    // Resin placement found nowhere valid to place at all — suppress EXPAND_HIVE here for a
                    // cooldown window and nudge the mob toward wandering/repositioning instead of recommitting to
                    // the same doomed spot next planning cycle.
                    hiveScore -= PENALTY_FAILED;
                    wanderScore += 25f;
                    gfc.recordFailure(AiGoalType.EXPAND_HIVE, tick, 150);
                }
                case FAILED_NO_WEB -> {
                    hiveScore += BOOST_HIVE;
                    gfc.recordFailure(AiGoalType.EXPAND_HIVE, tick, 120);
                }
                case FAILED_DANGER -> {
                    retreatScore += BOOST_RETREAT;
                    huntScore -= PENALTY_FAILED;
                    ambushScore -= PENALTY_FAILED;
                    gfc.recordFailure(AiGoalType.HUNT_TARGET, tick, 200);
                    gfc.recordFailure(AiGoalType.AMBUSH_TARGET, tick, 200);
                }
                case FAILED_COOLDOWN -> {
                    if (failedGoal == AiGoalType.HUNT_TARGET) {
                        huntScore -= PENALTY_FAILED * 0.5f;
                        gfc.recordFailure(AiGoalType.HUNT_TARGET, tick, 40);
                    }
                    if (failedGoal == AiGoalType.EXPAND_HIVE) {
                        hiveScore -= PENALTY_FAILED * 0.5f;
                        gfc.recordFailure(AiGoalType.EXPAND_HIVE, tick, 40);
                    }
                }
                case FAILED_OBSTACLE_UNBREAKABLE -> {
                    investigateScore += BOOST_INVEST;
                    ambushScore += 20f;
                    seekDarknessScore += 25f;
                    breakScore -= PENALTY_FAILED;
                    gfc.recordFailure(AiGoalType.BREAK_OBSTACLE, tick, 200);
                    if (failedGoal == AiGoalType.HUNT_TARGET) {
                        huntScore -= PENALTY_FAILED;
                        gfc.recordFailure(AiGoalType.HUNT_TARGET, tick, 120);
                    }
                }
                default -> {}
            }
        }

        huntScore -= gfc.getPenalty(AiGoalType.HUNT_TARGET, tick);
        ambushScore -= gfc.getPenalty(AiGoalType.AMBUSH_TARGET, tick);
        breakScore -= gfc.getPenalty(AiGoalType.BREAK_OBSTACLE, tick);
        hiveScore -= gfc.getPenalty(AiGoalType.EXPAND_HIVE, tick);
        lightsScore -= gfc.getPenalty(AiGoalType.KILL_LIGHTS, tick);
        retreatScore -= gfc.getPenalty(AiGoalType.RETREAT_TO_RESIN, tick);
        investigateScore -= gfc.getPenalty(AiGoalType.INVESTIGATE, tick);
        seekDarknessScore -= gfc.getPenalty(AiGoalType.SEEK_DARKNESS, tick);
        ambushFromDarknessScore -= gfc.getPenalty(AiGoalType.AMBUSH_FROM_DARKNESS, tick);

        FleeFireAction.tickFireAttackerMemory(blackboard, tick);

        var fireAttacker = blackboard.get(AiKeys.LAST_FIRE_ATTACKER, LivingEntity.class);
        var fireUserIsTarget = Boolean.TRUE.equals(blackboard.get(AiKeys.TARGET_IS_FIRE_USER, Boolean.class));
        var fireDangerActive = FleeFireAction.isFireDangerActive(blackboard, tick);

        if (fireAttacker != null && target != null) {
            var isSame = fireAttacker == target;
            blackboard.set(AiKeys.TARGET_IS_FIRE_USER, isSame);
            fireUserIsTarget = isSame;
        }

        if (fireDangerActive && hasTarget) {
            if (fireUserIsTarget) {
                huntScore -= 25f;
                ambushScore += 35f;
                lightsScore += 15f;
                if (nearWeb) {
                    defendScore = Math.max(0f, defendScore - 20f);
                    retreatScore += 20f;
                }
            } else {
                retreatScore += 15f;
                ambushScore += 10f;
            }
        }

        // --- Hive-needs-driven posture -------------------------------------------------------------------------
        // Organic equivalent of a colony noticing its own state and responding, rather than each xenomorph acting
        // purely on its own immediate situation. All of these are additive nudges on top of everything above, not
        // replacements — an active combat encounter still dominates through huntScore/ambushScore as normal.
        if (memory != null) {
            // Few hosts being converted -> prioritize hunting, and specifically capturing rather than killing a
            // valid host outright when one's available (reuses/extends the isolated-valid-host boost above).
            if (memory.hasFewHosts()) {
                huntScore += 15f;
                if (hasTarget && targetIsValidHost && !targetTooDangerous) {
                    hiveScore += 15f;
                }
            }

            // Plenty of hosts already restrained, but nowhere to route more of them -> prioritize construction over
            // capturing yet more hosts the hive has no infrastructure to use.
            if (memory.hasHostSurplusWithLittleResin()) {
                hiveScore += 30f;
            }

            // Hive's core has been exposed to light -> prioritize destroying light sources hive-wide, not just
            // wherever this particular mob happens to be standing.
            if (memory.isHeavilyIlluminated()) {
                lightsScore += 30f;
            }

            // Repeated incursions -> defensive aggression increases: hold the hive harder, and press an engagement
            // near it rather than favoring disengagement.
            if (memory.isUnderSustainedAttack(tick)) {
                defendScore += 25f;
                huntScore += 10f;
            }

            // Hive crowded with an unclaimed direction still available -> extend tunnels outward.
            if (memory.isCrowded() && memory.hasRoomToExpand()) {
                hiveScore += 20f;
            }

            // Nest maturity sets a small baseline lean rather than a hard override: a still-bootstrapping hive
            // leans toward growing its population, an established one leans toward holding what it has.
            switch (memory.nestMaturity()) {
                case HATCHLING, GROWING -> hiveScore += 10f;
                case THRIVING -> defendScore += 10f;
                default -> {}
            }
        }

        huntScore = Math.max(0f, huntScore);
        ambushScore = Math.max(0f, ambushScore);
        breakScore = Math.max(0f, breakScore);
        lightsScore = Math.max(0f, lightsScore);
        hiveScore = Math.max(0f, hiveScore);
        defendScore = Math.max(0f, defendScore);
        retreatScore = Math.max(0f, retreatScore);
        investigateScore = Math.max(0f, investigateScore);
        seekDarknessScore = Math.max(0f, seekDarknessScore);
        ambushFromDarknessScore = Math.max(0f, ambushFromDarknessScore);

        var activeGoalType = blackboard.get(AiKeys.ACTIVE_GOAL_TYPE, AiGoalType.class);
        if (activeGoalType != null) {
            switch (activeGoalType) {
                case HUNT_TARGET -> huntScore += HYSTERESIS_BONUS;
                case AMBUSH_TARGET -> ambushScore += HYSTERESIS_BONUS;
                case BREAK_OBSTACLE -> breakScore += HYSTERESIS_BONUS;
                case KILL_LIGHTS -> lightsScore += HYSTERESIS_BONUS;
                case EXPAND_HIVE -> hiveScore += HYSTERESIS_BONUS;
                case DEFEND_HIVE -> defendScore += HYSTERESIS_BONUS;
                case RETREAT_TO_RESIN -> retreatScore += HYSTERESIS_BONUS;
                case INVESTIGATE -> investigateScore += HYSTERESIS_BONUS;
                case WANDER -> wanderScore += HYSTERESIS_BONUS;
                case SEEK_DARKNESS -> seekDarknessScore += HYSTERESIS_BONUS;
                case AMBUSH_FROM_DARKNESS -> ambushFromDarknessScore += HYSTERESIS_BONUS;
                default -> {}
            }
        }

        record Candidate(
            AiGoalType type,
            float score
        ) {}

        var best = new Candidate(AiGoalType.WANDER, wanderScore);
        for (
            var c : new Candidate[] {
                new Candidate(AiGoalType.HUNT_TARGET, huntScore),
                new Candidate(AiGoalType.AMBUSH_TARGET, ambushScore),
                new Candidate(AiGoalType.BREAK_OBSTACLE, breakScore),
                new Candidate(AiGoalType.KILL_LIGHTS, lightsScore),
                new Candidate(AiGoalType.EXPAND_HIVE, hiveScore),
                new Candidate(AiGoalType.DEFEND_HIVE, defendScore),
                new Candidate(AiGoalType.RETREAT_TO_RESIN, retreatScore),
                new Candidate(AiGoalType.INVESTIGATE, investigateScore),
                new Candidate(AiGoalType.SEEK_DARKNESS, seekDarknessScore),
                new Candidate(AiGoalType.AMBUSH_FROM_DARKNESS, ambushFromDarknessScore),
            }
        ) {
            if (c.score() > best.score())
                best = c;
        }

        var chosen = best.type();
        var chosenScore = best.score();

        GoalUrgency urgency;
        boolean interruptible;
        String reason;
        LivingEntity chosenTarget = hasTarget ? target : null;
        BlockPos chosenDest = null;

        switch (chosen) {
            case HUNT_TARGET -> {
                urgency = chosenScore >= EMERGENCY_SCORE_THRESHOLD ? GoalUrgency.EMERGENCY : GoalUrgency.HIGH;
                interruptible = false;
                reason = buildReason("Hunting target", feedback);
            }
            case AMBUSH_TARGET -> {
                urgency = GoalUrgency.NORMAL;
                interruptible = true;
                reason = "Ambushing — target unaware";
            }
            case BREAK_OBSTACLE -> {
                urgency = GoalUrgency.HIGH;
                interruptible = true;
                reason = buildReason("Breaking obstacle to reach target", feedback);
            }
            case KILL_LIGHTS -> {
                urgency = GoalUrgency.NORMAL;
                interruptible = true;
                reason = "Destroying light sources";
                chosenTarget = null;
            }
            case EXPAND_HIVE -> {
                urgency = GoalUrgency.LOW;
                interruptible = true;
                reason = "Expanding hive";
                chosenTarget = null;
            }
            case DEFEND_HIVE -> {
                urgency = GoalUrgency.EMERGENCY;
                interruptible = false;
                reason = "Target in hive — defending";
            }
            case RETREAT_TO_RESIN -> {
                urgency = criticalHealth ? GoalUrgency.EMERGENCY : GoalUrgency.HIGH;
                interruptible = false;
                reason = criticalHealth
                    ? "Critical health — fleeing to known dark hive haven"
                    : "Wounded — retreating from a dangerous opponent";
                chosenTarget = null;
                if (memory != null) {
                    // Prefer the remembered dark hideout over just "the closest bit of hive" when it's available —
                    // this is the piece of routing a vanilla mob's flee logic has no equivalent of.
                    chosenDest = darkHaven.isPresent()
                        ? darkHaven.get()
                        : memory.findNearestOwnedWebCross(mob.level(), mob.blockPosition(), 80.0D).orElse(null);
                }
            }
            case INVESTIGATE -> {
                urgency = GoalUrgency.NORMAL;
                interruptible = true;
                chosenDest = lastSeenPos != null
                    ? lastSeenPos
                    : (feedback != null ? feedback.failurePos() : null);
                reason = buildReason("Investigating last known position", feedback);
                chosenTarget = null;
            }
            case SEEK_DARKNESS -> {
                urgency = GoalUrgency.NORMAL;
                interruptible = true;
                reason = "Exposed or hurt — seeking darkness";
                chosenTarget = null;
            }
            case AMBUSH_FROM_DARKNESS -> {
                urgency = GoalUrgency.NORMAL;
                interruptible = true;
                reason = "Repositioning to dark ambush position before engaging";
            }
            default -> {
                urgency = GoalUrgency.LOW;
                interruptible = true;
                reason = "Idle wandering";
                chosenTarget = null;
            }
        }

        var role = deriveRole(chosen, mob, blackboard, hasTarget, targetIsNearHive, criticalHealth);
        blackboard.set(AiKeys.XENO_ROLE, role);

        return PlannedGoal.of(
            chosen,
            chosenScore,
            tick,
            40,
            200,
            chosenTarget,
            chosenDest,
            urgency,
            interruptible,
            reason
        );
    }

    @SuppressWarnings("unused")
    private static XenoRole deriveRole(
        AiGoalType chosen,
        XenomorphEntity mob,
        Blackboard blackboard,
        boolean hasTarget,
        boolean targetNearHive,
        boolean lowHealth
    ) {
        if (!mob.getPassengers().isEmpty())
            return XenoRole.CARRIER;
        return switch (chosen) {
            case HUNT_TARGET -> XenoRole.HUNTER;
            case AMBUSH_TARGET, SEEK_DARKNESS,
                AMBUSH_FROM_DARKNESS -> XenoRole.STALKER;
            case DEFEND_HIVE -> XenoRole.DEFENDER;
            case EXPAND_HIVE -> XenoRole.HIVE_SPREADER;
            case RETREAT_TO_RESIN -> XenoRole.RETREATER;
            case BREAK_OBSTACLE, INVESTIGATE -> hasTarget ? XenoRole.HUNTER : XenoRole.STALKER;
            default -> XenoRole.IDLE;
        };
    }

    private static PlanFeedback readFeedback(Blackboard blackboard, int tick) {
        var full = blackboard.get(AiKeys.LAST_PLAN_FEEDBACK, PlanFeedback.class);
        if (full != null)
            return full;
        var raw = blackboard.get(AiKeys.LAST_FAILURE_REASON, PlanFailureReason.class);
        if (raw != null && raw != PlanFailureReason.NONE) {
            var activeGoal = blackboard.get(AiKeys.ACTIVE_GOAL_TYPE, AiGoalType.class);
            return PlanFeedback.of(raw, tick, null, activeGoal != null ? activeGoal : AiGoalType.NONE);
        }
        return null;
    }

    private static boolean isTargetFacingMob(LivingEntity target, XenomorphEntity mob) {
        var toMob = mob.position().subtract(target.position()).normalize();
        return target.getLookAngle().dot(toMob) > 0.5D;
    }

    private static String buildReason(String base, PlanFeedback feedback) {
        if (feedback == null || feedback.isNone())
            return base;
        return base + " [after " + feedback.reason().name() + "]";
    }
}
