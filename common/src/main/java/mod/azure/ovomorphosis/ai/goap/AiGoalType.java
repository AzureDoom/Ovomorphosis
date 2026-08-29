package mod.azure.ovomorphosis.ai.goap;

import com.azure.azurecortex.api.goal.Goal;

/**
 * Ovomorphosis's own goal-type enum, shared across all three creature stages (Facehugger, Chestburster, Xenomorph).
 * <p>
 * AzureCortex ships no fixed goal-type enum of its own — every mod using the framework supplies one implementing
 * {@link Goal} (see AzureCortex's GOAP-Planning wiki page). A single enum shared across creature stages, rather than
 * one per stage, was already how Ovomorphosis modeled this before the extraction; nothing about that changes here, only
 * the interface it implements.
 */
public enum AiGoalType implements Goal {

    NONE,

    // Shared
    SURVIVE,
    INVESTIGATE,
    WANDER,

    // Facehugger
    INFECT_HOST,
    STALK_HOST,
    RETREAT_AND_HIDE,

    // Chestburster
    FIND_FOOD,
    HIDE,
    GROW_SAFE,

    // Xenomorph
    HUNT_TARGET,
    AMBUSH_TARGET,
    CAPTURE_HOST,
    EXPAND_HIVE,
    KILL_LIGHTS,
    DEFEND_HIVE,
    RETREAT_TO_RESIN,
    SEEK_DARKNESS,
    AMBUSH_FROM_DARKNESS,
    LURE_TARGET,
    VENT_TRAVERSAL,
    BREAK_OBSTACLE;

    @Override
    public boolean isNone() {
        return this == NONE;
    }
}
