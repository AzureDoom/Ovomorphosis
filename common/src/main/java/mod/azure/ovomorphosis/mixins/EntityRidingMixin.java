package mod.azure.ovomorphosis.mixins;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import mod.azure.ovomorphosis.entities.facehugger.FacehuggerEntity;

@Mixin(Entity.class)
public abstract class EntityRidingMixin {

    /**
     * Players aren't serializable vehicles, so vanilla refuses to let anything ride them server-side. The facehugger is
     * dismounted on player removal (logout/shutdown) anyway, so it never needs saving as a player passenger.
     */
    @ModifyExpressionValue(
        method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/EntityType;canSerialize()Z")
    )
    private boolean ovomorphosis$allowFacehuggerOnPlayers(boolean canSerialize, Entity entityToRide) {
        var self = (Entity) (Object) this;
        return canSerialize || (self instanceof FacehuggerEntity && entityToRide instanceof Player);
    }
}
