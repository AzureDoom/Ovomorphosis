package mod.azure.xenogenesis.entities.xenomorph;

import mod.azure.azurelib.common.util.MoveAnalysis;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.SplittableRandom;

import mod.azure.xenogenesis.CommonMod;
import mod.azure.xenogenesis.ai.core.MobBrainRuntime;
import mod.azure.xenogenesis.ai.util.CrawlingManager;
import mod.azure.xenogenesis.ai.util.NearestHostileTargetSelector;
import mod.azure.xenogenesis.ai.util.TargetingSystem;
import mod.azure.xenogenesis.entities.AbstractAlienEntity;
import mod.azure.xenogenesis.registry.SoundRegistry;
import mod.azure.xenogenesis.util.Growable;

public class XenomorphEntity extends AbstractAlienEntity implements Growable {

    protected static final EntityDataAccessor<Float> GROWTH = SynchedEntityData.defineId(
        XenomorphEntity.class,
        EntityDataSerializers.FLOAT
    );

    protected static final EntityDataAccessor<Boolean> IS_EXECUTION = SynchedEntityData.defineId(
        XenomorphEntity.class,
        EntityDataSerializers.BOOLEAN
    );

    private final MobBrainRuntime<XenomorphEntity> brainRuntime;

    public final XenomorphAnimationDispatcher animationDispatcher;

    public XenomorphEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        this.animationDispatcher = new XenomorphAnimationDispatcher(this);
        this.moveAnalysis = new MoveAnalysis(this);
        this.brainRuntime = new MobBrainRuntime<>(
            this,
            new TargetingSystem<>(
                new NearestHostileTargetSelector<>(
                    CommonMod.getConfig().entityConfigs.xenomorphConfigs.xenoHostileRange
                ),
                10
            ),
            XenomorphTree.create()
        );
    }

    public static AttributeSupplier.Builder createAttributes() {
        return LivingEntity.createLivingAttributes()
            .add(Attributes.MAX_HEALTH, CommonMod.getConfig().entityConfigs.xenomorphConfigs.xenoHealth)
            .add(
                Attributes.ARMOR,
                CommonMod.getConfig().entityConfigs.xenomorphConfigs.xenoArmor
            )
            .add(Attributes.ARMOR_TOUGHNESS, CommonMod.getConfig().entityConfigs.xenomorphConfigs.xenoArmorToughness)
            .add(
                Attributes.KNOCKBACK_RESISTANCE,
                CommonMod.getConfig().entityConfigs.xenomorphConfigs.xenoKnockbackRes
            )
            .add(Attributes.FOLLOW_RANGE, 0.0)
            .add(Attributes.MOVEMENT_SPEED, 0.0)
            .add(Attributes.ATTACK_DAMAGE, CommonMod.getConfig().entityConfigs.xenomorphConfigs.xenoAttackDamage);
    }

    @Override
    public void tick() {
        super.tick();
        moveAnalysis.update();
        if (!this.level().isClientSide()) {
            brainRuntime.tick();
            CrawlingManager.updateWallCrawlingPhysics(this);
            if (this.isAlive()) {
                grow(this, 1);
            }
        }
    }

    @Override
    public @NotNull SoundEvent getDeathSound() {
        return SoundRegistry.XENOMORPH_DEATH.get();
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundRegistry.XENOMORPH_IDLE.get();
    }

    @Override
    protected @NotNull SoundEvent getHurtSound(@NotNull DamageSource damageSourceIn) {
        return SoundRegistry.XENOMORPH_HURT.get();
    }

    @Override
    protected void playStepSound(@NotNull BlockPos pos, @NotNull BlockState blockIn) {
        this.playSound(SoundRegistry.XENOMORPH_FOOTSTEP.get(), 0.15F, 1.0F);
    }

    @Override
    public @Nullable SpawnGroupData finalizeSpawn(
        @NotNull ServerLevelAccessor level,
        @NotNull DifficultyInstance difficulty,
        @NotNull MobSpawnType spawnType,
        @Nullable SpawnGroupData spawnGroupData
    ) {
        if (spawnType == MobSpawnType.SPAWN_EGG || spawnType == MobSpawnType.COMMAND)
            setGrowth(1200);
        return super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData);
    }

    @Override
    public float getGrowth() {
        return entityData.get(GROWTH);
    }

    @Override
    public void setGrowth(float growth) {
        entityData.set(GROWTH, growth);
    }

    @Override
    public float getMaxGrowth() {
        return 1200;
    }

    @Override
    public LivingEntity growInto() {
        return null;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.@NotNull Builder builder) {
        super.defineSynchedData(builder);
        builder.define(GROWTH, 0.0F);
        builder.define(IS_EXECUTION, false);
    }

    @Override
    public void addAdditionalSaveData(@NotNull CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("growth", getGrowth());
        tag.putBoolean("isExecuting", this.isExecuting());
    }

    @Override
    public void readAdditionalSaveData(@NotNull CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.setGrowth(tag.getFloat("growth"));
        this.setIsExecuting(tag.getBoolean("isExecuting"));
    }

    @Override
    protected boolean canRide(@NotNull Entity vehicle) {
        return false;
    }

    @Override
    public void positionRider(@NotNull Entity entity, @NotNull MoveFunction moveFunction) {
        if (entity instanceof LivingEntity mob) {
            var random = new SplittableRandom();
            mob.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 100, true, true));
            mob.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 40, 1, true, true));
            var f = Mth.sin(this.yBodyRot * ((float) Math.PI / 180));
            var g = Mth.cos(this.yBodyRot * ((float) Math.PI / 180));
            var y1 = random.nextFloat(0.14F, 0.15F);
            var y3 = random.nextFloat(0.44F, 0.45F);
            var y = random.nextFloat(0.74F, 0.75f);
            var y2 = random.nextFloat(1.14F, 1.15f);
            mob.setPos(
                this.getX() + ((this.isExecuting() ? -1.4f : -1.85f) * f),
                this.getY() + (this.isExecuting()
                    ? (mob.getBbHeight() < 1.4 ? y2 : y)
                    : (mob.getBbHeight() < 1.4 ? y3 : y1)),
                this.getZ() - ((this.isExecuting() ? -1.4f : -1.85f) * g)
            );
            mob.yBodyRot = this.yBodyRot;
            mob.setSpeed(0);
        }
    }

    @Override
    @NotNull
    public EntityDimensions getDefaultDimensions(@NotNull Pose pose) {
        if (this.xenogenesis$isWallCrawling())
            return EntityDimensions.scalable(0.9F, 0.9F);
        return super.getDefaultDimensions(pose);
    }

    public boolean isExecuting() {
        return entityData.get(IS_EXECUTION);
    }

    public void setIsExecuting(boolean isExecuting) {
        entityData.set(IS_EXECUTION, isExecuting);
    }

    public void updateAnimations() {
        var isMovingOnGround = moveAnalysis.isMovingHorizontally() && onGround();

        if (this.isDeadOrDying() || this.getHealth() <= 0) {
            animationDispatcher.clientDeath();
            return;
        }

        if (isMovingOnGround) {
            if (this.isAggressive() && !this.swinging) {
                animationDispatcher.clientRun();
            } else {
                animationDispatcher.clientWalk();
            }
            return;
        }

        if (!this.isAggressive()) {
            animationDispatcher.clientIdle();
        }
    }
}
