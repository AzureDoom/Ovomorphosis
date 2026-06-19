package mod.azure.xenogenesis.ai.core;

import mod.azure.xenogenesis.ai.actions.xenomorph.GrabAndExecuteAction;

/**
 * Central repository of {@link Blackboard} key constants used across all AI actions and nodes.
 * <p>
 * Keys are grouped by the mob archetype that uses them (e.g., worker, hopper, sapper). Using constants here avoids
 * typo-prone inline string literals throughout the codebase.
 */
public final class AiKeys {

    /** The mob's current attack target, stored as a {@code LivingEntity}. */
    public static final String TARGET = "target";

    /** Tick-based cooldown preventing consecutive leap attacks. */
    public static final String LEAP_COOLDOWN = "leap_cooldown";

    /** The {@link mod.azure.xenogenesis.ai.hive.TacticalOrder} currently assigned to this mob. */
    public static final String TACTICAL_ORDER = "tactical_order";

    /** Reference to the wall-surface navigator used during crawl pathing. */
    public static final String SURFACE_NAVIGATOR = "surface_navigator";

    /** The last known {@code BlockPos} of the mob's target, retained when line of sight is lost. */
    public static final String LAST_KNOWN_TARGET_POS = "last_known_target_pos";

    /** Cooldown applied after a worker performs a cornered-state attack. */
    public static final String CORNERED_ATTACK_COOLDOWN = "worker:cornered_attack_cooldown";

    /** Boolean flag set when a worker mob has been cornered by the player. */
    public static final String IS_CORNERED = "worker:is_cornered";

    /** The {@code BlockPos} the mob is currently navigating toward. */
    public static final String DESTINATION = "destination";

    /** Boolean flag indicating the mob is currently using wall-crawl movement. */
    public static final String WALL_CRAWLING = "wall_crawling";

    /**
     * Cooldown applied after a {@link GrabAndExecuteAction} completes, preventing the xenomorph from immediately
     * grabbing again.
     */
    public static final String GRAB_COOLDOWN = "xenomorph:grab_cooldown";

    private AiKeys() {}
}
