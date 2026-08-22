package mod.azure.ovomorphosis.ai.combat;

import net.minecraft.world.entity.Mob;

import mod.azure.ovomorphosis.ai.core.Action;
import mod.azure.ovomorphosis.ai.core.Cooldowns;

/**
 * The "choose" half of the choose/execute split: a data-only description of one attack a mob can pick between, used by
 * {@link AttackSelector} to decide which attack to run this tick without any attack-specific logic living in the
 * behavior tree itself.
 * <h3>Why this exists</h3> Previously, deciding between the tail punish and the claw swipe combo meant a hand-written
 * cascade of range/cooldown checks directly in {@code XenomorphTree} — one {@code if} block per attack, in a fixed
 * order, that every new attack (a bite, say) had to be manually threaded into at the right priority. With
 * {@link AttackProfile}, each attack is just an entry in a list; {@link AttackSelector} picks the best-scoring one that
 * is currently legal. Adding an attack is adding one profile, not editing tree control flow.
 * <p>
 * This only describes <em>when an attack is eligible and how urgently it should be preferred</em> — the actual
 * {@link Action} it wraps still owns its own animation timing and, via {@link MeleeHitResolver}, its own hit
 * resolution. {@link AttackProfile} does not replace either of those; it sits above them as the selection criteria.
 *
 * @param <E>         the mob type this attack applies to
 * @param name        a short, stable identifier for logging/diagnostics (e.g. {@code "tail_attack"}); by convention
 *                    matches the action's cooldown key
 * @param action      the {@link Action} that runs this attack once selected
 * @param cooldownKey the {@link Cooldowns} key that must be ready for this attack to be eligible
 * @param minRange    the minimum distance (blocks) the target must be at for this attack to be usable, or {@code 0} for
 *                    no minimum
 * @param maxRange    the maximum distance (blocks) the target may be at for this attack to be usable
 * @param priority    tie-breaker when more than one profile is eligible in the same tick — higher wins. Ties are broken
 *                    by list order (first listed wins), matching the historical fixed if-else ordering.
 */
public record AttackProfile<E extends Mob>(
    String name,
    Action<E> action,
    String cooldownKey,
    double minRange,
    double maxRange,
    int priority
) {

    /**
     * Returns {@code true} if {@code distance} (blocks, not squared) falls within this profile's usable range band.
     */
    public boolean inRange(double distance) {
        return distance >= minRange && distance <= maxRange;
    }

    /**
     * Returns {@code true} if this attack's cooldown has expired, or if {@code force} is {@code true} (used for
     * contexts like {@code DEFEND_HIVE} that historically bypassed individual attack cooldowns entirely).
     */
    public boolean isReady(Cooldowns cooldowns, boolean force) {
        return force || cooldowns.ready(cooldownKey);
    }
}
