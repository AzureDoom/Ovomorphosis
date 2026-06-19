package mod.azure.xenogenesis.mixins;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.Minecart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import mod.azure.xenogenesis.entities.AbstractAlienEntity;

/**
 * Thanks to Boston for this fix!
 */
@Mixin(Entity.class)
public abstract class EntityMixin {

    @Inject(at = @At("HEAD"), method = "startRiding", cancellable = true)
    void xenogenesis$boatRidingCancel(Entity entity, CallbackInfoReturnable<Boolean> callbackInfoReturnable) {
        var self = EntityMixin.<Entity>self(this);

        if (!(self instanceof AbstractAlienEntity))
            return;

        if (entity instanceof Boat || entity instanceof Minecart) {
            callbackInfoReturnable.setReturnValue(false);
        }
    }

    @Inject(at = @At("HEAD"), method = "tick")
    void xenogenesis$kickOut(CallbackInfo callbackInfo) {
        var self = EntityMixin.<Entity>self(this);
        var level = self.level();

        if (level.isClientSide)
            return;
        if (!(self instanceof AbstractAlienEntity))
            return;

        if (self.getVehicle() instanceof Boat || self.getVehicle() instanceof Minecart) {
            self.stopRiding();
        }
    }

    private static <T> T self(Object object) {
        return (T) object;
    }
}
