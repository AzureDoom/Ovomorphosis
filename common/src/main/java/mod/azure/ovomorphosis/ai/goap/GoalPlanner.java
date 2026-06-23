package mod.azure.ovomorphosis.ai.goap;

import net.minecraft.world.entity.Mob;

import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.ai.core.Cooldowns;

public interface GoalPlanner<E extends Mob> {

    PlannedGoal<E> chooseGoal(E mob, Blackboard blackboard, Cooldowns cooldowns);
}
