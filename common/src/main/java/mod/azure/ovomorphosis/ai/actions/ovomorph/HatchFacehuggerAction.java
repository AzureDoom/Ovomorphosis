package mod.azure.ovomorphosis.ai.actions.ovomorph;

import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

import mod.azure.ovomorphosis.ai.core.Action;
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
    public ActionStatus tick(OvomorphEntity egg, Blackboard blackboard, Cooldowns cooldowns) {
        ticks++;

        if (ticks >= hatchAt) {
            if (!egg.level().isClientSide()) {
                var facehugger = new FacehuggerEntity(EntityRegistry.FACEHUGGER.get(), egg.level());
                facehugger.setPos(egg.position().x, egg.position().y + 1.2, egg.position().z);
                facehugger.setDeltaMovement(
                    Mth.nextFloat(facehugger.getRandom(), -0.5f, 0.5f),
                    0.7,
                    Mth.nextFloat(facehugger.getRandom(), -0.5f, 0.5f)
                );
                facehugger.setIsInfertile(false);
                egg.level().addFreshEntity(facehugger);
            }
            return ActionStatus.SUCCESS;
        }

        return ActionStatus.RUNNING;
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
