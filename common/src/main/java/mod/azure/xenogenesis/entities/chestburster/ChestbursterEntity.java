package mod.azure.xenogenesis.entities.chestburster;

import mod.azure.azurelib.common.util.MoveAnalysis;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import mod.azure.xenogenesis.ai.core.MobBrainRuntime;
import mod.azure.xenogenesis.ai.util.NearestHostileTargetSelector;
import mod.azure.xenogenesis.ai.util.TargetingSystem;
import mod.azure.xenogenesis.entities.AbstractAlienEntity;
import mod.azure.xenogenesis.entities.xenomorph.XenomorphEntity;
import mod.azure.xenogenesis.registry.EntityRegistry;
import mod.azure.xenogenesis.registry.SoundRegistry;
import mod.azure.xenogenesis.util.Growable;

public class ChestbursterEntity extends AbstractAlienEntity implements Growable {

    protected static final EntityDataAccessor<Float> GROWTH = SynchedEntityData.defineId(
        ChestbursterEntity.class,
        EntityDataSerializers.FLOAT
    );

    private final MobBrainRuntime<ChestbursterEntity> brainRuntime;

    public final ChestbursterAnimationDispatcher animationDispatcher;

    public ChestbursterEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        this.animationDispatcher = new ChestbursterAnimationDispatcher(this);
        this.moveAnalysis = new MoveAnalysis(this);
        this.brainRuntime = new MobBrainRuntime<>(
            this,
            new TargetingSystem<>(
                new NearestHostileTargetSelector<>(16.0D),
                10
            ),
            ChestbursterTree.create()
        );
    }

    public static AttributeSupplier.Builder createAttributes() {
        return LivingEntity.createLivingAttributes()
            .add(Attributes.MAX_HEALTH, 30)
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
    public void tick() {
        super.tick();
        moveAnalysis.update();
        if (!this.level().isClientSide()) {
            brainRuntime.tick();
            if (this.isAlive()) {
                grow(this, 1);
            }
        }
        if (this.isNoAi()) {
            var yaw = 90.0f;
            this.setYRot(yaw);
            this.yRotO = yaw;
            this.yBodyRot = yaw;
            this.yBodyRotO = yaw;
        }
    }

    @Override
    public @NotNull SoundEvent getDeathSound() {
        return SoundEvents.EMPTY;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundRegistry.CHESTBUSTER_IDLE.get();
    }

    @Override
    protected @NotNull SoundEvent getHurtSound(@NotNull DamageSource damageSourceIn) {
        return SoundRegistry.CHESTBUSTER_PAIN.get();
    }

    @Override
    protected void playStepSound(@NotNull BlockPos pos, @NotNull BlockState blockIn) {
        this.playSound(SoundRegistry.CHESTBUSTER_FOOTSTEP.get(), 0.15F, 1.0F);
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
        return 2400;
    }

    @Override
    public LivingEntity growInto() {
        return new XenomorphEntity(EntityRegistry.XENOMORPH.get(), level());
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.@NotNull Builder builder) {
        super.defineSynchedData(builder);
        builder.define(GROWTH, 0.0F);
    }

    @Override
    public void addAdditionalSaveData(@NotNull CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("growth", getGrowth());
    }

    @Override
    public void readAdditionalSaveData(@NotNull CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.setGrowth(tag.getFloat("growth"));
    }

    public void updateAnimations() {
        var isMovingOnGround = moveAnalysis.isMovingHorizontally() && onGround();

        if (this.isDeadOrDying() || this.getHealth() <= 0) {
            animationDispatcher.clientDeath();
            return;
        }

        if (isMovingOnGround) {
            animationDispatcher.clientWalk();
            return;
        }

        if (!this.isAggressive()) {
            animationDispatcher.clientIdle();
        }
    }
}
