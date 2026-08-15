package mod.azure.ovomorphosis.ai.goap;

import mod.azure.ovomorphosis.ai.core.AiKeys;

/**
 * Structured failure codes that actions report back to the GOAP planner.
 * <p>
 * As of the {@code ActionOutcome} contract, actions no longer write these to the blackboard themselves. Instead,
 * {@code Action.tick} returns {@code ActionOutcome.blocked(reason, ...)} or {@code ActionOutcome.failed(reason, ...)},
 * and {@code MobBrainRuntime} writes {@link AiKeys#LAST_PLAN_FEEDBACK} centrally from that return value. The planner
 * still reads {@link AiKeys#LAST_PLAN_FEEDBACK} on the next planning interval exactly as before; only how it gets there
 * has changed.
 *
 * <pre>{@code
 * // Inside any Action.tick():
 * if (path == null) {
 *     return ActionOutcome.failed(PlanFailureReason.FAILED_NO_PATH, mob.blockPosition());
 * }
 * }</pre>
 */
public enum PlanFailureReason {

    /** No failure recorded — default state. */
    NONE,

    /**
     * Pathfinder returned null or an empty path. GOAP response: raise score for {@link AiGoalType#BREAK_OBSTACLE} or
     * investigate from a different approach angle.
     */
    FAILED_NO_PATH,

    /**
     * Navigation was succeeding but the mob became stuck (no meaningful displacement over several ticks despite an
     * active path). GOAP response: same as {@link #FAILED_NO_PATH} but also consider trying a flanking route.
     */
    FAILED_STUCK,

    /**
     * The blackboard target became null, died, or left sensor range mid-action. GOAP response: raise score for
     * INVESTIGATE at the last-known position.
     */
    FAILED_TARGET_LOST,

    /**
     * Required resin/web infrastructure was not present (e.g. CarryToWebAction found no web cross nearby). GOAP
     * response: raise score for {@link AiGoalType#EXPAND_HIVE} / place-web-cross.
     */
    FAILED_NO_WEB,

    /**
     * Ambient light level at the destination or along the route exceeded the mob's comfort threshold. GOAP response:
     * raise score for {@link AiGoalType#KILL_LIGHTS}.
     */
    FAILED_TOO_BRIGHT,

    /**
     * Physical obstacle (non-breakable block, entity, etc.) prevents the action from completing. GOAP response: raise
     * score for a break-obstacle or detour action.
     */
    FAILED_BLOCKED,

    /**
     * A danger stimulus (fire, player retaliation, overwhelming force) was detected and continuing the action would be
     * suicidal. GOAP response: raise score for RETREAT / HIDE / RETREAT_TO_RESIN.
     */
    FAILED_DANGER,

    /**
     * The action's own cooldown or a shared cooldown prevented execution. GOAP response: suppress the goal score for
     * that goal type for the remainder of the cooldown window; prefer an alternative goal.
     */
    FAILED_COOLDOWN,

    /**
     * The action requires a specific precondition on the blackboard that is not satisfied (generic catch-all for
     * prerequisite failures not covered above).
     */
    FAILED_PRECONDITION,

    /**
     * The path to the target is obstructed by a block that is not tagged {@code WEAK_BLOCKS} or has hardness > 50. The
     * xenomorph cannot break through it. GOAP response: switch to {@link AiGoalType#INVESTIGATE} (try another approach
     * angle), {@link AiGoalType#AMBUSH_TARGET} (wait for target to move), or {@link AiGoalType#SEEK_DARKNESS}
     * (reposition tactically).
     */
    FAILED_OBSTACLE_UNBREAKABLE,

    /**
     * Resin/web placement found no valid candidate block at all (every scanned position was occupied, unreplaceable, or
     * lacked a sturdy adjacent face) — as distinct from {@link #FAILED_TOO_BRIGHT}, which means candidates might exist
     * but the ambient light rules them out. GOAP response: suppress {@link AiGoalType#EXPAND_HIVE} for a cooldown
     * window and prefer wandering/repositioning before recommitting to hive expansion at the same spot.
     */
    FAILED_NO_VALID_PLACEMENT,
}
