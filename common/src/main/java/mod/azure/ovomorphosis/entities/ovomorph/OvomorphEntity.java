package mod.azure.ovomorphosis.entities.ovomorph;

import com.azure.azurecortex.runtime.CortexRuntime;
import com.azure.azurecortex.sensing.TargetSensor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.util.OvomorphHostTargetSelector;
import mod.azure.ovomorphosis.ai.util.TargetingUtils;
import mod.azure.ovomorphosis.entities.AbstractAlienEntity;
import mod.azure.ovomorphosis.registry.BlockRegistry;
import mod.azure.ovomorphosis.util.ClientAnimState;
import mod.azure.ovomorphosis.util.OvomorphStructureChecks;

public class OvomorphEntity extends AbstractAlienEntity {

    private static final EntityDataAccessor<Boolean> HAS_FACEHUGGER = SynchedEntityData.defineId(
        OvomorphEntity.class,
        EntityDataSerializers.BOOLEAN
    );

    private static final EntityDataAccessor<Integer> EGG_STATE = SynchedEntityData.defineId(
        OvomorphEntity.class,
        EntityDataSerializers.INT
    );

    private int deathTickCounter;

    private final CortexRuntime<OvomorphEntity, AiGoalType> brainRuntime;

    public final OvomorphAnimationDispatcher animationDispatcher;

    public OvomorphEntity(EntityType<? extends AbstractAlienEntity> entityType, Level level) {
        super(entityType, level);
        this.brainRuntime = new CortexRuntime<>(
            this,
            new TargetSensor<>(new OvomorphHostTargetSelector<>(), 10, TargetSensor.lineOfSight()),
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
    protected void defineSynchedData() {
        super.defineSynchedData();

        this.entityData.define(HAS_FACEHUGGER, true);
        this.entityData.define(EGG_STATE, 0);
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
            .add(Attributes.MAX_HEALTH, CommonMod.getConfig().entityConfigs.ovomorphConfigs.ovomorphHealth)
            .add(
                Attributes.ARMOR,
                CommonMod.getConfig().entityConfigs.ovomorphConfigs.ovomorphArmor
            )
            .add(Attributes.ARMOR_TOUGHNESS, CommonMod.getConfig().entityConfigs.ovomorphConfigs.ovomorphArmorToughness)
            .add(
                Attributes.KNOCKBACK_RESISTANCE,
                1.0
            )
            .add(Attributes.FOLLOW_RANGE, 0.0)
            .add(Attributes.MOVEMENT_SPEED, 0.0);
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
        if (entity instanceof OvomorphEntity) {
            return;
        }

        if (
            !level().isClientSide &&
                entity instanceof LivingEntity living &&
                TargetingUtils.faceHuggerTest(this, living)
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
        @Nullable SpawnGroupData spawnData,
        @Nullable CompoundTag dataTag
    ) {
        float yaw = this.getRandom().nextInt(4) * 90.0f;
        this.setYRot(yaw);
        this.yRotO = yaw;
        this.yBodyRot = yaw;
        this.yBodyRotO = yaw;
        super.setYHeadRot(yaw);
        return super.finalizeSpawn(level, difficulty, spawnType, spawnData, dataTag);
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
            if (this.getEggState() == EggStates.HATCHED.ordinal()) {
                deathTickCounter++;
            }
            if (deathTickCounter >= 600) {
                this.level()
                    .setBlockAndUpdate(this.blockPosition(), BlockRegistry.RESIN_WEB_CROSS.get().defaultBlockState());
                this.remove(RemovalReason.DISCARDED);
            }
            brainRuntime.tick();
        }
        if (tickCount == 1) {
            moveTo(Mth.floor(getX()) + 0.5, getY(), Mth.floor(getZ()) + 0.5, getYRot(), getXRot());
        }
        this.setDeltaMovement(Vec3.ZERO);
        this.hasImpulse = false;

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

    /**
     * Returns true if all cardinal directions + upper diagonals are solid, meaning the egg is in a fully enclosed space
     * and should not hatch.
     */
    public boolean isEnclosed() {
        var level = this.level();
        var pos = this.blockPosition();

        for (var dir : Direction.values()) {
            if (!level.getBlockState(pos.relative(dir)).isSolidRender(level, pos.relative(dir))) {
                return false;
            }
        }

        var above = pos.above();
        for (var dx : new int[] { -1, 1 }) {
            for (var dz : new int[] { -1, 1 }) {
                var diag = above.offset(dx, 0, dz);
                if (!level.getBlockState(diag).isSolidRender(level, diag)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Returns how many consecutive air (or non-solid) blocks exist directly above this egg, up to a cap of 3.
     */
    public int getOpenSpaceAbove() {
        var level = this.level();
        var pos = this.blockPosition();
        var count = 0;
        for (var i = 1; i <= 3; i++) {
            var check = pos.above(i);
            if (level.getBlockState(check).isSolidRender(level, check))
                break;
            count++;
        }
        return count;
    }

    private void playAnimation(ClientAnimState next) {
        if (currentClientAnim == next) {
            return;
        }

        currentClientAnim = next;

        switch (next) {
            case IDLE -> animationDispatcher.clientIdle();
            case HATCHING -> animationDispatcher.clientHatching();
            case HATCHED -> animationDispatcher.clientHatched();
        }
    }

    public void updateAnimations() {
        if (this.isDeadOrDying() || this.getHealth() <= 0 || this.getEggState() == EggStates.HATCHED.ordinal()) {
            playAnimation(ClientAnimState.HATCHED);
            return;
        }

        if (this.getEggState() == EggStates.HATCHING.ordinal()) {
            playAnimation(ClientAnimState.HATCHING);
            return;
        }

        playAnimation(ClientAnimState.IDLE);
    }

    @SuppressWarnings("unused")
    public static boolean canOvomorphSpawn(
        EntityType<OvomorphEntity> type,
        ServerLevelAccessor level,
        MobSpawnType reason,
        BlockPos pos,
        RandomSource random
    ) {
        if (level.getDifficulty() == Difficulty.PEACEFUL) {
            return false;
        }

        if (level.getMaxLocalRawBrightness(pos) > 7) {
            return false;
        }

        if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) {
            return false;
        }

        if (!level.getEntitiesOfClass(OvomorphEntity.class, new AABB(pos).inflate(10)).isEmpty()) {
            return false;
        }

        return OvomorphStructureChecks.isInTargetStructure(level, pos);
    }
}
