package mod.azure.ovomorphosis.mixins;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import mod.azure.ovomorphosis.ai.util.TargetingUtils;
import mod.azure.ovomorphosis.client.facehugger.EntityHeadData;
import mod.azure.ovomorphosis.entities.AbstractAlienEntity;
import mod.azure.ovomorphosis.entities.facehugger.FacehuggerEntity;

/**
 * Thanks to Boston for this fix!
 */
@Mixin(Entity.class)
public abstract class EntityMixin {

    @Inject(at = @At("HEAD"), method = "startRiding", cancellable = true)
    void ovomorphosis$boatRidingCancel(Entity vehicle, CallbackInfoReturnable<Boolean> callbackInfoReturnable) {
        var self = TargetingUtils.<Entity>self(this);

        if (!(self instanceof AbstractAlienEntity))
            return;

        if (vehicle instanceof Boat || vehicle instanceof Minecart) {
            callbackInfoReturnable.setReturnValue(false);
        }
    }

    @Inject(at = @At("HEAD"), method = "tick")
    void ovomorphosis$kickOut(CallbackInfo callbackInfo) {
        var self = TargetingUtils.<Entity>self(this);
        var level = self.level();

        if (level.isClientSide)
            return;
        if (!(self instanceof AbstractAlienEntity))
            return;

        if (self.getVehicle() instanceof Boat || self.getVehicle() instanceof Minecart) {
            self.stopRiding();
        }
    }

    @Inject(
        method = "positionRider(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity$MoveFunction;)V",
        at = @At("TAIL")
    )
    private void ovomorphosis$faceRidingPosition(
        Entity passenger,
        Entity.MoveFunction callback,
        CallbackInfo ci
    ) {
        if (!(passenger instanceof FacehuggerEntity)) {
            return;
        }

        var selfEntity = TargetingUtils.<Entity>self(this);

        if (!(selfEntity instanceof LivingEntity self)) {
            return;
        }

        var data = EntityHeadData.ENTITY_HEAD_DATA_BY_TYPE.get(self.getType());

        if (data == null) {
            return;
        }

        var yaw = Math.toRadians(self.yBodyRot);
        var px = data.pivot().x;
        var py = data.pivot().y;
        var pz = -data.pivot().z;

        var worldX = px * Math.cos(yaw) - pz * Math.sin(yaw);
        var worldZ = px * Math.sin(yaw) + pz * Math.cos(yaw);

        Vec3 pos = self.position().add(worldX, py, worldZ);

        callback.accept(passenger, pos.x, pos.y, pos.z);
    }
}
