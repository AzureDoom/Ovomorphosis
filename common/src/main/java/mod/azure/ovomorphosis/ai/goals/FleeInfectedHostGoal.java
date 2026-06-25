package mod.azure.ovomorphosis.ai.goals;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.level.pathfinder.Path;

import java.util.Comparator;
import java.util.EnumSet;

import mod.azure.ovomorphosis.infection.InfectionManager;
import mod.azure.ovomorphosis.infection.InfectionState;

public class FleeInfectedHostGoal extends Goal {

    private final PathfinderMob mob;

    private final double fleeRange;

    private final double fastSpeed;

    private LivingEntity target;

    private Path path;

    public FleeInfectedHostGoal(PathfinderMob mob, double range, double fast) {
        this.mob = mob;
        this.fleeRange = range;
        this.fastSpeed = fast;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        target = mob.level()
            .getEntitiesOfClass(
                LivingEntity.class,
                mob.getBoundingBox().inflate(fleeRange),
                e -> e != mob
                    && InfectionManager.isInfected(e)
                    && InfectionManager.getPhase(e) != InfectionState.Phase.DORMANT
            )
            .stream()
            .min(Comparator.comparingDouble(mob::distanceToSqr))
            .orElse(null);

        if (target == null)
            return false;

        var fleeVec = DefaultRandomPos.getPosAway(mob, 16, 7, target.position());
        if (fleeVec == null)
            return false;

        if (target.distanceToSqr(fleeVec.x, fleeVec.y, fleeVec.z) < target.distanceToSqr(mob))
            return false;

        path = mob.getNavigation().createPath(fleeVec.x, fleeVec.y, fleeVec.z, 0);
        return path != null;
    }

    @Override
    public boolean canContinueToUse() {
        return !mob.getNavigation().isDone()
            && target != null
            && InfectionManager.isInfected(target);
    }

    @Override
    public void start() {
        mob.getNavigation().moveTo(path, fastSpeed);
    }

    @Override
    public void tick() {
        mob.getNavigation().moveTo(path, fastSpeed);
    }

    @Override
    public void stop() {
        target = null;
    }
}
