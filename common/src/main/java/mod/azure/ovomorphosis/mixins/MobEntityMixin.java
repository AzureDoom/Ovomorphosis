package mod.azure.ovomorphosis.mixins;

import mod.azure.ovomorphosis.ai.goals.FleeInfectedHostGoal;
import mod.azure.ovomorphosis.ai.util.TargetingUtils;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import mod.azure.ovomorphosis.entities.facehugger.FacehuggerEntity;

/**
 * @author Boston Vanseghi
 */
@Mixin(Mob.class)
public abstract class MobEntityMixin extends LivingEntity {

    protected MobEntityMixin(EntityType<? extends LivingEntity> type, Level world) {
        super(type, world);
    }

    @Inject(method = { "playAmbientSound" }, at = { @At("HEAD") }, cancellable = true)
    public void ovomorphosis$playAmbientSound(CallbackInfo callbackInfo) {
        if (this.getPassengers().stream().anyMatch(FacehuggerEntity.class::isInstance))
            callbackInfo.cancel();
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ovomorphosis$addFleeGoal(CallbackInfo ci) {
        var self = TargetingUtils.<Mob>self(this);
        if (self instanceof Animal animal) {
            self.goalSelector.addGoal(1, new FleeInfectedHostGoal(animal, 12.0, 1.6));
        }
    }
}
