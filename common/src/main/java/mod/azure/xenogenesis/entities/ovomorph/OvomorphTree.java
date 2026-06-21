package mod.azure.xenogenesis.entities.ovomorph;

import net.minecraft.world.entity.LivingEntity;

import mod.azure.xenogenesis.ai.actions.IdleAction;
import mod.azure.xenogenesis.ai.actions.ovomorph.HatchFacehuggerAction;
import mod.azure.xenogenesis.ai.core.AiKeys;
import mod.azure.xenogenesis.ai.core.BehaviorNode;
import mod.azure.xenogenesis.ai.core.BehaviorResult;

public class OvomorphTree {

    public static BehaviorNode<OvomorphEntity> create() {
        var hatch = new HatchFacehuggerAction();
        var idle = new IdleAction<OvomorphEntity>(40, 100, 1);

        return (egg, blackboard, cooldowns) -> {
            if (egg.getEggState() == EggStates.HATCHED.ordinal() || !egg.hasFacehugger()) {
                return BehaviorResult.run(idle, 5);
            }

            var host = blackboard.get(AiKeys.TARGET, LivingEntity.class);
            if (
                egg.getEggState() == EggStates.HATCHING.ordinal()
                    || (host != null && host.isAlive())
            ) {
                egg.setEggState(EggStates.HATCHING.ordinal());
                return BehaviorResult.run(hatch, 100);
            }

            return BehaviorResult.run(idle, 5);
        };
    }
}
