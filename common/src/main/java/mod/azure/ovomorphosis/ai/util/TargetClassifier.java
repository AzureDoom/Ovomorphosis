package mod.azure.ovomorphosis.ai.util;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.TridentItem;

import mod.azure.ovomorphosis.ai.actions.xenomorph.FleeFireAction;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.entities.AbstractAlienEntity;
import mod.azure.ovomorphosis.entities.xenomorph.XenomorphEntity;
import mod.azure.ovomorphosis.util.ModTags;

/**
 * Evaluates the current target entity once per planning cycle and writes a set of boolean classification keys to the
 * blackboard.
 * <h3>Keys written</h3>
 * <ul>
 * <li>{@link AiKeys#TARGET_IS_RANGED} — target is holding a bow, crossbow, or trident, or has recently fired a
 * projectile nearby.</li>
 * <li>{@link AiKeys#TARGET_IS_FIRE_USER} — already maintained by {@link FleeFireAction}; this class only refreshes it
 * when fire danger is not active so it correctly resets to {@code false}.</li>
 * <li>{@link AiKeys#TARGET_IS_ISOLATED} — no other players or mobs within 12 blocks of the target. Influences
 * carry/capture scoring.</li>
 * <li>{@link AiKeys#TARGET_IS_NEAR_HIVE} — target is within 20 blocks of the nearest known resin web cross. Triggers
 * hive-defense scoring.</li>
 * <li>{@link AiKeys#TARGET_IS_ARMORED} — target has at least two armor slots filled. Influences grab eligibility and
 * combat approach choice.</li>
 * <li>{@link AiKeys#TARGET_IS_VALID_HOST} — target passes the facehugger host validity test, meaning it could be
 * infected. Influences carrier scoring.</li>
 * <li>{@link AiKeys#TARGET_IS_TOO_DANGEROUS_TO_GRAB} — target is ranged, armored, facing the mob and close, or is a
 * danger entity. Used to suppress grab/carry attempts.</li>
 * </ul>
 * <h3>Call site</h3> Call {@link #classify} at the start of {@code chooseGoal} before any score computation. All keys
 * are cleared to {@code false} when the target is null so stale state never leaks.
 */
public final class TargetClassifier {

    private static final double ISOLATION_RADIUS = 12.0D;

    private static final int ARMOUR_SLOT_THRESHOLD = 2;

    private TargetClassifier() {}

    /**
     * Evaluates the current blackboard target and writes all classification keys. Safe to call every planning cycle —
     * cheap enough for 20-tick cadence.
     *
     * @param mob        the xenomorph performing the evaluation
     * @param blackboard the mob's blackboard
     */
    public static void classify(XenomorphEntity mob, Blackboard blackboard) {
        var target = blackboard.get(AiKeys.TARGET, LivingEntity.class);

        if (target == null || !target.isAlive()) {
            clearAll(blackboard);
            return;
        }

        var memory = blackboard.get(AiKeys.HIVE_MEMORY, HiveMemory.class);

        var isRanged = isRangedCombatant(mob, target);
        var isIsolated = isIsolated(mob, target);
        var isNearHive = isNearHive(mob, target, memory);
        var isArmored = isArmored(target);
        var isValidHost = TargetingUtils.faceHuggerTest(mob, target);
        var isDangerEntity = target.getType().is(ModTags.DANGER_ENTITIES);

        var tooHeavy = isArmored && armorPoints(target) >= 16;
        var tooDangerous = isDangerEntity
            || (isRanged && mob.distanceToSqr(target) > 4.0 * 4.0)
            || (tooHeavy)
            || target.getType().is(ModTags.XENO_GRAB_BLACKLIST);

        blackboard.set(AiKeys.TARGET_IS_RANGED, isRanged);
        blackboard.set(AiKeys.TARGET_IS_ISOLATED, isIsolated);
        blackboard.set(AiKeys.TARGET_IS_NEAR_HIVE, isNearHive);
        blackboard.set(AiKeys.TARGET_IS_ARMORED, isArmored);
        blackboard.set(AiKeys.TARGET_IS_VALID_HOST, isValidHost);
        blackboard.set(AiKeys.TARGET_IS_TOO_DANGEROUS_TO_GRAB, tooDangerous);

        var fireDangerExpiry = blackboard.get(AiKeys.FIRE_DANGER_UNTIL_TICK, Integer.class);
        var currentTick = (int) mob.level().getGameTime();
        if (fireDangerExpiry != null && currentTick >= fireDangerExpiry) {
            blackboard.set(AiKeys.TARGET_IS_FIRE_USER, false);
        }
    }

    private static boolean isRangedCombatant(XenomorphEntity mob, LivingEntity target) {
        for (var slot : target.getHandSlots()) {
            var item = slot.getItem();
            if (
                item.asItem() instanceof BowItem
                    || item.asItem() instanceof CrossbowItem
                    || item.asItem() instanceof TridentItem
            ) {
                return true;
            }
        }

        var box = mob.getBoundingBox().inflate(12.0D);
        return !mob.level()
            .getEntitiesOfClass(
                AbstractArrow.class,
                box,
                a -> a.isAlive() && a.getOwner() == target
            )
            .isEmpty();
    }

    private static boolean isIsolated(XenomorphEntity mob, LivingEntity target) {
        var box = target.getBoundingBox().inflate(ISOLATION_RADIUS);
        var level = mob.level();

        for (var entity : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (entity == target)
                continue;
            if (entity instanceof AbstractAlienEntity)
                continue;
            if (entity instanceof Player p && (p.isCreative() || p.isSpectator()))
                continue;
            if (!entity.isAlive())
                continue;
            return false;
        }
        return true;
    }

    private static boolean isNearHive(XenomorphEntity mob, LivingEntity target, HiveMemory memory) {
        if (memory == null)
            return false;
        return memory.findNearestWebCross(mob.level(), target.blockPosition(), 20.0D).isPresent();
    }

    private static boolean isArmored(LivingEntity target) {
        return armorPoints(target) > 0 && filledArmorSlots(target) >= ARMOUR_SLOT_THRESHOLD;
    }

    private static int filledArmorSlots(LivingEntity target) {
        int filled = 0;
        for (var slot : target.getArmorSlots()) {
            if (!slot.isEmpty())
                filled++;
        }
        return filled;
    }

    private static int armorPoints(LivingEntity target) {
        var armorAttr = target.getAttributeValue(
            net.minecraft.world.entity.ai.attributes.Attributes.ARMOR
        );
        return (int) armorAttr;
    }

    private static void clearAll(Blackboard blackboard) {
        blackboard.set(AiKeys.TARGET_IS_RANGED, false);
        blackboard.set(AiKeys.TARGET_IS_ISOLATED, false);
        blackboard.set(AiKeys.TARGET_IS_NEAR_HIVE, false);
        blackboard.set(AiKeys.TARGET_IS_ARMORED, false);
        blackboard.set(AiKeys.TARGET_IS_VALID_HOST, false);
        blackboard.set(AiKeys.TARGET_IS_TOO_DANGEROUS_TO_GRAB, false);
        blackboard.set(AiKeys.TARGET_IS_FIRE_USER, false);
    }
}
