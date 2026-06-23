package mod.azure.ovomorphosis.ai.goap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.Optional;

public record PlannedGoal<E extends Mob>(
    AiGoalType type,
    float score,

    int startedAtTick,
    int minCommitTicks,
    int maxCommitTicks,

    Optional<LivingEntity> target,
    Optional<BlockPos> destination,

    GoalUrgency urgency,
    boolean interruptible,

    String reason
) {

    public boolean canReplan(int currentTick) {
        return currentTick - startedAtTick >= minCommitTicks;
    }

    public boolean isExpired(int currentTick) {
        return currentTick - startedAtTick >= maxCommitTicks;
    }

    public boolean hasValidTarget() {
        return target.isPresent() && target.get().isAlive();
    }

    public boolean hasDestination() {
        return destination.isPresent();
    }

    public boolean isNone() {
        return type == AiGoalType.NONE;
    }

    public static <E extends Mob> PlannedGoal<E> of(
        AiGoalType type,
        float score,
        int currentTick,
        int minCommitTicks,
        int maxCommitTicks,
        LivingEntity target,
        BlockPos destination,
        GoalUrgency urgency,
        boolean interruptible,
        String reason
    ) {
        return new PlannedGoal<>(
            type,
            score,
            currentTick,
            minCommitTicks,
            maxCommitTicks,
            Optional.ofNullable(target),
            Optional.ofNullable(destination),
            urgency,
            interruptible,
            reason
        );
    }

    public static <E extends Mob> PlannedGoal<E> none(int currentTick) {
        return new PlannedGoal<>(
            AiGoalType.NONE,
            0.0F,
            currentTick,
            20,
            40,
            Optional.empty(),
            Optional.empty(),
            GoalUrgency.LOW,
            true,
            "No goal selected"
        );
    }
}
