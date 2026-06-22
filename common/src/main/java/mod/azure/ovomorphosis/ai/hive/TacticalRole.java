package mod.azure.ovomorphosis.ai.hive;

/**
 * Defines the role a mob plays within its squad during coordinated combat.
 * <p>
 * Roles are assigned by a {@link TacticalCoordinator} and stored in {@link SquadBlackboard}. Each role maps to a
 * different destination and target priority in {@link SimpleTacticalCoordinator}.
 */
public enum TacticalRole {

    /** Engages the primary target directly; the highest-aggression role. */
    FRONTLINE,

    /** Attempts to attack from the side or engages a secondary target when within range. */
    FLANKER,

    /** Temporarily pulls back from the fight, used when a mob needs space or is low on health. */
    RETREATING,

    /** Hangs back to assist or harass; lower priority than frontline or flanker. */
    SUPPORT
}
