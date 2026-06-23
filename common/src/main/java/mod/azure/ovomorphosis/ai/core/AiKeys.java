package mod.azure.ovomorphosis.ai.core;

import mod.azure.ovomorphosis.ai.actions.MoveToTargetAction;
import mod.azure.ovomorphosis.ai.actions.xenomorph.*;
import mod.azure.ovomorphosis.ai.util.HiveMemory;

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

    /** The last known {@code BlockPos} of the mob's target, retained when line of sight is lost. */
    public static final String LAST_KNOWN_TARGET_POS = "last_known_target_pos";

    /** The {@code BlockPos} the mob is currently navigating toward. */
    public static final String DESTINATION = "destination";

    /**
     * Cooldown applied after a {@link GrabAndExecuteAction} completes, preventing the xenomorph from immediately
     * grabbing again.
     */
    public static final String GRAB_COOLDOWN = "grab_cooldown";

    /**
     * The {@link HiveMemory} instance tracking all resin blocks placed by this xenomorph. Stored as an object
     * reference; retrieve with {@code blackboard.get(AiKeys.HIVE_MEMORY, HiveMemory.class)}.
     */
    public static final String HIVE_MEMORY = "hive_memory";

    /**
     * Cooldown between individual resin-block placements. Set by {@link PlaceResinAction} after each successful
     * placement.
     */
    public static final String RESIN_PLACE_COOLDOWN = "resin_place_cooldown";

    /**
     * Cooldown applied after a carry-to-web action completes, preventing the xenomorph from immediately carrying
     * another target.
     */
    public static final String CARRY_COOLDOWN = "carry_cooldown";

    public static final String PASSIVE_DECISION = "passive_decision";

    /**
     * Scan-interval cooldown used internally by {@link BreakToTargetAction}.
     */
    public static final String BREAK_TO_TARGET_SCAN = "break_to_target_scan";

    /**
     * Flag written to the blackboard by {@link MoveToTargetAction} when the mob is stuck and a breakable block is
     * suspected to be the cause. {@link BreakToTargetAction} reads and clears this flag. Using a flag rather than a
     * permanent tree branch prevents the break action from blocking normal movement.
     */
    public static final String BREAK_TO_TARGET_TRIGGER = "break_to_target_trigger";

    /**
     * Scan-interval cooldown used internally by {@link DestroyLightSourceAction}.
     */
    public static final String LIGHT_SCAN_COOLDOWN = "destroy_light_scan";

    public static final String SEEK_COOLDOWN = "seek_dark_replan";

    private AiKeys() {}
}
