package mod.azure.ovomorphosis.ai.combat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import mod.azure.ovomorphosis.ai.util.TargetingUtils;

/**
 * The "execute" half of the choose/execute split: given that some higher-level decision has already picked
 * <em>which</em> attack to run, this is the one place that actually resolves whether a strike lands.
 * <h3>Why this exists</h3> Before this, {@code TimedAttackAction}, {@code XenomorphCombatAction}, and
 * {@code LungeAction} each independently re-implemented the same three steps (bounding-box reach check, melee
 * line-of-sight check, {@code doHurtTarget}) with slightly different inflate radii scattered across the codebase (2.8,
 * 2.5, 1.5, 2.8 again). Every new attack — a bite, a second tail variant, whatever comes next — was one more copy of
 * that block to keep in sync. Centralizing "can this hit land, and did it" here means a new attack only needs to supply
 * its own timing/animation (see {@link AttackProfile}); the actual hit resolution is shared and only has to be correct
 * in one place.
 * <p>
 * This intentionally does <em>not</em> decide which attack to use, apply cooldowns, or terminate the calling
 * {@code Action} — those remain the calling action's job, since they differ per attack (a tail punish sets a longer
 * cooldown than a claw swipe; a grab-and-execute doesn't use this path at all). This class only answers "given this
 * mob, this target, and this reach, does the hit connect" and applies the damage if so.
 */
public final class MeleeHitResolver {

    private MeleeHitResolver() {}

    /**
     * Attempts to resolve a single melee strike from {@code mob} against {@code target} at the given reach.
     * <p>
     * On a landed hit, this also snaps the mob's look direction onto the target immediately beforehand, matching the
     * historical behavior of the call sites this replaces (helps the attack animation/hitbox line up with the swing).
     *
     * @param mob    the attacking mob
     * @param target the defending target
     * @param reach  the bounding-box inflation distance used for the reach check — callers should pass the same value
     *               used for the range gate that led them to attempt this strike in the first place
     * @return {@code true} if the target was actually struck (in range, had line of sight, and {@link Mob#doHurtTarget}
     *         was invoked); {@code false} if the strike whiffed for either reason
     */
    public static boolean tryStrike(Mob mob, LivingEntity target, double reach) {
        if (!mob.getBoundingBox().inflate(reach).intersects(target.getBoundingBox()))
            return false;

        if (!TargetingUtils.hasMeleeLineOfSight(mob, target))
            return false;

        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        mob.doHurtTarget(target);
        return true;
    }
}
