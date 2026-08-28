package mod.azure.ovomorphosis.entities.xenomorph;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

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
import mod.azure.ovomorphosis.data.OvomorphosisSavedData;
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

    /**
     * How stale {@link mod.azure.ovomorphosis.ai.core.AiKeys#LAST_SEEN_TICK} can be before INVESTIGATE gives up on
     * extrapolating an interception point and just walks to the raw last-seen block instead. Beyond this, the target
     * could plausibly be almost anywhere, so guessing a specific heading stops being worth the confidence it implies.
     */
    private static final int MAX_PREDICTION_STALENESS_TICKS = 60;

    /**
     * Minimum horizontal speed (blocks/tick) the target must have had when last seen for INVESTIGATE to bother
     * extrapolating ahead at all. Below this they were essentially standing still, so the last-seen block already is
     * the best guess and a "predicted" point would just be jitter.
     */
    private static final double MIN_PREDICTION_SPEED = 0.02D;

    /** Floor on how far ahead of the last-seen block to search, once extrapolation is worth doing at all. */
    private static final double MIN_PREDICTION_DISTANCE = 2.0D;

    /**
     * Ceiling on how far ahead of the last-seen block to search, regardless of how fast the target was moving or how
     * long they've been out of sight — keeps a lucky sprint-away from sending the mob on a long, low-confidence beeline
     * instead of a search.
     */
    private static final double MAX_PREDICTION_DISTANCE = 8.0D;

    /**
     * Per-eligible-planning-cycle chance of a healthy, actively-pursued mob choosing to feign retreat toward a dark
     * ambush spot instead of continuing to press the fight (see {@link AiGoalType#LURE_TARGET}). Deliberately low —
     * combined with {@link mod.azure.ovomorphosis.ai.core.AiKeys#LURE_COOLDOWN} below, this is meant to surface only
     * occasionally across a long fight, not on a predictable schedule.
     */
    private static final float LURE_CHANCE = 0.08f;

    /** Below this squared distance the pursuer is already close enough that fighting beats fleeing toward a lure. */
    private static final double LURE_MIN_PURSUIT_DIST_SQ = 3.0D * 3.0D;

    /** Beyond this squared distance the target isn't credibly "in pursuit" — too far off to be actively chasing. */
    private static final double LURE_MAX_PURSUIT_DIST_SQ = 20.0D * 20.0D;

    /** Minimum ticks between LURE_TARGET selections (30s), set only when it's actually the winning goal. */
    private static final int LURE_COOLDOWN_TICKS = 600;

    /**
     * Multiplier applied to straight-line distance to approximate the ordinary route's real cost for
     * {@link HiveMemory#findVentShortcut} — actual pathfinding winds around obstacles and (for a wall-crawler) through
     * tunnels, so straight-line distance alone understates it. This is a heuristic, not a literal A* node count; see
     * {@code PlaceResinAction}/{@code HiveMemory} docs for why splicing vent edges into the real pathfinder wasn't
     * attempted.
     */
    private static final double ORDINARY_ROUTE_ESTIMATE_MULTIPLIER = 1.6D;

    /** Minimum straight-line distance to the target before a vent shortcut is even worth evaluating. */
    private static final double VENT_MIN_TARGET_DIST = 15.0D;

    /**
     * Radius (blocks) scanned by {@link HiveMemory#syncVentBlocksNear} — see the vent-sync cooldown below.
     * <p>
     * Deliberately wide relative to {@link #VENT_MIN_TARGET_DIST}: a freshly-summoned xenomorph's hive doesn't exist
     * until its very first tick, and gets created at wherever <em>it</em> happens to be standing at that moment (see
     * {@code XenomorphEntity#ensureHiveAssignment}) — not wherever a player may have already built vent infrastructure.
     * A vent placed before that first tick is therefore never linked to any hive at placement time
     * ({@code VentBlock#onPlace} finds no hive yet to register with), so this scan, run around both the mob and its
     * target once it has one, is the only way such a pre-existing vent ever gets discovered at all.
     */
    private static final int VENT_SYNC_RADIUS = 32;

    /**
     * Minimum ticks between {@link HiveMemory#syncVentBlocksNear} scans. That scan is a real block-state scan (not a
     * cheap registry lookup), so it's only run occasionally — mainly as a safety net for vent blocks that predate the
     * placement hook (e.g. hand-placed before this scan existed, or before the mob's hive existed at all — see
     * {@link #VENT_SYNC_RADIUS}); ordinarily a vent block registers itself immediately on placement and there's nothing
     * left for this scan to find.
     */
    private static final int VENT_SYNC_COOLDOWN_TICKS = 150;

    /** Minimum ticks between VENT_TRAVERSAL selections, set only once it's actually the winning goal. */
    private static final int VENT_TRAVERSAL_COOLDOWN_TICKS = 200;

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
        var targetFacingMob = false;

        if (hasTarget) {
            var distSq = mob.distanceToSqr(target);
            targetFacingMob = isTargetFacingMob(target, mob);

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

        // --- Deliberate false retreat (LURE_TARGET) -------------------------------------------------------------
        // Distinct from the health-driven retreat above: this is a *healthy* mob choosing to disengage, not one
        // that's losing. A pursuing target plus a known dark ambush route is an opportunity to draw them somewhere
        // less favorable for them rather than only ever meeting the chase head-on. "Pursuing" is approximated by the
        // target actively facing the mob within a plausible chase distance — neither already at melee range (no
        // point luring instead of just fighting) nor far enough off that they're not really giving chase.
        var pursuitDistSq = hasTarget ? mob.distanceToSqr(target) : 0.0;
        var targetIsPursuing = hasTarget
            && targetFacingMob
            && pursuitDistSq >= LURE_MIN_PURSUIT_DIST_SQ
            && pursuitDistSq <= LURE_MAX_PURSUIT_DIST_SQ;

        var lureScore = 0f;
        if (
            healthyAggressive
                && targetIsPursuing
                && darkHaven.isPresent()
                && cooldowns.ready(AiKeys.LURE_COOLDOWN)
                && mob.getRandom().nextFloat() < LURE_CHANCE
        ) {
            // Scored high enough to win when it clears the gate above, but the gate itself (low roll chance +
            // long cooldown once actually chosen, set below in the destination switch) is what keeps this rare —
            // not a low score here, which would just make it inconsistent rather than infrequent.
            lureScore = 65f;
        }

        // --- Vent shortcut (VENT_TRAVERSAL) ---------------------------------------------------------------------
        // A long-distance pathfinding shortcut through the hive's own vent network (see HiveMemory#findVentShortcut)
        // — only worth evaluating when actively hunting a target far enough away that a shortcut could plausibly
        // help, and gated by its own cooldown so a mob doesn't duck in and out of vents on every replan. Finding a
        // route is inherently restricted to vents this hive itself built/registered, which is what makes vent travel
        // a mature-hive perk rather than a free shortcut available to any lone xenomorph.
        HiveMemory.VentRoute ventRoute = null;
        if (memory != null && hasTarget && cooldowns.ready(AiKeys.VENT_SYNC_COOLDOWN)) {
            // Safety net for vent blocks that predate/bypass the placement hook (see VentBlock) — cheap registry
            // lookups aren't an option here since there's no world-wide vent registry, only this hive's own map, so
            // this has to be an actual (bounded, cooldown-gated) block scan. Scanned around both the mob and its
            // target, since a pre-existing vent could plausibly be near either one.
            var foundNearMob = memory.syncVentBlocksNear(mob.level(), mob.blockPosition(), VENT_SYNC_RADIUS);
            var foundNearTarget = memory.syncVentBlocksNear(mob.level(), target.blockPosition(), VENT_SYNC_RADIUS);
            if ((foundNearMob || foundNearTarget) && mob.level() instanceof ServerLevel serverLevel) {
                // Mutating the HiveMemory in place doesn't itself mark the underlying SavedData dirty — without
                // this, a newly-discovered vent would register correctly in memory but silently never make it into
                // the world save.
                OvomorphosisSavedData.markHiveDirty(serverLevel);
            }
            cooldowns.set(AiKeys.VENT_SYNC_COOLDOWN, VENT_SYNC_COOLDOWN_TICKS);
        }

        if (
            memory != null
                && hasTarget
                && cooldowns.ready(AiKeys.VENT_TRAVERSAL_COOLDOWN)
                && mob.distanceToSqr(target) >= VENT_MIN_TARGET_DIST * VENT_MIN_TARGET_DIST
        ) {
            var ordinaryEstimate = Math.sqrt(mob.distanceToSqr(target)) * ORDINARY_ROUTE_ESTIMATE_MULTIPLIER;
            ventRoute = memory
                .findVentShortcut(mob.level(), mob.blockPosition(), target.blockPosition(), ordinaryEstimate)
                .orElse(null);
        }

        // Scored to comfortably beat every other pursuit-goal's practical ceiling (DEFEND_HIVE alone can reach 85,
        // plus stacking bonuses and hysteresis push several goals well past 100) — a found vent route isn't really
        // "competing" with those goals for a different objective, it's a strictly faster way to reach the same
        // target once the cost-margin gate above has already judged it worthwhile, so it should win outright rather
        // than get outscored by whichever combat posture happens to be active.
        var ventScore = ventRoute != null ? 150f : 0f;

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
                    hiveScore -= PENALTY_FAILED;
                    wanderScore += 25f;
                    gfc.recordFailure(AiGoalType.EXPAND_HIVE, tick, 60);
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
        lureScore -= gfc.getPenalty(AiGoalType.LURE_TARGET, tick);
        ventScore -= gfc.getPenalty(AiGoalType.VENT_TRAVERSAL, tick);

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
        lureScore = Math.max(0f, lureScore);
        ventScore = Math.max(0f, ventScore);

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
                case LURE_TARGET -> lureScore += HYSTERESIS_BONUS;
                case VENT_TRAVERSAL -> ventScore += HYSTERESIS_BONUS;
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
                new Candidate(AiGoalType.LURE_TARGET, lureScore),
                new Candidate(AiGoalType.VENT_TRAVERSAL, ventScore),
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
                    ? predictInterceptPosition(mob, blackboard, lastSeenPos, tick)
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
            case LURE_TARGET -> {
                urgency = GoalUrgency.NORMAL;
                interruptible = true;
                reason = "Feigning retreat to lure pursuer into a dark ambush";
                chosenDest = darkHaven.orElse(null);
                // Only set once this is actually the winning goal (not just eligible) — this is what makes the
                // rarity in the scoring gate above stick, rather than the roll passing again on the very next
                // eligible cycle.
                cooldowns.set(AiKeys.LURE_COOLDOWN, LURE_COOLDOWN_TICKS);
            }
            case VENT_TRAVERSAL -> {
                urgency = GoalUrgency.NORMAL;
                interruptible = true;
                reason = "Taking a known vent shortcut toward the target";
                if (ventRoute != null) {
                    // GOAL_DESTINATION (from chosenDest) drives the ordinary walk-to-entrance leg; VENT_ENTRANCE/
                    // VENT_EXIT are read directly by VentTraversalAction once the mob arrives there — see
                    // XenomorphTree's VENT_TRAVERSAL branch.
                    chosenDest = ventRoute.entrance();
                    blackboard.set(AiKeys.VENT_ENTRANCE, ventRoute.entrance());
                    blackboard.set(AiKeys.VENT_EXIT, ventRoute.exit());
                    cooldowns.set(AiKeys.VENT_TRAVERSAL_COOLDOWN, VENT_TRAVERSAL_COOLDOWN_TICKS);
                }
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
                AMBUSH_FROM_DARKNESS, LURE_TARGET -> XenoRole.STALKER;
            case VENT_TRAVERSAL -> hasTarget ? XenoRole.HUNTER : XenoRole.STALKER;
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

    /**
     * Extrapolates a believable search point beyond {@code lastSeenPos} using the target's heading when last seen
     * ({@code lastSeenPos + normalize(lastSeenVelocity) * predictionDistance}), instead of INVESTIGATE only ever
     * walking to the exact last-seen block. This is what lets a mob that loses sight of a target rounding a corner
     * plausibly cut them off further down a hallway rather than beelining for the doorway and only re-acquiring once
     * it's already there.
     * <p>
     * Falls back to the raw {@code lastSeenPos} whenever extrapolating isn't warranted: no recorded velocity, the
     * sighting is stale enough ({@link #MAX_PREDICTION_STALENESS_TICKS}) that the target could plausibly be almost
     * anywhere, the target was essentially stationary when last seen ({@link #MIN_PREDICTION_SPEED}), or the projected
     * point lands somewhere clearly not stand-able (e.g. embedded in the wall around the very corner that broke line of
     * sight).
     */
    private static BlockPos predictInterceptPosition(
        XenomorphEntity mob,
        Blackboard blackboard,
        BlockPos lastSeenPos,
        int tick
    ) {
        var velocity = blackboard.get(AiKeys.LAST_SEEN_VELOCITY, Vec3.class);
        var lastSeenTick = blackboard.get(AiKeys.LAST_SEEN_TICK, Integer.class);

        if (velocity == null || lastSeenTick == null)
            return lastSeenPos;

        var elapsed = tick - lastSeenTick;
        if (elapsed < 0 || elapsed > MAX_PREDICTION_STALENESS_TICKS)
            return lastSeenPos;

        var horizSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (horizSpeed < MIN_PREDICTION_SPEED)
            return lastSeenPos;

        var predictionDistance = Mth.clamp(horizSpeed * elapsed, MIN_PREDICTION_DISTANCE, MAX_PREDICTION_DISTANCE);
        var dirX = velocity.x / horizSpeed;
        var dirZ = velocity.z / horizSpeed;

        var predicted = new BlockPos(
            Mth.floor(lastSeenPos.getX() + dirX * predictionDistance),
            lastSeenPos.getY(),
            Mth.floor(lastSeenPos.getZ() + dirZ * predictionDistance)
        );

        return isStandable(mob.level(), predicted) ? predicted : lastSeenPos;
    }

    /** {@code true} if {@code pos} is open space with solid footing beneath it — a plausible place to search. */
    private static boolean isStandable(Level level, BlockPos pos) {
        var below = pos.below();
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
            && !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
    }

    private static String buildReason(String base, PlanFeedback feedback) {
        if (feedback == null || feedback.isNone())
            return base;
        return base + " [after " + feedback.reason().name() + "]";
    }
}
