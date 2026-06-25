package mod.azure.ovomorphosis.mixins;

import mod.azure.ovomorphosis.ai.util.TargetingUtils;
import mod.azure.ovomorphosis.entities.AbstractAlienEntity;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.animal.Wolf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Wolf.class)
public class WolfFacehuggerReactionMixin {

    @Unique
    private static final int REACT_RANGE = 12;

    @Unique
    private static final int COOLDOWN_TICKS = 80;

    @Unique
    private int ovomorphosis$growlCooldown = 0;

    @Inject(method = "tick", at = @At("TAIL"))
    private void ovomorphosis$onAiStep(CallbackInfo ci) {
        var self = TargetingUtils.<Wolf>self(this);
        var level = self.level();

        if (level.isClientSide())
            return;

        if (ovomorphosis$growlCooldown > 0) {
            ovomorphosis$growlCooldown--;
            return;
        }

        var nearby = level.getEntitiesOfClass(
            AbstractAlienEntity.class,
            self.getBoundingBox().inflate(REACT_RANGE),
            e -> !e.isDeadOrDying()
        );

        if (!nearby.isEmpty()) {
            self.playSound(SoundEvents.WOLF_GROWL, 1.0F, self.getVoicePitch());
            ovomorphosis$growlCooldown = COOLDOWN_TICKS;
        }
    }
}
