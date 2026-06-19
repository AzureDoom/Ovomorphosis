package mod.azure.xenogenesis.ai.util;

import net.minecraft.world.entity.LivingEntity;

import java.util.Comparator;

import mod.azure.xenogenesis.ai.core.AiKeys;
import mod.azure.xenogenesis.ai.core.Blackboard;
import mod.azure.xenogenesis.entities.facehugger.FacehuggerEntity;

public final class FacehuggerHostileTargetSelector<E extends FacehuggerEntity> implements TargetSelector<E> {

    private final double range;

    public FacehuggerHostileTargetSelector(double range) {
        this.range = range;
    }

    @Override
    public LivingEntity findTarget(E mob, Blackboard blackboard) {
        var current = blackboard.get(AiKeys.TARGET, LivingEntity.class);
        if (current != null && current.isAlive() && TargetingUtils.faceHuggerTest(mob, current)) {
            return current;
        }

        return mob.level()
            .getEntitiesOfClass(
                LivingEntity.class,
                mob.getBoundingBox().inflate(range),
                entity -> TargetingUtils.faceHuggerTest(mob, entity)
            )
            .stream()
            .min(Comparator.comparingDouble(mob::distanceToSqr))
            .orElse(null);
    }
}
