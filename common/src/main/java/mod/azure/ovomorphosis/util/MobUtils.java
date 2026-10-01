package mod.azure.ovomorphosis.util;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Set;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.entities.AbstractAlienEntity;
import mod.azure.ovomorphosis.entities.AcidEntity;
import mod.azure.ovomorphosis.registry.EntityRegistry;
import mod.azure.ovomorphosis.registry.SoundRegistry;
import mod.azure.ovomorphosis.services.XenoServices;

public class MobUtils {

    public static void spawnAcid(DamageSources damageSources, DamageSource source, AbstractAlienEntity alienEntity) {
        if (alienEntity.level().isClientSide()) {
            return;
        }

        if (
            Set.of(
                damageSources.genericKill(),
                damageSources.generic(),
                damageSources.onFire(),
                damageSources.magic(),
                damageSources.fall()
            ).contains(source)
        ) {
            return;
        }

        var acidEntity = new AcidEntity(EntityRegistry.ACID.get(), alienEntity.level());
        acidEntity.moveTo(alienEntity.getX(), alienEntity.getY(), alienEntity.getZ(), alienEntity.getYRot(), 0);
        alienEntity.level().addFreshEntity(acidEntity);
    }

    public static void spawnFireParticles(LivingEntity livingEntity, ServerLevel level) {
        var random = livingEntity.getRandom();
        var box = livingEntity.getBoundingBox();

        for (var i = 0; i < 4; i++) {
            var x = box.minX + random.nextDouble() * box.getXsize();
            var y = box.minY + random.nextDouble() * box.getYsize();
            var z = box.minZ + random.nextDouble() * box.getZsize();

            level.sendParticles(ParticleTypes.FLAME, x, y, z, 1, 0.02D, 0.03D, 0.02D, 0.0D);

            if (random.nextFloat() < 0.35F) {
                level.sendParticles(ParticleTypes.SMOKE, x, y, z, 1, 0.02D, 0.03D, 0.02D, 0.0D);
            }
        }
    }

    public static void applyCustomGravity(Entity entity) {
        entity.applyGravity();
        entity.move(MoverType.SELF, entity.getDeltaMovement());
        entity.setDeltaMovement(entity.getDeltaMovement().scale(0.38));
    }

    public static void applyParticles(RandomSource random, Entity entity) {
        for (var i = 0; i < random.nextIntBetweenInclusive(0, 4); i++) {
            entity.level()
                .addAlwaysVisibleParticle(
                    ParticleTypes.COMPOSTER,
                    entity.blockPosition().getX() + random.nextDouble(),
                    entity.blockPosition().getY() + 0.09,
                    entity.blockPosition().getZ() + random.nextDouble(),
                    0.0,
                    0.0,
                    0.0
                );
        }
    }

    public static void applySounds(int age, RandomSource random, Entity entity) {
        if (age == 1 || age % 40 == 0) {
            entity.level()
                .playSound(
                    null,
                    entity.blockPosition().getX(),
                    entity.blockPosition().getY(),
                    entity.blockPosition().getZ(),
                    SoundRegistry.ACID.get(),
                    SoundSource.BLOCKS,
                    0.2f + random.nextFloat() * 0.2f,
                    0.9f + random.nextFloat() * 0.15f
                );
        }
    }

    /**
     * Acid work applied per hit (every 5 ticks) is {@code acidDestroySpeedMultiplier / ACID_WORK_DIVISOR}, in hardness
     * units. At the default multiplier of 3 that dissolves stone (hardness 1.5) in ~15 seconds, dirt in ~5 seconds, and
     * obsidian in several minutes.
     */
    private static final float ACID_WORK_DIVISOR = 120F;

    public static void applyBlockBreaking(int age, Entity entity) {
        if (age % 5 != 0 || !CommonMod.getConfig().enableAcidBlockBreaking) {
            return;
        }

        if (
            !(entity.level() instanceof ServerLevel serverLevel)
                || !XenoServices.COMMON_REGISTRY.canEntityGrief(serverLevel, entity)
        ) {
            return;
        }

        var posBelow = entity.blockPosition().below();
        var blockStateBelow = serverLevel.getBlockState(posBelow);

        if (blockStateBelow.isAir() || blockStateBelow.is(ModTags.ACID_RESISTANT_BLOCKS)) {
            return;
        }

        BlockBreakProgressManager.damage(
            serverLevel,
            posBelow,
            CommonMod.getConfig().acidDestroySpeedMultiplier / ACID_WORK_DIVISOR
        );
    }

    public static void applyContactEffects(int age, RandomSource random, Entity entity) {
        var entities = entity.level().getEntitiesOfClass(Entity.class, entity.getBoundingBox().inflate(1));

        if (age % 40 != 0)
            return;
        for (var e : entities) {
            if (e instanceof LivingEntity living) {
                if (shouldSkipAcidEffect(living)) {
                    continue;
                }
                living.addEffect(
                    new MobEffectInstance(MobEffects.POISON, 60, random.nextIntBetweenInclusive(0, 4))
                );
            } else if (
                e instanceof ItemEntity item && CommonMod.getConfig().enableAcidItemBreaking
            ) {
                var itemStack = item.getItem();
                if (itemStack.getMaxDamage() < 2) {
                    itemStack.shrink(1);
                } else {
                    itemStack.setDamageValue(itemStack.getDamageValue() + random.nextIntBetweenInclusive(0, 4));
                }
            }
        }
    }

    private static boolean shouldSkipAcidEffect(LivingEntity living) {
        return living.hasEffect(MobEffects.POISON)
            || living.getType().is(ModTags.ACID_RESISTANT_ENTITIES)
            || living instanceof Player player
                && (player.isCreative() || player.isSpectator());
    }

    public static boolean hasBlockingHelmet(LivingEntity target) {
        var helmet = target.getItemBySlot(EquipmentSlot.HEAD);
        return !helmet.isEmpty() && helmet.is(ModTags.FACEHUGGER_BLOCKING_HELMETS);
    }

    public static void punishBlockingHelmet(LivingEntity target) {
        if (!(target.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        var helmet = target.getItemBySlot(EquipmentSlot.HEAD);
        if (helmet.isEmpty()) {
            return;
        }

        if (target.getRandom().nextFloat() < 0.05F) {
            target.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            target.spawnAtLocation(helmet);
            target.playSound(SoundEvents.ITEM_BREAK, 1.0F, 1.0F);
            return;
        }

        if (helmet.isDamageableItem() && target instanceof ServerPlayer player) {
            helmet.hurtAndBreak(
                1,
                serverLevel,
                player,
                item -> target.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY)
            );
        }
    }
}
