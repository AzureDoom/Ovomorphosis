package mod.azure.xenogenesis.entities.queen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import mod.azure.xenogenesis.ai.actions.*;
import mod.azure.xenogenesis.ai.actions.queen.LayOvomorphAction;
import mod.azure.xenogenesis.ai.core.AiKeys;
import mod.azure.xenogenesis.ai.core.BehaviorNode;
import mod.azure.xenogenesis.ai.core.BehaviorResult;
import mod.azure.xenogenesis.ai.util.TargetingUtils;

public class QueenTree {

    public static BehaviorNode<QueenEntity> create() {
        var wander = new WanderAction<QueenEntity>(
            0.18D,
            5,
            6.0D,
            60,
            160
        );

        var idle = new IdleAction<QueenEntity>(40, 100, 1);

        var destinationMove = new MoveToDestinationAction<QueenEntity>(
            2.5D,
            0.35D,
            25,
            5.0D,
            1.0D,
            0.55D,
            0.85D
        );

        var moveToTargetCombat = new MoveToTargetAction<QueenEntity>(
            1.8D,
            0.40D,
            20
        );

        var normalAttack = new TimedAttackAction<QueenEntity>(
            "normal_attack",
            40,
            14,
            7,
            100,
            x -> x.animationDispatcher.serverAttack()
        );

        var heavyAttack = new TimedAttackAction<QueenEntity>(
            "tail_attack",
            40,
            14,
            7,
            110,
            x -> x.animationDispatcher.serverTailAttack()
        );

        var layOvomorph = new LayOvomorphAction<QueenEntity>(
            15,
            x -> x.animationDispatcher.serverLayEgg()
        );

        return (queen, blackboard, cooldowns) -> {

            var currentTarget = blackboard.get(AiKeys.TARGET, LivingEntity.class);
            if (currentTarget != null && !currentTarget.isAlive()) {
                blackboard.set(AiKeys.TARGET, null);
                queen.setTarget(null);
                currentTarget = null;
            }

            var destination = blackboard.get(AiKeys.DESTINATION, BlockPos.class);
            if (destination != null && currentTarget == null) {
                return BehaviorResult.run(destinationMove, 25);
            }

            if (currentTarget != null && currentTarget.isAlive()) {
                var yGap = currentTarget.getY() - queen.getY();
                var canReachVertically = yGap <= 1.5D;

                if (
                    canReachVertically
                        && TargetingUtils.isInAttackRange(queen, currentTarget, 1.25D)
                        && cooldowns.ready("tail_attack")
                ) {
                    return BehaviorResult.run(heavyAttack, 110);
                }

                if (
                    canReachVertically
                        && TargetingUtils.isInAttackRange(queen, currentTarget, 1.0D)
                        && cooldowns.ready("normal_attack")
                ) {
                    return BehaviorResult.run(normalAttack, 100);
                }

                return BehaviorResult.run(moveToTargetCombat, 20);
            }

            if (cooldowns.ready(LayOvomorphAction.LAY_COOLDOWN)) {
                return BehaviorResult.run(layOvomorph, 15);
            }

            return BehaviorResult.run(idle, 5);
        };
    }
}
