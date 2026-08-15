package mod.azure.ovomorphosis.ai.actions.ovomorph;

import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import mod.azure.ovomorphosis.ai.core.Action;
import mod.azure.ovomorphosis.ai.core.ActionOutcome;
import mod.azure.ovomorphosis.ai.core.ActionStatus;
import mod.azure.ovomorphosis.ai.core.Blackboard;
import mod.azure.ovomorphosis.ai.core.Cooldowns;
import mod.azure.ovomorphosis.entities.facehugger.FacehuggerEntity;
import mod.azure.ovomorphosis.entities.ovomorph.EggStates;
import mod.azure.ovomorphosis.entities.ovomorph.OvomorphEntity;
import mod.azure.ovomorphosis.registry.EntityRegistry;
import mod.azure.ovomorphosis.registry.SoundRegistry;

public final class HatchFacehuggerAction implements Action<OvomorphEntity> {

    private int ticks;

    private int hatchAt;

    @Override
    public void start(OvomorphEntity egg, Blackboard blackboard, Cooldowns cooldowns) {
        ticks = 0;
        hatchAt = 40 + egg.getRandom().nextInt(60 - 40 + 1);
        egg.setEggState(EggStates.HATCHING.ordinal());
        egg.level()
            .playSound(egg, egg.blockPosition(), SoundRegistry.OVOMORPH_OPEN.get(), SoundSource.HOSTILE, 1.0F, 1.0F);
    }

    @Override
    public ActionOutcome tick(OvomorphEntity egg, Blackboard blackboard, Cooldowns cooldowns) {
        ticks++;

        if (ticks >= hatchAt) {
            if (!egg.level().isClientSide()) {
                var facehugger = new FacehuggerEntity(EntityRegistry.FACEHUGGER.get(), egg.level());
                facehugger.setIsInfertile(false);

                var openAbove = egg.getOpenSpaceAbove();
                var level = egg.level();
                var eggPos = egg.blockPosition();

                if (openAbove >= 2) {
                    facehugger.setPos(egg.position().x, egg.position().y + 1.2, egg.position().z);
                    facehugger.setDeltaMovement(
                        Mth.nextFloat(facehugger.getRandom(), -0.5f, 0.5f),
                        0.7,
                        Mth.nextFloat(facehugger.getRandom(), -0.5f, 0.5f)
                    );
                } else if (openAbove == 1) {
                    var top = eggPos.above();
                    facehugger.setPos(top.getX() + 0.5, top.getY(), top.getZ() + 0.5);
                    facehugger.setDeltaMovement(
                        Mth.nextFloat(facehugger.getRandom(), -0.2f, 0.2f),
                        0.0,
                        Mth.nextFloat(facehugger.getRandom(), -0.2f, 0.2f)
                    );
                } else {
                    var spawned = false;
                    for (
                        var dir : new Direction[] { Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST }
                    ) {
                        var side = eggPos.relative(dir);
                        if (level.getBlockState(side).isAir()) {
                            facehugger.setPos(side.getX() + 0.5, side.getY(), side.getZ() + 0.5);
                            var nudge = Vec3.atCenterOf(dir.getNormal()).scale(0.2);
                            facehugger.setDeltaMovement(nudge.x, 0.1, nudge.z);
                            spawned = true;
                            break;
                        }
                    }
                    if (!spawned) {
                        facehugger.setPos(egg.position().x, egg.position().y + 0.5, egg.position().z);
                        facehugger.setDeltaMovement(0, 0, 0);
                    }
                }

                egg.level().addFreshEntity(facehugger);
            }
            return ActionOutcome.SUCCESS;
        }

        return ActionOutcome.RUNNING;
    }

    @Override
    public void stop(OvomorphEntity egg, Blackboard blackboard, Cooldowns cooldowns, ActionStatus reason) {
        egg.setHasFacehugger(false);
        egg.setEggState(EggStates.HATCHED.ordinal());
    }

    @Override
    public boolean isInterruptible() {
        return false;
    }

    @Override
    public int priority() {
        return 100;
    }
}
