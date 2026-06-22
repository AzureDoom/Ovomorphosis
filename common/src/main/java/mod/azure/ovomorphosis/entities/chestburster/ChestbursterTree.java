package mod.azure.ovomorphosis.entities.chestburster;

import net.minecraft.world.entity.LivingEntity;

import mod.azure.ovomorphosis.ai.actions.*;
import mod.azure.ovomorphosis.ai.actions.chestburster.EatFoodAction;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.BehaviorNode;
import mod.azure.ovomorphosis.ai.core.BehaviorResult;

public class ChestbursterTree {

    public static BehaviorNode<ChestbursterEntity> create() {
        var idle = new IdleAction<ChestbursterEntity>(40, 100, 1);

        var eatAction = new EatFoodAction(
            16.0,
            2.0,
            0.12,
            5,
            28,
            mob -> mob.animationDispatcher.serverEating()
        );

        var wander = new WanderAction<ChestbursterEntity>(
            0.1D,
            5,
            6.0D,
            60,
            160
        );

        var flee = new FleeAction<ChestbursterEntity>(
            0.18D,
            20,
            70
        );

        var fleeExplosive = new ExplosiveFleeAction<ChestbursterEntity>(
            0.18D,
            10,
            20,
            120
        );

        var swim = new SwimAction<ChestbursterEntity>(200);

        return (chestburster, blackboard, cooldowns) -> {

            var threat = blackboard.get(AiKeys.TARGET, LivingEntity.class);
            var hasThreat = threat != null && threat.isAlive();

            if (fleeExplosive.hasNearbyExplosive(chestburster)) {
                return BehaviorResult.run(fleeExplosive, 120);
            }

            if (hasThreat) {
                return BehaviorResult.run(flee, 70);
            }

            if (eatAction.canStart(chestburster)) {
                return BehaviorResult.run(eatAction, 5);
            }

            if (chestburster.isInWater() || chestburster.isInLava()) {
                return BehaviorResult.run(swim, 200);
            }

            if (!cooldowns.isOnCooldown(AiKeys.PASSIVE_DECISION)) {
                cooldowns.set(AiKeys.PASSIVE_DECISION, 180);
                if (chestburster.getRandom().nextFloat() < 0.1F) {
                    return BehaviorResult.run(wander, 5);
                }
                return BehaviorResult.run(idle, 5);
            }

            return BehaviorResult.run(idle, 5);
        };
    }
}
