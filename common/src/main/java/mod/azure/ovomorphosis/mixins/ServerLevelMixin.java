package mod.azure.ovomorphosis.mixins;

import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

import mod.azure.ovomorphosis.data.OvomorphosisSavedData;
import mod.azure.ovomorphosis.infection.EggmorphTracker;
import mod.azure.ovomorphosis.infection.InfectionManager;
import mod.azure.ovomorphosis.util.BlockBreakProgressManager;

@Mixin(ServerLevel.class)
public class ServerLevelMixin {

    @Unique
    private boolean ovomorphosis$dataLoaded = false;

    @Inject(at = @At("HEAD"), method = "tick(Ljava/util/function/BooleanSupplier;)V")
    public void ovomorphosis$tick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        var serverLevel = ServerLevel.class.cast(this);

        if (!ovomorphosis$dataLoaded) {
            OvomorphosisSavedData.get(serverLevel);
            ovomorphosis$dataLoaded = true;
        }

        BlockBreakProgressManager.tick(serverLevel);
        InfectionManager.tick(serverLevel);
        EggmorphTracker.tickAll(serverLevel);
    }
}
