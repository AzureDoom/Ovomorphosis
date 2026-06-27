package mod.azure.ovomorphosis.entities;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.registry.SoundRegistry;
import mod.azure.ovomorphosis.util.BlockBreakProgressManager;
import mod.azure.ovomorphosis.util.ModTags;

public class AcidEntity extends Entity {

    public int age = 0;

    public AcidEntity(EntityType<? extends Entity> entityType, Level level) {
        super(entityType, level);
        this.setDeltaMovement(Vec3.ZERO);
    }

    @Override
    protected void defineSynchedData() {}

    @Override
    protected void readAdditionalSaveData(CompoundTag compoundTag) {
        if (compoundTag.contains("aliveTicks")) {
            age = compoundTag.getInt("aliveTicks");
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag compoundTag) {
        compoundTag.putInt("aliveTicks", age);
    }

    @Override
    public boolean dampensVibrations() {
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        age++;
        if (level().isClientSide()) {
            this.applyParticles();
            return;
        }
        if (age == 1) {
            moveTo(blockPosition().offset(0, 0, 0), getYRot(), getXRot());
        }
        this.applyCustomGravity();
        this.applyBlockBreaking();
        this.applyContactEffects();
        this.applySounds();

        if (
            age >= random.nextIntBetweenInclusive(400, 800) || level().getBlockState(blockPosition())
                .is(Blocks.LAVA)
        ) {
            kill();
        }
    }

    private void applyParticles() {
        for (var i = 0; i < random.nextIntBetweenInclusive(0, 4); i++) {
            level().addAlwaysVisibleParticle(
                ParticleTypes.COMPOSTER,
                blockPosition().getX() + random.nextDouble(),
                blockPosition().getY() + 0.09,
                blockPosition().getZ() + random.nextDouble(),
                0.0,
                0.0,
                0.0
            );
        }
    }

    private void applySounds() {
        if (age == 1 || age % 40 == 0) {
            level().playSound(
                null,
                blockPosition().getX(),
                blockPosition().getY(),
                blockPosition().getZ(),
                SoundRegistry.ACID.get(),
                SoundSource.BLOCKS,
                0.2f + random.nextFloat() * 0.2f,
                0.9f + random.nextFloat() * 0.15f
            );
        }
    }

    private void applyBlockBreaking() {
        if (
            age % 5 == 0 &&
                (CommonMod.getConfig().enableAcidBlockBreaking || level().getGameRules()
                    .getBoolean(GameRules.RULE_MOBGRIEFING))
        ) {
            var blockStateBelow = level().getBlockState(blockPosition().below());
            if (!blockStateBelow.is(ModTags.ACID_RESISTANT_BLOCKS)) {
                var blockHardness = blockStateBelow.getDestroySpeed(
                    level(),
                    blockPosition().below()
                );
                BlockBreakProgressManager.damage(
                    level(),
                    blockPosition().below(),
                    blockHardness * CommonMod.getConfig().acidDestroySpeedMultiplier
                );
            }
        }
    }

    private void applyContactEffects() {
        var entities = level().getEntitiesOfClass(Entity.class, getBoundingBox().inflate(1));

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

    private void applyCustomGravity() {
        if (!this.isNoGravity()) {
            this.setDeltaMovement(this.getDeltaMovement().add(0.0D, -0.04D, 0.0D));
        }
        move(MoverType.SELF, getDeltaMovement());
        setDeltaMovement(getDeltaMovement().scale(0.38));
    }

    private static boolean shouldSkipAcidEffect(LivingEntity living) {
        return living.hasEffect(MobEffects.POISON)
            || living.getType().is(ModTags.ACID_RESISTANT_ENTITIES)
            || living instanceof Player player
                && (player.isCreative() || player.isSpectator());
    }
}
