package mod.azure.ovomorphosis.mixins;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import mod.azure.ovomorphosis.client.facehugger.EntityHeadData;
import mod.azure.ovomorphosis.entities.AbstractAlienEntity;
import mod.azure.ovomorphosis.entities.facehugger.FacehuggerEntity;
import mod.azure.ovomorphosis.infection.InfectionManager;

/**
 * @author Boston Vanseghi/AzureDoom
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends Entity {

    protected LivingEntityMixin(EntityType<?> type, Level world) {
        super(type, world);
    }

    @Shadow
    public abstract boolean hurt(@NotNull DamageSource source, float amount);

    @Inject(method = "die", at = @At("TAIL"))
    public void ovomorphosis$onDie(DamageSource source, CallbackInfo ci) {
        InfectionManager.clearInfection(LivingEntityMixin.self(this));
    }

    @Inject(method = { "hurt" }, at = { @At("HEAD") }, cancellable = true)
    public void ovomorphosis$hurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> callbackInfo) {
        if (
            this.getVehicle() != null && this.getVehicle() instanceof AbstractAlienEntity && (source == damageSources()
                .drown() || source == damageSources().inWall())
        )
            callbackInfo.setReturnValue(false);
        if (
            amount >= 2 && this.getFirstPassenger() != null && this.getPassengers()
                .stream()
                .anyMatch(
                    FacehuggerEntity.class::isInstance
                )
        ) {
            var facehugger = (FacehuggerEntity) this.getFirstPassenger();
            facehugger.hurt(source, amount / 2);
            facehugger.addEffect(
                new MobEffectInstance(
                    MobEffects.CONFUSION,
                    40,
                    60,
                    false,
                    false
                )
            );
            facehugger.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 10, false, false));
            // facehugger.animationDispatcher.sendStunned();
            facehugger.unRide();
        }
    }

    @Inject(method = { "tick" }, at = { @At("HEAD") })
    void ovomorphosis$tick(CallbackInfo callbackInfo) {
        if (!this.level().isClientSide) {
            if (this.getPassengers().stream().anyMatch(AbstractAlienEntity.class::isInstance)) {
                this.setAirSupply(this.getMaxAirSupply());
            }
        }
    }

    @Inject(method = { "isImmobile" }, at = { @At("RETURN") }, cancellable = true)
    protected void ovomorphosis$isImmobile(CallbackInfoReturnable<Boolean> callbackInfo) {
        if (this.getPassengers().stream().anyMatch(FacehuggerEntity.class::isInstance))
            callbackInfo.setReturnValue(true);
    }

    @Inject(method = "getPassengerRidingPosition", at = @At("RETURN"), cancellable = true)
    private void ovomorphosis$faceRidingPosition(Entity passenger, CallbackInfoReturnable<Vec3> cir) {
        if (!(passenger instanceof FacehuggerEntity))
            return;

        var self = (LivingEntity) (Object) this;
        var data = EntityHeadData.ENTITY_HEAD_DATA_BY_TYPE.get(self.getType());
        if (data == null)
            return;

        var yaw = Math.toRadians(self.yBodyRot);
        var px = data.pivot().x;
        var py = data.pivot().y;
        var pz = -data.pivot().z;

        var worldX = px * Math.cos(yaw) - pz * Math.sin(yaw);
        var worldZ = px * Math.sin(yaw) + pz * Math.cos(yaw);

        cir.setReturnValue(self.position().add(worldX, py, worldZ));
    }

    private static <T> T self(Object object) {
        return (T) object;
    }
}
