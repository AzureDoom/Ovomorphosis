package mod.azure.ovomorphosis.mixins;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.sensing.NearestVisibleLivingEntitySensor;
import net.minecraft.world.entity.ai.sensing.VillagerHostilesSensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import mod.azure.ovomorphosis.entities.xenomorph.XenomorphEntity;

/**
 * Thanks to Boston for this fix!
 */
@Mixin(VillagerHostilesSensor.class)
public abstract class VillagerHostilesSensorMixin extends NearestVisibleLivingEntitySensor {

    @Inject(at = @At("HEAD"), method = "isClose", cancellable = true)
    void ovomorphosis$isClose(
        LivingEntity attacker,
        LivingEntity target,
        CallbackInfoReturnable<Boolean> callbackInfoReturnable
    ) {
        if (!(target instanceof XenomorphEntity))
            return;

        var distance = 12F;
        var returnValue = target.distanceToSqr(attacker) <= (distance * distance);
        callbackInfoReturnable.setReturnValue(returnValue);
    }

    @Inject(at = @At("HEAD"), method = "isHostile", cancellable = true)
    void ovomorphosis$isHostile(LivingEntity entity, CallbackInfoReturnable<Boolean> callbackInfoReturnable) {
        if (entity instanceof XenomorphEntity) {
            callbackInfoReturnable.setReturnValue(true);
        }
    }
}
