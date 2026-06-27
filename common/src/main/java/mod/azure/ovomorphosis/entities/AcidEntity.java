package mod.azure.ovomorphosis.entities;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
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
            this.applyParticles();
            return;
        }
        if (age == 1) {
            moveTo(blockPosition().offset(0, 0, 0), getYRot(), getXRot());
        }
        this.applyCustomGravity();
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

    private void applyCustomGravity() {
        if (!this.isNoGravity()) {
            this.setDeltaMovement(this.getDeltaMovement().add(0.0D, -0.04D, 0.0D));
        }
        move(MoverType.SELF, getDeltaMovement());
        setDeltaMovement(getDeltaMovement().scale(0.38));
    }
}
