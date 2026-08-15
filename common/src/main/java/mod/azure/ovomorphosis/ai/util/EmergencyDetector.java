package mod.azure.ovomorphosis.ai.util;

import net.minecraft.world.entity.Mob;

import mod.azure.ovomorphosis.ai.actions.ExplosiveFleeAction;
import mod.azure.ovomorphosis.ai.actions.FleeFireAction;
import mod.azure.ovomorphosis.ai.goap.GoalApplicator;
import mod.azure.ovomorphosis.ai.goap.GoalUrgency;

/**
 * A cheap, pre-planner check for genuine emergencies (on fire, imminent explosion, critical health), used to supply
 * {@link GoalApplicator#shouldReplan(mod.azure.ovomorphosis.ai.core.Blackboard, int, GoalUrgency)} with a
 * {@code candidateUrgency} <em>before</em> the full {@code GoalPlanner.chooseGoal} has run.
 * <h3>Why this exists</h3> {@link GoalApplicator#shouldReplan} advertises an emergency override that bypasses the
 * min-commit lock, but that override only fires when the caller passes {@link GoalUrgency#EMERGENCY} as
 * {@code candidateUrgency}. Scoring candidates requires running the planner — which {@code shouldReplan} exists to gate
 * in the first place. Without a cheap upstream probe, the emergency override is advertised but effectively unreachable:
 * nothing ever supplies a non-null, non-default urgency until after the planner (which might not even be invoked) has
 * already run.
 * <p>
 * This class breaks that chicken-and-egg problem with a handful of O(1)/cheap-scan checks that mirror the same
 * conditions the behavior tree's reactive emergency branches (fire, explosion) already use, plus a critical-health
 * threshold. None of these allocate a full candidate list or score goals; they are safe to call every tick.
 */
public final class EmergencyDetector {

    private EmergencyDetector() {}

    /** Radius used for the explosive-proximity probe. Matches the detection radius reactive flee branches use. */
    private static final double EXPLOSIVE_SCAN_RADIUS = 10.0D;

    /** Health fraction at or below which the mob is considered critically wounded regardless of active goal. */
    private static final float CRITICAL_HEALTH_FRACTION = 0.15f;

    /**
     * Returns {@link GoalUrgency#EMERGENCY} if a cheap check finds the mob on fire, near an active fuse/primed
     * explosive, or at critically low health; otherwise returns {@code null} (meaning "unknown/not emergency", so the
     * caller should fall back to its normal replan cadence rather than treating this as a low-urgency result).
     *
     * @param mob the mob to probe
     * @param <E> the mob type
     * @return {@link GoalUrgency#EMERGENCY}, or {@code null} if no emergency condition was cheaply detected
     */
    public static <E extends Mob> GoalUrgency detectPreplanUrgency(E mob) {
        if (!mob.isAlive())
            return null;

        if (mob.isOnFire())
            return GoalUrgency.EMERGENCY;

        if (FleeFireAction.shouldFleefire(mob))
            return GoalUrgency.EMERGENCY;

        if (ExplosiveFleeAction.hasNearbyExplosive(mob, EXPLOSIVE_SCAN_RADIUS))
            return GoalUrgency.EMERGENCY;

        if (mob.getMaxHealth() > 0f && mob.getHealth() <= mob.getMaxHealth() * CRITICAL_HEALTH_FRACTION)
            return GoalUrgency.EMERGENCY;

        return null;
    }
}
