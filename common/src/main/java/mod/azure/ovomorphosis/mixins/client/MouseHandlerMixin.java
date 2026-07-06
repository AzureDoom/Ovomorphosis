package mod.azure.ovomorphosis.mixins.client;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import mod.azure.ovomorphosis.entities.facehugger.FacehuggerEntity;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

    @Unique
    private long ovomorphosis$lastFacehuggerLockMessageTime;

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void ovomorphosis$blockHeadMovement(CallbackInfo ci) {
        var minecraft = Minecraft.getInstance();

        if (minecraft.player == null) {
            return;
        }

        var hasFacehugger = minecraft.player.getPassengers()
            .stream()
            .anyMatch(entity -> entity instanceof FacehuggerEntity);

        if (hasFacehugger) {
            this.ovomorphosis$showFacehuggerLockMessage(minecraft);
            ci.cancel();
        }
    }

    @Unique
    private void ovomorphosis$showFacehuggerLockMessage(Minecraft minecraft) {
        var now = Util.getMillis();

        if (now - this.ovomorphosis$lastFacehuggerLockMessageTime < 1500L) {
            return;
        }

        this.ovomorphosis$lastFacehuggerLockMessageTime = now;

        if (minecraft.player == null) {
            return;
        }

        minecraft.player.displayClientMessage(
            Component.translatable("msg.ovomorphosis.facehugger_head_locked"),
            true
        );
    }
}
