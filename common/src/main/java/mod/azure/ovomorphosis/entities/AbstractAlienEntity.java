package mod.azure.ovomorphosis.entities;

import mod.azure.azurelib.common.util.MoveAnalysis;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import mod.azure.ovomorphosis.ai.actions.FleeFireAction;
import mod.azure.ovomorphosis.ai.util.CrawlingManager;
import mod.azure.ovomorphosis.ai.util.WallCrawlingMob;
import mod.azure.ovomorphosis.util.ClientAnimState;
import mod.azure.ovomorphosis.util.MobUtils;
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

    protected static final EntityDataAccessor<Float> FIRE_TOLERANCE_NBT =
        SynchedEntityData.defineId(AbstractAlienEntity.class, EntityDataSerializers.FLOAT);

    protected final CrawlingManager crawlingManager = new CrawlingManager(
        this,
        DATA_WALL_CRAWLING,
        DATA_CRAWL_FORWARD_X,
        DATA_CRAWL_FORWARD_Y,
        DATA_CRAWL_FORWARD_Z,
        DATA_CRAWL_UP_X,
        DATA_CRAWL_UP_Y,
        DATA_CRAWL_UP_Z,
        DATA_CRAWL_DIST_FROM_BLOCK
    );

    protected ClientAnimState currentClientAnim = null;

    protected int lookCooldown = 0;

    protected int lookTicks = 0;

    protected int lastAnimationTick = -1;

    public AbstractAlienEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    public float maxUpStep() {
        return 1.25F;
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
        builder.define(FIRE_TOLERANCE_NBT, 0.0F);
    }

    @Override
    public void addAdditionalSaveData(@NotNull CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("fireToleranceNbt", getFireToleranceNbt());
    }

    @Override
    public void readAdditionalSaveData(@NotNull CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.setFireToleranceNbt(tag.getFloat("fireToleranceNbt"));
    }

    @SuppressWarnings("ConstantValue")
    @Override
    public boolean ovomorphosis$isWallCrawling() {
        return crawlingManager != null && crawlingManager.isWallCrawling();
    }

    @Override
    public void ovomorphosis$setWallCrawling(boolean crawling) {
        crawlingManager.setWallCrawling(crawling);
    }

    @Override
    public int ovomorphosis$getWallCrawlGraceTicks() {
        return crawlingManager.getWallCrawlGraceTicks();
    }

    @Override
    public void ovomorphosis$setWallCrawlGraceTicks(int ticks) {
        crawlingManager.setWallCrawlGraceTicks(ticks);
    }

    @Override
    public Vec3 ovomorphosis$getCrawlForward() {
        return crawlingManager.getCrawlForward();
    }

    @Override
    public Vec3 ovomorphosis$getOldCrawlForward() {
        return crawlingManager.getOldCrawlForward();
    }

    @Override
    public Vec3 ovomorphosis$getCrawlUp() {
        return crawlingManager.getCrawlUp();
    }

    @Override
    public Vec3 ovomorphosis$getOldCrawlUp() {
        return crawlingManager.getOldCrawlUp();
    }

    @Override
    public double ovomorphosis$getCrawlDistFromBlock() {
        return crawlingManager.getCrawlDistFromBlock();
    }

    @Override
    public double ovomorphosis$getOldCrawlDistFromBlock() {
        return crawlingManager.getOldCrawlDistFromBlock();
    }

    @Override
    public void ovomorphosis$setCrawlOrientation(Vec3 forward, Vec3 up, double distFromBlock) {
        crawlingManager.setCrawlOrientation(forward, up, distFromBlock);
    }

    @Override
    public void tick() {
        super.tick();
        this.setAirSupply(this.getMaxAirSupply());

        crawlingManager.tick();

        if (isInWater()) {
            setSwimming(true);
        }

        if (!this.level().isClientSide()) {
            if (this.isOnFire() && this.tickCount % 2 == 0) {
                MobUtils.spawnFireParticles(this, (ServerLevel) this.level());
            }
            this.getActiveEffects()
                .stream()
                .map(MobEffectInstance::getEffect)
                .filter(effect -> effect.is(ModTags.REMOVABLE_EFFECTS))
                .toList()
                .forEach(this::removeEffect);
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
        MobUtils.spawnAcid(damageSources(), source, this);
        super.die(source);
    }

    @Override
    public boolean hurt(@NotNull DamageSource source, float amount) {
        if (isAlive() && amount > 4F) {
            MobUtils.spawnAcid(damageSources(), source, this);
        }
        return super.hurt(source, amount);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    public float getFireToleranceNbt() {
        return entityData.get(FIRE_TOLERANCE_NBT);
    }

    public void setFireToleranceNbt(float value) {
        entityData.set(FIRE_TOLERANCE_NBT, Math.min(value, FleeFireAction.MAX_TOLERANCE));
    }

    public boolean isFireHardened() {
        return getFireToleranceNbt() >= FleeFireAction.MAX_TOLERANCE;
    }
}
