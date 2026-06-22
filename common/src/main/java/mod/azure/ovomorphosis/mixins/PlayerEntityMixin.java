package mod.azure.ovomorphosis.mixins;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import mod.azure.ovomorphosis.entities.AbstractAlienEntity;
import mod.azure.ovomorphosis.entities.facehugger.FacehuggerEntity;

@Mixin(Player.class)
public abstract class PlayerEntityMixin extends LivingEntity {

    protected PlayerEntityMixin(EntityType<? extends LivingEntity> entityType, Level world) {
        super(entityType, world);
    }

    @Inject(method = { "wantsToStopRiding" }, at = { @At("RETURN") }, cancellable = true)
    protected void ovomorphosis$shouldDismount(CallbackInfoReturnable<Boolean> callbackInfo) {
        if (this.getVehicle() instanceof AbstractAlienEntity)
            callbackInfo.setReturnValue(false);
    }

    @Inject(method = { "interactOn" }, at = { @At("HEAD") }, cancellable = true)
    protected void ovomorphosis$stopPlayerUsing(
        Entity entity,
        InteractionHand hand,
        CallbackInfoReturnable<InteractionResult> callbackInfo
    ) {
        if (this.getPassengers().stream().anyMatch(FacehuggerEntity.class::isInstance))
            callbackInfo.setReturnValue(InteractionResult.FAIL);
    }

    @Inject(method = { "attack" }, at = { @At("HEAD") }, cancellable = true)
    protected void ovomorphosis$noAttacking(Entity target, CallbackInfo callbackInfo) {
        if (this.getPassengers().stream().anyMatch(FacehuggerEntity.class::isInstance))
            this.stopUsingItem();
    }

    @Inject(method = { "aiStep" }, at = { @At("HEAD") }, cancellable = true)
    public void ovomorphosis$tickMovement(CallbackInfo callbackInfo) {
        if (this.getPassengers().stream().anyMatch(FacehuggerEntity.class::isInstance))
            callbackInfo.cancel();
    }
}
