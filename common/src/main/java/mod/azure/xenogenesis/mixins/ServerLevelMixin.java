package mod.azure.xenogenesis.mixins;

import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import mod.azure.xenogenesis.blocks.EggmorphTracker;
import mod.azure.xenogenesis.data.XenogenesisSavedData;
import mod.azure.xenogenesis.infection.InfectionManager;
import mod.azure.xenogenesis.util.BlockBreakProgressManager;

@Mixin(ServerLevel.class)
public class ServerLevelMixin {

    private boolean xenogenesis$dataLoaded = false;

    @Inject(at = @At("HEAD"), method = "tick(Ljava/util/function/BooleanSupplier;)V")
    public void xenogenesis$tick(java.util.function.BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        var serverLevel = ServerLevel.class.cast(this);

        if (!xenogenesis$dataLoaded) {
            XenogenesisSavedData.get(serverLevel);
            xenogenesis$dataLoaded = true;
        }

        BlockBreakProgressManager.tick(serverLevel);
        InfectionManager.tick(serverLevel);
        EggmorphTracker.tickAll(serverLevel);
    }
}
