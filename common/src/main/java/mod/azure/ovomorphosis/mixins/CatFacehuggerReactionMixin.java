package mod.azure.ovomorphosis.mixins;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.animal.Cat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import mod.azure.ovomorphosis.ai.util.TargetingUtils;
import mod.azure.ovomorphosis.entities.AbstractAlienEntity;

@Mixin(Cat.class)
public class CatFacehuggerReactionMixin {

    @Unique
    private static final int REACT_RANGE = 10;

    @Unique
    private static final int COOLDOWN_TICKS = 60;

    @Unique
    private int ovomorphosis$hissCooldown = 0;

    @Inject(method = "tick", at = @At("TAIL"))
    private void ovomorphosis$onAiStep(CallbackInfo ci) {
        var self = TargetingUtils.<Cat>self(this);
        var level = self.level();

        if (level.isClientSide())
            return;

        if (ovomorphosis$hissCooldown > 0) {
            ovomorphosis$hissCooldown--;
            return;
        }

        var nearby = level.getEntitiesOfClass(
            AbstractAlienEntity.class,
            self.getBoundingBox().inflate(REACT_RANGE),
            e -> !e.isDeadOrDying()
        );

        if (!nearby.isEmpty()) {
            self.playSound(SoundEvents.CAT_HISS, 1.0F, self.getVoicePitch());
            ovomorphosis$hissCooldown = COOLDOWN_TICKS;
        }
    }
}
