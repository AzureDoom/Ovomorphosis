package mod.azure.ovomorphosis.ai.core;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import mod.azure.ovomorphosis.ai.actions.FleeFireAction;
import mod.azure.ovomorphosis.ai.goap.*;
import mod.azure.ovomorphosis.ai.roles.XenoRole;
import mod.azure.ovomorphosis.ai.util.HiveMemory;
import mod.azure.ovomorphosis.entities.xenomorph.XenomorphGoalPlanner;

/**
 * Typed blackboard keys shared across all Ovomorphosis mobs.
 * <p>
 * Keys are intentionally {@code String} constants so they remain readable in debug output. Use the typed
 * {@link Blackboard#get}/{@link Blackboard#set} helpers to avoid raw casts.
 */
public final class AiKeys {

    private AiKeys() {}

    public static final String TARGET = "target";

    public static final String GOAL_TARGET = "goal_target";

    /**
     * Last confirmed world position where the active target was seen. Used by INVESTIGATE goals.
     * <p>
     * Written by {@link mod.azure.ovomorphosis.ai.util.TargetingSystem} only on ticks where the mob has genuine,
     * unobstructed line of sight to the target — as opposed to {@link #LAST_KNOWN_TARGET_POS}, which tracks the
     * target's live position regardless of visibility. That distinction is what makes this key meaningful as a "last
     * seen" snapshot: it freezes the moment sight is lost (e.g. the target ducks around a corner) rather than
     * continuing to update, giving {@link #LAST_SEEN_VELOCITY}/{@link #LAST_SEEN_TICK} something genuine to extrapolate
     * a search point from.
     */
    public static final String LAST_SEEN_POS = "last_seen_pos";

    /**
     * The target's horizontal velocity ({@link net.minecraft.world.phys.Vec3}, y always {@code 0}) at the moment
     * {@link #LAST_SEEN_POS} was last updated. Used by INVESTIGATE goals to extrapolate a believable interception point
     * ({@code lastSeenPos + normalize(lastSeenVelocity) * predictionDistance}) rather than only ever walking to the
     * exact last-seen block.
     */
    public static final String LAST_SEEN_VELOCITY = "last_seen_velocity";

    /**
     * Game tick at which {@link #LAST_SEEN_POS} was last updated. Lets INVESTIGATE goals scale how far to extrapolate
     * ahead by how long the target has actually been out of sight, and discard the prediction entirely once it's stale
     * enough that the target could be almost anywhere.
     */
    public static final String LAST_SEEN_TICK = "last_seen_tick";

    /**
     * Wherever the target currently is, updated every tick it's alive regardless of line of sight. Distinct from
     * {@link #LAST_SEEN_POS}, which only updates when the target is genuinely visible.
     */
    public static final String LAST_KNOWN_TARGET_POS = "last_known_target_pos";

    /**
     * Navigation destination set by the planner for the current goal. Actions should read this rather than computing
     * their own destination from the target.
     */
    public static final String GOAL_DESTINATION = "goal_destination";

    /**
     * Low-level destination consumed by {@code MoveToDestinationAction}. Set by actions that need to drive the
     * navigator directly (e.g. RetreatAndHideAction, WanderAction).
     */
    public static final String DESTINATION = "destination";

    /** Type: {@link PlannedGoal}. The full goal record currently committed by the planner. */
    public static final String ACTIVE_GOAL = "active_goal";

    /** Type: {@link AiGoalType}. Convenience shorthand extracted from {@link #ACTIVE_GOAL}. */
    public static final String ACTIVE_GOAL_TYPE = "active_goal_type";

    /** Human-readable string explaining why the planner chose the current goal. */
    public static final String LAST_GOAL_REASON = "last_goal_reason";

    /**
     * Prevents the passive (non-threat) branch of the behavior tree from ticking every frame. Set to a high value on
     * passive-action start; cleared to 1 on interruption.
     */
    public static final String PASSIVE_DECISION = "passive_decision";

    /**
     * Rate-limits how often the GOAP planner is allowed to re-evaluate goals. Typically set to 20 ticks after each
     * replan.
     */
    public static final String GOAL_REPLAN = "goal_replan";

    /** Cooldown between leap-and-attach attempts (Facehugger). */
    public static final String GRAB_COOLDOWN = "grab_cooldown";

    /**
     * Game tick at which a mob was first noticed swimming with no live target and no blackboard destination.
     * {@code SwimAction} uses this to give the mob a brief grace period of ordinary bobbing before it commits to
     * beelining for the nearest shore — without it, any mob that happens to be idle while merely wet immediately
     * abandons whatever it was doing to force its way onto land.
     */
    public static final String SWIM_STRANDED_SINCE_TICK = "swim_stranded_since_tick";

    /** Cooldown between resin block placements (Xenomorph hive building). */
    public static final String RESIN_PLACE_COOLDOWN = "resin_place_cooldown";

    /** Cooldown between carry-to-web actions (Xenomorph). */
    public static final String CARRY_COOLDOWN = "carry_cooldown";

    /** Cooldown between light-source destruction scans (Xenomorph). */
    public static final String LIGHT_SCAN_COOLDOWN = "light_scan_cooldown";

    /**
     * Cooldown between LURE_TARGET selections (Xenomorph). Deliberately long relative to how rarely the goal scores
     * highly in the first place — see {@code XenomorphGoalPlanner}'s lure scoring — so a false-retreat-into-ambush
     * doesn't repeat often enough for a player to recognize it as a scripted mechanic.
     */
    public static final String LURE_COOLDOWN = "lure_cooldown";

    /**
     * Vent entrance position chosen by the planner for
     * {@link mod.azure.ovomorphosis.ai.goap.AiGoalType#VENT_TRAVERSAL}. Type: {@link BlockPos}.
     */
    public static final String VENT_ENTRANCE = "vent_entrance";

    /**
     * Vent exit position paired with {@link #VENT_ENTRANCE} for the current
     * {@link mod.azure.ovomorphosis.ai.goap.AiGoalType#VENT_TRAVERSAL}. Type: {@link BlockPos}.
     */
    public static final String VENT_EXIT = "vent_exit";

    /**
     * Cooldown between VENT_TRAVERSAL selections (Xenomorph). Prevents a mob from ducking in and out of vents on every
     * single replan even when a shortcut remains technically available.
     */
    public static final String VENT_TRAVERSAL_COOLDOWN = "vent_traversal_cooldown";

    /** Cooldown between {@code HiveMemory#syncVentBlocksNear} scans — see that method's docs for why it's gated. */
    public static final String VENT_SYNC_COOLDOWN = "vent_sync_cooldown";

    /**
     * Set to {@code true} by {@code BreakToTargetAction} when it determines a block needs to be broken to reach the
     * target. Cleared when the break completes or the target changes.
     */
    public static final String BREAK_TO_TARGET_TRIGGER = "break_to_target_trigger";

    /** BlockPos of the specific block that {@code BreakToTargetAction} is currently targeting. */
    public static final String BREAK_TO_TARGET_SCAN = "break_to_target_scan";

    /**
     * Set to {@code true} by {@code BreakToTargetAction} whenever it finishes resolving an attempt entered purely
     * because {@code ACTIVE_GOAL_TYPE == BREAK_OBSTACLE} (as opposed to a fresh, concrete
     * {@link #BREAK_TO_TARGET_TRIGGER}). {@code ACTIVE_GOAL_TYPE} stays {@code BREAK_OBSTACLE} for the planner's whole
     * commit window regardless of whether the obstruction was already cleared, so without this flag the tree would
     * re-enter {@code BreakToTargetAction} on that stale goal type every tick — forever restarting an action with
     * nothing left to do, and never falling through to movement/combat branches. Cleared by
     * {@code GoalApplicator.apply} whenever a fresh goal is committed. A genuinely new {@link #BREAK_TO_TARGET_TRIGGER}
     * (set by {@code MoveToTargetAction} detecting a real, current obstruction) always bypasses this flag entirely — it
     * only gates the stale-goal-type fallback path.
     */
    public static final String BREAK_TO_TARGET_EXHAUSTED = "break_to_target_exhausted";

    /** Type: {@link HiveMemory}. Shared hive state read by WanderAction (dark preference) and hive-building actions. */
    public static final String HIVE_MEMORY = "hive_memory";

    /**
     * Running count of how many times the active goal has been abandoned with {@code FAILURE}. Reset when a new goal
     * type is committed. Used for planner fallback heuristics.
     */
    public static final String FAILED_GOAL_COUNT = "failed_goal_count";

    /**
     * Written by actions when they fail or are interrupted. Type: {@link PlanFeedback}.
     * <p>
     * The planner reads this on the next planning cycle, uses it to bias goal scores, then clears it so stale feedback
     * does not persist. Cleared automatically by {@code GoalApplicator.apply}.
     */
    public static final String LAST_PLAN_FEEDBACK = "last_plan_feedback";

    /**
     * Convenience shorthand — actions that only need to record a Reason code (not a full {@link PlanFeedback}) can
     * write here. The planner wraps this into a {@link PlanFeedback} if {@link #LAST_PLAN_FEEDBACK} is not already set.
     * Type: {@link PlanFailureReason}. Cleared automatically by {@code GoalApplicator.apply}.
     */
    public static final String LAST_FAILURE_REASON = "last_failure_reason";

    /**
     * Cooldown between dodge attempts. Prevents spamming DodgeProjectileAction. Type: cooldown ticks (int).
     */
    public static final String DODGE_COOLDOWN = "dodge_cooldown";

    /**
     * Lunge action cooldown. Prevents LungeAction from firing every tick. Type: cooldown ticks (int).
     */
    public static final String LUNGE_COOLDOWN = "lunge_cooldown";

    /**
     * Persistent fire tolerance counter for FleeFireAction. Accumulates while fire is nearby; decays while fleeing.
     * Survives action restarts. Type: {@link Float}.
     */
    public static final String FIRE_TOLERANCE = "fire_tolerance";

    /**
     * Game tick timestamp after which flee-fire can trigger again. Set by {@link FleeFireAction} on success. Type:
     * {@link Integer} (game tick expiry).
     */
    public static final String FIRE_FLEE_COOLDOWN = "fire_flee_cooldown";

    /** Last known position of an environmental fire source. Type: {@link BlockPos}. */
    public static final String LAST_FIRE_POS = "last_fire_pos";

    /**
     * The entity that most recently caused fire damage to or near this mob (flint-and-steel user, fire arrow shooter,
     * lava-bucket placer detected via fire proximity). Type: {@link LivingEntity}.
     * <p>
     * Written by {@link FleeFireAction} when it detects fire near a known attacker. Cleared when
     * {@link #FIRE_DANGER_UNTIL_TICK} expires. The planner uses this to switch from direct combat to flanking or
     * cautious hive-defense posture against the attacker.
     */
    public static final String LAST_FIRE_ATTACKER = "last_fire_attacker";

    /**
     * {@code true} when the current {@link #TARGET} is the same entity recorded in {@link #LAST_FIRE_ATTACKER}.
     * Recomputed by the planner each cycle; cached here so the behavior tree can gate lunge/charge without re-querying
     * the attacker every tick. Type: {@link Boolean}.
     */
    public static final String TARGET_IS_FIRE_USER = "target_is_fire_user";

    /**
     * Game tick timestamp until which the xenomorph considers fire a serious danger from a specific attacker. Set to
     * {@code currentTick + 200} (10 s) when a fire attacker is recorded; extended on repeated fire events. The planner
     * degrades fire-user penalties once this expires. Type: {@link Integer}.
     */
    public static final String FIRE_DANGER_UNTIL_TICK = "fire_danger_until_tick";

    public static final String HIVE_SYNC_COOLDOWN = "hive_sync_cooldown";

    public static final String GOAL_FAILURE_COOLDOWNS = "goal_failure_cooldowns";

    /** {@code true} when target is holding a ranged weapon or has fired a projectile recently. Type: Boolean. */
    public static final String TARGET_IS_RANGED = "target_is_ranged";

    /**
     * {@code true} when no other non-alien living entity is within 12 blocks of the target. Influences carry/capture
     * scoring. Type: Boolean.
     */
    public static final String TARGET_IS_ISOLATED = "target_is_isolated";

    /** {@code true} when target is within 20 blocks of the nearest resin web cross. Type: Boolean. */
    public static final String TARGET_IS_NEAR_HIVE = "target_is_near_hive";

    /** {@code true} when target has at least two filled armor slots. Type: Boolean. */
    public static final String TARGET_IS_ARMORED = "target_is_armored";

    /** {@code true} when target passes the facehugger host validity test (can be infected). Type: Boolean. */
    public static final String TARGET_IS_VALID_HOST = "target_is_valid_host";

    /**
     * {@code true} when the target is a danger entity, ranged and at distance, heavily armored, or on the grab
     * blacklist. Suppresses grab and carry scoring. Type: Boolean.
     */
    public static final String TARGET_IS_TOO_DANGEROUS_TO_GRAB = "target_is_too_dangerous_to_grab";

    /**
     * The current soft intent role for this xenomorph. Type: {@link XenoRole}. Updated every planning cycle by
     * {@link XenomorphGoalPlanner}.
     */
    public static final String XENO_ROLE = "xeno_role";

    /**
     * Type: {@link mod.azure.ovomorphosis.ai.goap.WorldStateSnapshot}. The coarse world-state facts on record for
     * whatever plan is currently in {@link #ACTIVE_GOAL}, captured by
     * {@link mod.azure.ovomorphosis.ai.goap.GoalApplicator#apply} and compared against live state every tick by
     * {@link mod.azure.ovomorphosis.ai.goap.PlanInvalidation} to force a replan the moment they diverge, independent of
     * {@link #LAST_PLAN_FEEDBACK}.
     */
    public static final String PLAN_WORLD_STATE = "plan_world_state";

    public static final String IGNORED_FOOD = "burster_ignored_food";
}
