package mod.azure.ovomorphosis.ai.core;

import mod.azure.ovomorphosis.ai.actions.xenomorph.FleeFireAction;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.goap.PlanFailureReason;
import mod.azure.ovomorphosis.ai.goap.PlanFeedback;
import mod.azure.ovomorphosis.ai.goap.PlannedGoal;
import mod.azure.ovomorphosis.ai.util.HiveMemory;

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

    /** Last confirmed world position where the active target was seen. Used by INVESTIGATE goals. */
    public static final String LAST_SEEN_POS = "last_seen_pos";

    /** Alias kept for legacy action references; prefer {@link #LAST_SEEN_POS} for new code. */
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

    /** Cooldown between resin block placements (Xenomorph hive building). */
    public static final String RESIN_PLACE_COOLDOWN = "resin_place_cooldown";

    /** Cooldown between carry-to-web actions (Xenomorph). */
    public static final String CARRY_COOLDOWN = "carry_cooldown";

    /** Cooldown between light-source destruction scans (Xenomorph). */
    public static final String LIGHT_SCAN_COOLDOWN = "light_scan_cooldown";

    /**
     * Set to {@code true} by {@code BreakToTargetAction} when it determines a block needs to be broken to reach the
     * target. Cleared when the break completes or the target changes.
     */
    public static final String BREAK_TO_TARGET_TRIGGER = "break_to_target_trigger";

    /** BlockPos of the specific block that {@code BreakToTargetAction} is currently targeting. */
    public static final String BREAK_TO_TARGET_SCAN = "break_to_target_scan";

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

    public static final String LAST_FIRE_POS = "last_fire_pos";

    public static final String HIVE_SYNC_COOLDOWN = "hive_sync_cooldown";
}
