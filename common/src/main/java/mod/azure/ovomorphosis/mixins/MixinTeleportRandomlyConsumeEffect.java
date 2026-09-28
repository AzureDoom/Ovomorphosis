package mod.azure.ovomorphosis.mixins;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.consume_effects.TeleportRandomlyConsumeEffect;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import mod.azure.ovomorphosis.entities.AbstractAlienEntity;
import mod.azure.ovomorphosis.entities.chestburster.ChestbursterEntity;
import mod.azure.ovomorphosis.entities.runner.RunnerEntity;
import mod.azure.ovomorphosis.infection.InfectionManager;
import mod.azure.ovomorphosis.registry.EntityRegistry;
import mod.azure.ovomorphosis.util.ModTags;

@Mixin(TeleportRandomlyConsumeEffect.class)
@SuppressWarnings("deprecation")
public class MixinTeleportRandomlyConsumeEffect {

    @Inject(method = "apply", at = @At("HEAD"), cancellable = true)
    private void ovomorphosis$removeEmbryo(
        Level level,
        ItemStack stack,
        LivingEntity user,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (!(level instanceof ServerLevel serverLevel))
            return;
        if (!InfectionManager.isInfected(user))
            return;

        var type = user.getType();

        if (type.builtInRegistryHolder().is(ModTags.XENOMORPH_HOST)) {
            ovomorphosis$tryTeleportingEntity(
                user,
                serverLevel,
                new ChestbursterEntity(EntityRegistry.CHESTBURSTER.get(), serverLevel)
            );
            cir.setReturnValue(true);
        } else if (type.builtInRegistryHolder().is(ModTags.RUNNER_HOST)) {
            ovomorphosis$tryTeleportingEntity(
                user,
                serverLevel,
                new RunnerEntity(EntityRegistry.RUNNER.get(), serverLevel)
            );
            cir.setReturnValue(true);
        }
    }

    @Unique
    private static void ovomorphosis$tryTeleportingEntity(
        LivingEntity host,
        ServerLevel level,
        AbstractAlienEntity alienEntity
    ) {
        if (host.isPassenger())
            host.stopRiding();

        InfectionManager.spawnMob(host, level, alienEntity);
        var entityPos = host.position();

        for (var i = 0; i < 16; i++) {
            var xOffset = alienEntity.getX() + (alienEntity.getRandom().nextDouble() - 0.5) * 16.0;
            var yOffset = Mth.clamp(
                alienEntity.getY() + (double) (alienEntity.getRandom().nextInt(16) - 8),
                level.getMinY(),
                level.getMinY() + level.getLogicalHeight() - 1
            );
            var zOffset = alienEntity.getZ() + (alienEntity.getRandom().nextDouble() - 0.5) * 16.0;

            if (!alienEntity.randomTeleport(xOffset, yOffset, zOffset, true, BlockTags.DANGEROUS_FOR_TELEPORTATION))
                continue;

            level.gameEvent(GameEvent.TELEPORT, entityPos, GameEvent.Context.of(alienEntity));
            level.playSound(null, alienEntity.blockPosition(), SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS);
            alienEntity.resetFallDistance();
            break;
        }

        InfectionManager.removeInfection(host.getUUID());
    }
}
