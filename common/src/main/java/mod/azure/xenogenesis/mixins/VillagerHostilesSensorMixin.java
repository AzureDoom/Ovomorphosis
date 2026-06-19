package mod.azure.xenogenesis.mixins;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.sensing.NearestVisibleLivingEntitySensor;
import net.minecraft.world.entity.ai.sensing.VillagerHostilesSensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import mod.azure.xenogenesis.entities.AbstractAlienEntity;

/**
 * Thanks to Boston for this fix!
 */
@Mixin(VillagerHostilesSensor.class)
public abstract class VillagerHostilesSensorMixin extends NearestVisibleLivingEntitySensor {

    @Inject(at = @At("HEAD"), method = "isClose", cancellable = true)
    void xenogenesis$isClose(
        LivingEntity livingEntity,
        LivingEntity livingEntity2,
        CallbackInfoReturnable<Boolean> callbackInfoReturnable
    ) {
        if (!(livingEntity2 instanceof AbstractAlienEntity))
            return;

        var distance = 12F;
        var returnValue = livingEntity2.distanceToSqr(livingEntity) <= (distance * distance);
        callbackInfoReturnable.setReturnValue(returnValue);
    }

    @Inject(at = @At("HEAD"), method = "isHostile", cancellable = true)
    void xenogenesis$isHostile(LivingEntity livingEntity, CallbackInfoReturnable<Boolean> callbackInfoReturnable) {
        if (livingEntity instanceof AbstractAlienEntity) {
            callbackInfoReturnable.setReturnValue(true);
        }
    }
}
