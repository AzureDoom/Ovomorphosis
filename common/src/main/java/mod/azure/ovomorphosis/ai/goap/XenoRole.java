package mod.azure.ovomorphosis.ai.goap;

import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.entities.xenomorph.XenomorphEntity;

/**
 * Soft, temporary intent label assigned to a {@link XenomorphEntity} by the planner each planning cycle.
 * <h3>Purpose</h3> Roles are not hard-coded behavior; they are a compact summary of what the planner decided this
 * cycle, written to the blackboard so the behavior tree and other systems can read a single enum rather than
 * re-deriving intent from a combination of goal type + target classification keys.
 * <p>
 * Multiple xenomorphs near each other will naturally end up with different roles based on their individual world state
 * — one may be {@link #STALKER} because its target isn't facing it, another {@link #DEFENDER} because it's near the
 * hive, a third {@link #RETREATER} because it's low health. This produces the appearance of coordinated dynamic
 * behavior without explicit squad logic.
 * <h3>Usage</h3> Read from {@link AiKeys#XENO_ROLE} in the behavior tree to adjust movement speed, animation choices,
 * or action gating:
 *
 * <pre>{@code
 * var role = blackboard.get(AiKeys.XENO_ROLE, XenoRole.class);
 * if (role == XenoRole.STALKER) {
 *     return BehaviorResult.run(moveToTargetAmbush, 19);
 * }
 * }</pre>
 */
public enum XenoRole {

    /**
     * Default — no strong intent. Wander, expand hive, destroy lights. Assigned when no other role condition is met.
     */
    IDLE,

    /**
     * Actively pursuing a confirmed target at full speed. Assigned when {@link AiGoalType#HUNT_TARGET} is chosen and
     * the target is not a fire user.
     */
    HUNTER,

    /**
     * Closing distance quietly on an unaware target. Assigned when {@link AiGoalType#AMBUSH_TARGET} or
     * {@link AiGoalType#SEEK_DARKNESS} is chosen, or when HUNT_TARGET is chosen but the target is not yet facing the
     * mob.
     */
    STALKER,

    /**
     * Carrying a victim toward a resin web for deposit. Assigned when the mob has a passenger (victim is riding it).
     * Tree can use this to skip combat branch evaluation while carrying.
     */
    CARRIER,

    /**
     * Placing resin, carrying victims, or expanding the hive network. Assigned when {@link AiGoalType#EXPAND_HIVE} is
     * chosen.
     */
    HIVE_SPREADER,

    /**
     * Guarding hive territory against an intruder. Assigned when {@link AiGoalType#DEFEND_HIVE} is chosen. Tree skips
     * cooldown checks and commits to close-range combat immediately.
     */
    DEFENDER,

    /**
     * Withdrawing from combat due to low health, overwhelming danger, or fire exposure. Assigned when
     * {@link AiGoalType#RETREAT_TO_RESIN} or {@link AiGoalType#AMBUSH_FROM_DARKNESS} is chosen as a health-driven goal.
     */
    RETREATER,
}
