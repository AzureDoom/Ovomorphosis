package mod.azure.ovomorphosis.entities.ovomorph;

import com.azure.azurecortex.api.behavior.BehaviorNode;
import com.azure.azurecortex.api.behavior.BehaviorResult;
import com.azure.azurecortex.api.blackboard.CommonBlackboardKeys;

import mod.azure.ovomorphosis.ai.actions.IdleAction;
import mod.azure.ovomorphosis.ai.actions.ovomorph.HatchFacehuggerAction;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;

public class OvomorphTree {

    public static BehaviorNode<OvomorphEntity, AiGoalType> create() {
        var hatch = new HatchFacehuggerAction<AiGoalType>();
        var idle = new IdleAction<OvomorphEntity, AiGoalType>(40, 100, 1);

        return (egg, blackboard, cooldowns) -> {
            if (egg.getEggState() == EggStates.HATCHED.ordinal() || !egg.hasFacehugger()) {
                return BehaviorResult.run(idle, 5);
            }

            var host = blackboard.get(CommonBlackboardKeys.TARGET);
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
