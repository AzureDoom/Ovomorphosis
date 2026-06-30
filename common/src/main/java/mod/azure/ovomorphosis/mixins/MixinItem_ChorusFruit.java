package mod.azure.ovomorphosis.mixins;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ChorusFruitItem;
import net.minecraft.world.item.ItemStack;
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

@Mixin(ChorusFruitItem.class)
public class MixinItem_ChorusFruit {

    @Inject(method = "finishUsingItem", at = @At("HEAD"), cancellable = true)
    private void ovomorphosis$removeEmbryo(
        ItemStack stack,
        Level level,
        LivingEntity livingEntity,
        CallbackInfoReturnable<ItemStack> cir
    ) {
        if (!InfectionManager.isInfected(livingEntity))
            return;
        if (level.isClientSide)
            return;

        if (livingEntity.getType().is(ModTags.XENOMORPH_HOST)) {
            ovomorphosis$tryTeleportingEntity(
                livingEntity,
                new ChestbursterEntity(EntityRegistry.CHESTBURSTER.get(), livingEntity.level()),
                stack,
                cir
            );
        } else if (livingEntity.getType().is(ModTags.RUNNER_HOST)) {
            ovomorphosis$tryTeleportingEntity(
                livingEntity,
                new RunnerEntity(EntityRegistry.RUNNER.get(), livingEntity.level()),
                stack,
                cir
            );
        }
    }

    @Unique
    private static void ovomorphosis$tryTeleportingEntity(
        LivingEntity host,
        AbstractAlienEntity alienEntity,
        ItemStack stack,
        CallbackInfoReturnable<ItemStack> cir
    ) {
        var level = host.level();
        if (level.isClientSide)
            return;
        if (host.isPassenger())
            host.stopRiding();

        InfectionManager.spawnMob(host, (ServerLevel) level, alienEntity);
        var entityPos = host.position();

        for (var i = 0; i < 16; i++) {
            var xOffset = alienEntity.getX() + (alienEntity.getRandom().nextDouble() - 0.5) * 16.0;
            var yOffset = Mth.clamp(
                alienEntity.getY() + (double) (alienEntity.getRandom().nextInt(16) - 8),
                level.getMinBuildHeight(),
                level.getMinBuildHeight() + ((ServerLevel) level).getLogicalHeight() - 1
            );
            var zOffset = alienEntity.getZ() + (alienEntity.getRandom().nextDouble() - 0.5) * 16.0;

            if (!alienEntity.randomTeleport(xOffset, yOffset, zOffset, true))
                continue;

            level.gameEvent(GameEvent.TELEPORT, entityPos, GameEvent.Context.of(alienEntity));
            level.playSound(null, alienEntity.blockPosition(), SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS);
            alienEntity.resetFallDistance();
            break;
        }
        InfectionManager.removeInfection(host.getUUID());
        cir.setReturnValue(stack);
    }
}
