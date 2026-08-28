package mod.azure.ovomorphosis.ai.goap;

public enum AiGoalType {
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
    BREAK_OBSTACLE,
}
