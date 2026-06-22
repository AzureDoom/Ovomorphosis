package mod.azure.ovomorphosis.entities;

import mod.azure.azurelib.common.util.MoveAnalysis;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

import mod.azure.ovomorphosis.ai.util.WallCrawlingMob;
import mod.azure.ovomorphosis.registry.EntityRegistry;
import mod.azure.ovomorphosis.util.ClientAnimState;
import mod.azure.ovomorphosis.util.ModTags;

public class AbstractAlienEntity extends PathfinderMob implements WallCrawlingMob {

    public MoveAnalysis moveAnalysis;

    private static final EntityDataAccessor<Boolean> DATA_WALL_CRAWLING =
        SynchedEntityData.defineId(AbstractAlienEntity.class, EntityDataSerializers.BOOLEAN);

    private static final EntityDataAccessor<Float> DATA_CRAWL_FORWARD_X =
        SynchedEntityData.defineId(AbstractAlienEntity.class, EntityDataSerializers.FLOAT);

    private static final EntityDataAccessor<Float> DATA_CRAWL_FORWARD_Y =
        SynchedEntityData.defineId(AbstractAlienEntity.class, EntityDataSerializers.FLOAT);

    private static final EntityDataAccessor<Float> DATA_CRAWL_FORWARD_Z =
        SynchedEntityData.defineId(AbstractAlienEntity.class, EntityDataSerializers.FLOAT);

    private static final EntityDataAccessor<Float> DATA_CRAWL_UP_X =
        SynchedEntityData.defineId(AbstractAlienEntity.class, EntityDataSerializers.FLOAT);

    private static final EntityDataAccessor<Float> DATA_CRAWL_UP_Y =
        SynchedEntityData.defineId(AbstractAlienEntity.class, EntityDataSerializers.FLOAT);

    private static final EntityDataAccessor<Float> DATA_CRAWL_UP_Z =
        SynchedEntityData.defineId(AbstractAlienEntity.class, EntityDataSerializers.FLOAT);

    private static final EntityDataAccessor<Float> DATA_CRAWL_DIST_FROM_BLOCK =
        SynchedEntityData.defineId(AbstractAlienEntity.class, EntityDataSerializers.FLOAT);

    private int wallCrawlGraceTicks;

    private Vec3 oldCrawlForward = new Vec3(0.0D, 0.0D, 1.0D);

    private Vec3 oldCrawlUp = new Vec3(0.0D, 1.0D, 0.0D);

    private double oldCrawlDistFromBlock;

    protected ClientAnimState currentClientAnim = null;

    protected int lookCooldown = 0;

    protected int lookTicks = 0;

    protected int lastAnimationTick = -1;

    public AbstractAlienEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    public float maxUpStep() {
        if (ovomorphosis$isWallCrawling()) {
            return 0.6F;
        }
        return 1.25F;
    }

    @Override
    public void travel(@NotNull Vec3 vec3) {
        if (this.tickCount % 10 == 0)
            this.refreshDimensions();
        super.travel(vec3);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.@NotNull Builder builder) {
        super.defineSynchedData(builder);

        builder.define(DATA_WALL_CRAWLING, false);

        builder.define(DATA_CRAWL_FORWARD_X, 0.0F);
        builder.define(DATA_CRAWL_FORWARD_Y, 0.0F);
        builder.define(DATA_CRAWL_FORWARD_Z, 1.0F);

        builder.define(DATA_CRAWL_UP_X, 0.0F);
        builder.define(DATA_CRAWL_UP_Y, 1.0F);
        builder.define(DATA_CRAWL_UP_Z, 0.0F);

        builder.define(DATA_CRAWL_DIST_FROM_BLOCK, 0.0F);
    }

    @Override
    public boolean ovomorphosis$isWallCrawling() {
        return this.entityData.get(DATA_WALL_CRAWLING);
    }

    @Override
    public void ovomorphosis$setWallCrawling(boolean crawling) {
        this.entityData.set(DATA_WALL_CRAWLING, crawling);

        if (crawling) {
            this.wallCrawlGraceTicks = 4;
        }
    }

    @Override
    public int ovomorphosis$getWallCrawlGraceTicks() {
        return wallCrawlGraceTicks;
    }

    @Override
    public void ovomorphosis$setWallCrawlGraceTicks(int ticks) {
        this.wallCrawlGraceTicks = ticks;
    }

    @Override
    public Vec3 ovomorphosis$getCrawlForward() {
        return new Vec3(
            this.entityData.get(DATA_CRAWL_FORWARD_X),
            this.entityData.get(DATA_CRAWL_FORWARD_Y),
            this.entityData.get(DATA_CRAWL_FORWARD_Z)
        );
    }

    @Override
    public Vec3 ovomorphosis$getOldCrawlForward() {
        return oldCrawlForward;
    }

    @Override
    public Vec3 ovomorphosis$getCrawlUp() {
        return new Vec3(
            this.entityData.get(DATA_CRAWL_UP_X),
            this.entityData.get(DATA_CRAWL_UP_Y),
            this.entityData.get(DATA_CRAWL_UP_Z)
        );
    }

    @Override
    public Vec3 ovomorphosis$getOldCrawlUp() {
        return oldCrawlUp;
    }

    @Override
    public double ovomorphosis$getCrawlDistFromBlock() {
        return this.entityData.get(DATA_CRAWL_DIST_FROM_BLOCK);
    }

    @Override
    public double ovomorphosis$getOldCrawlDistFromBlock() {
        return oldCrawlDistFromBlock;
    }

    @Override
    public void ovomorphosis$setCrawlOrientation(Vec3 forward, Vec3 up, double distFromBlock) {
        if (forward.lengthSqr() > 0.0001D) {
            var normalizedForward = forward.normalize();

            this.entityData.set(DATA_CRAWL_FORWARD_X, (float) normalizedForward.x);
            this.entityData.set(DATA_CRAWL_FORWARD_Y, (float) normalizedForward.y);
            this.entityData.set(DATA_CRAWL_FORWARD_Z, (float) normalizedForward.z);
        }

        if (up.lengthSqr() > 0.0001D) {
            var normalizedUp = up.normalize();

            this.entityData.set(DATA_CRAWL_UP_X, (float) normalizedUp.x);
            this.entityData.set(DATA_CRAWL_UP_Y, (float) normalizedUp.y);
            this.entityData.set(DATA_CRAWL_UP_Z, (float) normalizedUp.z);
        }

        this.entityData.set(DATA_CRAWL_DIST_FROM_BLOCK, (float) distFromBlock);
    }

    private void syncOldCrawlRenderState() {
        this.oldCrawlForward = this.ovomorphosis$getCrawlForward();
        this.oldCrawlUp = this.ovomorphosis$getCrawlUp();
        this.oldCrawlDistFromBlock = this.ovomorphosis$getCrawlDistFromBlock();
    }

    @Override
    public void tick() {
        super.tick();
        this.setAirSupply(this.getMaxAirSupply());

        syncOldCrawlRenderState();

        if (!this.level().isClientSide()) {
            if (this.isOnFire() && this.tickCount % 2 == 0) {
                this.spawnFireParticles(this, (ServerLevel) this.level());
            }
        }

        if (moveAnalysis != null)
            moveAnalysis.update();

        if (this.isNoAi()) {
            var yaw = 90.0f;
            this.setYRot(yaw);
            this.yRotO = yaw;
            this.yBodyRot = yaw;
            this.yBodyRotO = yaw;
        }
        if (this.tickCount % 10 == 0) {
            this.refreshDimensions();
        }

        if (!this.level().isClientSide()) {
            this.getActiveEffects()
                .stream()
                .map(MobEffectInstance::getEffect)
                .filter(effect -> effect.is(ModTags.REMOVABLE_EFFECTS))
                .toList()
                .forEach(this::removeEffect);
        }
    }

    @Override
    public boolean displayFireAnimation() {
        return false;
    }

    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, @NotNull DamageSource source) {
        if (fallDistance <= 12.0F) {
            return false;
        }
        return super.causeFallDamage(fallDistance, multiplier, source);
    }

    @Override
    protected void tickDeath() {
        ++this.deathTime;
        if (this.deathTime >= 40 && !this.level().isClientSide() && !this.isRemoved()) {
            this.level().broadcastEntityEvent(this, (byte) 60);
            this.dropExperience(this);
            this.remove(RemovalReason.KILLED);
        }
    }

    @Override
    public void die(@NotNull DamageSource source) {
        spawnAcid(source);
        super.die(source);
    }

    @Override
    public boolean hurt(@NotNull DamageSource source, float amount) {
        if (isAlive() && amount > 4F) {
            spawnAcid(source);
        }
        return super.hurt(source, amount);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    public void spawnAcid(DamageSource source) {
        if (level().isClientSide()) {
            return;
        }

        var sources = damageSources();

        if (
            Set.of(
                sources.genericKill(),
                sources.generic(),
                sources.onFire(),
                sources.magic(),
                sources.fall()
            ).contains(source)
        ) {
            return;
        }

        var acidEntity = new AcidEntity(EntityRegistry.ACID.get(), level());
        acidEntity.moveTo(this.getX(), this.getY(), this.getZ(), this.getYRot(), 0);
        this.level().addFreshEntity(acidEntity);
    }

    public void spawnFireParticles(LivingEntity livingEntity, ServerLevel level) {
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
}
