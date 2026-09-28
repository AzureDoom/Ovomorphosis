package mod.azure.ovomorphosis.entities;

import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import mod.azure.ovomorphosis.util.MobUtils;

public class AcidEntity extends Entity {

    public int age = 0;

    public AcidEntity(EntityType<? extends Entity> entityType, Level level) {
        super(entityType, level);
        this.setDeltaMovement(Vec3.ZERO);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.@NotNull Builder builder) {}

    @Override
    protected void readAdditionalSaveData(ValueInput compoundTag) {
        if (compoundTag.getString("aliveTicks").isPresent()) {
            age = compoundTag.getInt("aliveTicks").orElse(0);
        }
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput compoundTag) {
        compoundTag.putInt("aliveTicks", age);
    }

    @Override
    public boolean hurtServer(@NonNull ServerLevel serverLevel, @NonNull DamageSource damageSource, float v) {
        return false;
    }

    @Override
    public boolean dampensVibrations() {
        return true;
    }

    @Override
    protected double getDefaultGravity() {
        return 0.04;
    }

    @Override
    public void tick() {
        super.tick();
        age++;
        if (level().isClientSide()) {
            MobUtils.applyParticles(random, this);
            return;
        } else if (level() instanceof ServerLevel serverLevel) {
            if (age == 1) {
                snapTo(blockPosition().offset(0, 0, 0), getYRot(), getXRot());
            }
            MobUtils.applyCustomGravity(this);
            MobUtils.applyBlockBreaking(age, this);
            MobUtils.applyContactEffects(age, random, this);
            MobUtils.applySounds(age, random, this);

            if (
                age >= random.nextIntBetweenInclusive(400, 800) || level().getBlockState(blockPosition())
                    .is(Blocks.LAVA)
            ) {
                kill(serverLevel);
            }
        }
    }
}
