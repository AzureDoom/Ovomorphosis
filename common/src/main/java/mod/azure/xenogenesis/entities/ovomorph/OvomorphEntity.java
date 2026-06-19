package mod.azure.xenogenesis.entities.ovomorph;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import mod.azure.xenogenesis.ai.core.MobBrainRuntime;
import mod.azure.xenogenesis.ai.util.NearestHostileTargetSelector;
import mod.azure.xenogenesis.ai.util.TargetingSystem;
import mod.azure.xenogenesis.ai.util.TargetingUtils;
import mod.azure.xenogenesis.entities.AbstractAlienEntity;

public class OvomorphEntity extends AbstractAlienEntity {

    private static final EntityDataAccessor<Boolean> HAS_FACEHUGGER = SynchedEntityData.defineId(
        OvomorphEntity.class,
        EntityDataSerializers.BOOLEAN
    );

    private static final EntityDataAccessor<Integer> EGG_STATE = SynchedEntityData.defineId(
        OvomorphEntity.class,
        EntityDataSerializers.INT
    );

    private final MobBrainRuntime<OvomorphEntity> brainRuntime;

    public final OvomorphAnimationDispatcher animationDispatcher;

    public OvomorphEntity(EntityType<? extends AbstractAlienEntity> entityType, Level level) {
        super(entityType, level);
        this.brainRuntime = new MobBrainRuntime<>(
            this,
            new TargetingSystem<>(
                new NearestHostileTargetSelector<>(16.0D),
                10
            ),
            OvomorphTree.create()
        );
        this.animationDispatcher = new OvomorphAnimationDispatcher(this);
    }

    public void setEggState(int value) {
        entityData.set(EGG_STATE, value);
    }

    public int getEggState() {
        return entityData.get(EGG_STATE);
    }

    public boolean hasFacehugger() {
        return entityData.get(HAS_FACEHUGGER);
    }

    public void setHasFacehugger(boolean value) {
        entityData.set(HAS_FACEHUGGER, value);
    }

    @Override
    public void defineSynchedData(SynchedEntityData.@NotNull Builder builder) {
        super.defineSynchedData(builder);
        builder.define(HAS_FACEHUGGER, true);
        builder.define(EGG_STATE, 0);
    }

    @Override
    public void addAdditionalSaveData(@NotNull CompoundTag nbt) {
        super.addAdditionalSaveData(nbt);
        nbt.putBoolean("hasFacehugger", hasFacehugger());
        nbt.putInt("eggState", getEggState());
    }

    @Override
    public void readAdditionalSaveData(@NotNull CompoundTag nbt) {
        super.readAdditionalSaveData(nbt);
        setHasFacehugger(nbt.getBoolean("hasFacehugger"));
        setEggState(nbt.getInt("eggState"));
    }

    public static AttributeSupplier.Builder createAttributes() {
        return LivingEntity.createLivingAttributes()
            .add(Attributes.MAX_HEALTH, 10)
            .add(
                Attributes.ARMOR,
                1.0
            )
            .add(Attributes.ARMOR_TOUGHNESS, 0.0)
            .add(
                Attributes.KNOCKBACK_RESISTANCE,
                0.0
            )
            .add(Attributes.FOLLOW_RANGE, 0.0)
            .add(Attributes.MOVEMENT_SPEED, 0.0);
    }

    @Override
    @NotNull
    public EntityDimensions getDefaultDimensions(@NotNull Pose pose) {
        if (this.getEggState() == EggStates.HATCHED.ordinal() && !this.isDeadOrDying())
            return EntityDimensions.scalable(1.0f, 0.6f);
        if (this.isDeadOrDying())
            return EntityDimensions.scalable(1.0f, 0.6f);
        return super.getDefaultDimensions(pose);
    }

    @Override
    public void travel(@NotNull Vec3 vec3) {
        if (this.tickCount % 10 == 0)
            this.refreshDimensions();
        super.travel(vec3);
    }

    @Override
    public @NotNull SoundEvent getDeathSound() {
        return SoundEvents.EMPTY;
    }

    @Override
    protected @NotNull SoundEvent getSwimSplashSound() {
        return SoundEvents.EMPTY;
    }

    @Override
    protected @NotNull SoundEvent getSwimSound() {
        return SoundEvents.EMPTY;
    }

    @Override
    public void doPush(@NotNull Entity entity) {
        if (
            !level().isClientSide && (entity instanceof LivingEntity living &&
                TargetingUtils.faceHuggerTest(this, living))
        ) {
            this.setEggState(EggStates.HATCHING.ordinal());
        }
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return this.isAlive();
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public boolean shouldPassengersInheritMalus() {
        return false;
    }

    @Override
    public @Nullable SpawnGroupData finalizeSpawn(
        @NotNull ServerLevelAccessor level,
        @NotNull DifficultyInstance difficulty,
        @NotNull MobSpawnType spawnType,
        @Nullable SpawnGroupData spawnGroupData
    ) {
        float yaw = this.getRandom().nextInt(4) * 90.0f;
        this.setYRot(yaw);
        this.yRotO = yaw;
        this.yBodyRot = yaw;
        this.yBodyRotO = yaw;
        super.setYHeadRot(yaw);
        return super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData);
    }

    @Override
    public void setYBodyRot(float yaw) {}

    @Override
    public void setYHeadRot(float yaw) {}

    @Override
    public void knockback(double strength, double x, double z) {}

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide()) {
            brainRuntime.tick();
        }

        var yaw = 90.0f;
        this.setYRot(yaw);
        this.yRotO = yaw;
        this.yBodyRot = yaw;
        this.yBodyRotO = yaw;
    }

    @Override
    public boolean hurt(@NotNull DamageSource source, float amount) {
        if (hasFacehugger() && amount > 0) {
            this.setEggState(EggStates.HATCHING.ordinal());
        }
        return super.hurt(source, amount);
    }

    public void updateAnimations() {
        if (this.isDeadOrDying() || this.getHealth() <= 0 || this.getEggState() == EggStates.HATCHED.ordinal()) {
            animationDispatcher.clientHatched();
            return;
        }

        if (this.getEggState() == EggStates.HATCHING.ordinal()) {
            animationDispatcher.clientHatching();
            return;
        }

        if (!this.isAggressive()) {
            animationDispatcher.clientIdle();
        }
    }
}
