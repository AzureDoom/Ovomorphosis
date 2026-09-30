package mod.azure.ovomorphosis.entities;

import com.azure.azurecortex.api.navigation.MovementCapability;
import com.azure.azurecortex.navigation.crawl.CrawlCapability;
import com.azure.azurecortex.navigation.crawl.CrawlController;
import com.azure.azurecortex.navigation.crawl.CrawlState;
import mod.azure.azurelib.common.util.MoveAnalysis;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import mod.azure.ovomorphosis.ai.actions.FleeFireAction;
import mod.azure.ovomorphosis.util.ClientAnimState;
import mod.azure.ovomorphosis.util.MobUtils;
import mod.azure.ovomorphosis.util.ModTags;

public class AbstractAlienEntity extends PathfinderMob implements MovementCapability, CrawlCapability {

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

    protected final CrawlState crawlState = new CrawlState(
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

    private static final int SUFFOCATION_GRACE_TICKS = 10;

    private int suffocationTicks = 0;

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

    @Override
    public boolean isHazardBlock(Level level, BlockPos pos, BlockState state) {
        return state.is(ModTags.DANGER_BLOCKS);
    }

    @Override
    public boolean isPassableSolid(Level level, BlockPos pos, BlockState state) {
        return state.is(ModTags.RESIN);
    }

    @Override
    public boolean isHazardFluid(Level level, BlockPos pos, FluidState fluid) {
        return fluid.is(ModTags.DANGER_FLUIDS);
    }

    @Override
    public boolean isHazardEntityType(EntityType<?> type) {
        return type.is(ModTags.DANGER_ENTITIES);
    }

    @Override
    public boolean isWallCrawling() {
        return crawlState.isWallCrawling();
    }

    @Override
    public void setWallCrawling(boolean crawling) {
        crawlState.setWallCrawling(crawling);
    }

    @Override
    public int getWallCrawlGraceTicks() {
        return crawlState.getWallCrawlGraceTicks();
    }

    @Override
    public void setWallCrawlGraceTicks(int ticks) {
        crawlState.setWallCrawlGraceTicks(ticks);
    }

    @Override
    public Vec3 getCrawlForward() {
        return crawlState.getCrawlForward();
    }

    @Override
    public Vec3 getOldCrawlForward() {
        return crawlState.getOldCrawlForward();
    }

    @Override
    public Vec3 getCrawlUp() {
        return crawlState.getCrawlUp();
    }

    @Override
    public Vec3 getOldCrawlUp() {
        return crawlState.getOldCrawlUp();
    }

    @Override
    public double getCrawlDistFromBlock() {
        return crawlState.getCrawlDistFromBlock();
    }

    @Override
    public double getOldCrawlDistFromBlock() {
        return crawlState.getOldCrawlDistFromBlock();
    }

    @Override
    public void setCrawlOrientation(Vec3 forward, Vec3 up, double distFromBlock) {
        crawlState.setCrawlOrientation(forward, up, distFromBlock);
    }

    @Override
    public void tick() {
        super.tick();
        this.setAirSupply(this.getMaxAirSupply());

        crawlState.tick();
        CrawlController.updateWallCrawlingPhysics(this);

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

            if (canBreakOutOfSuffocation()) {
                tickSuffocationEscape((ServerLevel) this.level());
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
    }

    protected boolean canBreakOutOfSuffocation() {
        return false;
    }

    private void tickSuffocationEscape(ServerLevel level) {
        if (this.isNoAi() || this.noPhysics || !this.isAlive()) {
            suffocationTicks = 0;
            return;
        }

        var suffocating = findSuffocatingBlocks(level);

        if (suffocating.isEmpty()) {
            suffocationTicks = 0;
            return;
        }

        if (++suffocationTicks < SUFFOCATION_GRACE_TICKS) {
            return;
        }

        if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            return;
        }

        for (var pos : suffocating) {
            level.destroyBlock(pos, true, this);
        }

        suffocationTicks = 0;
    }

    private List<BlockPos> findSuffocatingBlocks(ServerLevel level) {
        var result = new ArrayList<BlockPos>();
        var width = this.getBbWidth() * 0.8F;
        var eyeBox = AABB.ofSize(this.getEyePosition(), width, 1.0E-6, width);
        var eyeShape = Shapes.create(eyeBox);

        BlockPos.betweenClosedStream(eyeBox).forEach(mutable -> {
            var state = level.getBlockState(mutable);

            if (state.isAir() || !state.isSuffocating(level, mutable))
                return;

            if (state.is(ModTags.RESIN) || state.is(ModTags.VENT_BLOCKS))
                return;

            if (state.getDestroySpeed(level, mutable) < 0F)
                return;

            var collision = state.getCollisionShape(level, mutable)
                .move(mutable.getX(), mutable.getY(), mutable.getZ());

            if (Shapes.joinIsNotEmpty(collision, eyeShape, BooleanOp.AND)) {
                result.add(mutable.immutable());
            }
        });

        return result;
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
        if (source.is(DamageTypes.IN_WALL)) {
            return false;
        }

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
