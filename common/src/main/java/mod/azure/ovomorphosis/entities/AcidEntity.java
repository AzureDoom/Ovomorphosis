package mod.azure.ovomorphosis.entities;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import mod.azure.ovomorphosis.util.MobUtils;

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
            MobUtils.applyParticles(random, this);
            return;
        }
        if (age == 1) {
            moveTo(blockPosition().offset(0, 0, 0), getYRot(), getXRot());
        }
        MobUtils.applyCustomGravity(this);
        MobUtils.applyBlockBreaking(age, this);
        MobUtils.applyContactEffects(age, random, this);
        MobUtils.applySounds(age, random, this);

        if (
            age >= random.nextIntBetweenInclusive(400, 800) || level().getBlockState(blockPosition())
                .is(Blocks.LAVA)
        ) {
            kill();
        }
    }
}
