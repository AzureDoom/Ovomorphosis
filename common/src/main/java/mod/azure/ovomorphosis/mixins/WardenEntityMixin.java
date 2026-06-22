package mod.azure.ovomorphosis.mixins;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.warden.Warden;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import mod.azure.ovomorphosis.entities.AbstractAlienEntity;

@Mixin(Warden.class)
public class WardenEntityMixin {

    @Inject(method = { "canTargetEntity" }, at = { @At("HEAD") }, cancellable = true)
    void ovomorphosis$tick(@Nullable Entity entity, CallbackInfoReturnable<Boolean> ci) {
        if (entity instanceof AbstractAlienEntity)
            ci.setReturnValue(false);
    }
}
