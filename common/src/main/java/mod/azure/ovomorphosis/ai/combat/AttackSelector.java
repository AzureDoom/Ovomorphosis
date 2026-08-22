package mod.azure.ovomorphosis.ai.combat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import mod.azure.ovomorphosis.ai.core.Cooldowns;

/**
 * Picks the best legal {@link AttackProfile} for {@code mob} to use against {@code target} this tick.
 * <p>
 * "Legal" means: line-of-sight/hit resolution is <em>not</em> checked here (that's {@link MeleeHitResolver}'s job at
 * execution time) — only range and cooldown, since those are what a behavior tree needs to know before committing to an
 * action. Among legal candidates, the highest {@link AttackProfile#priority()} wins; ties keep the first-listed
 * candidate, matching the fixed ordering the old cascading if-chain used to have.
 */
public final class AttackSelector {

    private AttackSelector() {}

    /**
     * @param mob        the attacking mob
     * @param target     the candidate target
     * @param cooldowns  the mob's cooldown tracker
     * @param forceReady if {@code true}, ignores every profile's individual cooldown (mirrors the historical
     *                   {@code defending}-bypasses-cooldowns behavior for hive defense)
     * @param candidates the attacks to choose between, in priority-tie-break order
     * @param <E>        the mob type
     * @return the selected {@link AttackProfile}, or {@code null} if none are currently in range and ready
     */
    @Nullable
    public static <E extends Mob> AttackProfile<E> select(
        Mob mob,
        LivingEntity target,
        Cooldowns cooldowns,
        boolean forceReady,
        List<AttackProfile<E>> candidates
    ) {
        var distance = Math.sqrt(mob.distanceToSqr(target));

        AttackProfile<E> best = null;
        for (var candidate : candidates) {
            if (!candidate.inRange(distance))
                continue;
            if (!candidate.isReady(cooldowns, forceReady))
                continue;
            if (best == null || candidate.priority() > best.priority())
                best = candidate;
        }
        return best;
    }
}
