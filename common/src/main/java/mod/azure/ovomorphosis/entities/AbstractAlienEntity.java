package mod.azure.ovomorphosis.entities;

import com.azure.azurecortex.api.navigation.MovementCapability;
import com.azure.azurecortex.navigation.crawl.CrawlCapability;
import com.azure.azurecortex.navigation.crawl.CrawlController;
import com.azure.azurecortex.navigation.crawl.CrawlState;
import mod.azure.azurelib.util.MoveAnalysis;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;

import mod.azure.ovomorphosis.ai.actions.FleeFireAction;
import mod.azure.ovomorphosis.services.XenoServices;
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

    private static final int NUDGE_RADIUS = 2;

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
    public void addAdditionalSaveData(@NotNull ValueOutput tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("fireToleranceNbt", getFireToleranceNbt());
    }

    @Override
    protected void readAdditionalSaveData(@NotNull ValueInput tag) {
        super.readAdditionalSaveData(tag);
        this.setFireToleranceNbt(tag.getFloatOr("fireToleranceNbt", getFireToleranceNbt()));
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
    @SuppressWarnings("deprecation")
    public boolean isHazardEntityType(EntityType<?> type) {
        return type.builtInRegistryHolder().is(ModTags.DANGER_ENTITIES);
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

        var scan = scanSuffocation(level);

        if (!scan.stuck()) {
            suffocationTicks = 0;
            return;
        }

        if (++suffocationTicks < SUFFOCATION_GRACE_TICKS) {
            return;
        }

        suffocationTicks = 0;

        if (!scan.breakable().isEmpty() && XenoServices.COMMON_REGISTRY.canEntityGrief(level, this)) {
            for (var pos : scan.breakable()) {
                level.destroyBlock(pos, true, this);
            }
            return;
        }

        nudgeToFreeSpace(level);
    }

    /**
     * @param stuck     whether the mob's head is inside any suffocating block other than resin or vents
     * @param breakable the subset of those blocks that can be broken (excludes unbreakable blocks)
     */
    private record SuffocationScan(
        boolean stuck,
        List<BlockPos> breakable
    ) {}

    private SuffocationScan scanSuffocation(ServerLevel level) {
        var breakable = new ArrayList<BlockPos>();
        var stuck = false;
        var width = this.getBbWidth() * 0.8F;
        var eyeBox = AABB.ofSize(this.getEyePosition(), width, 1.0E-6, width);
        var eyeShape = Shapes.create(eyeBox);

        for (var mutable : (Iterable<BlockPos>) BlockPos.betweenClosedStream(eyeBox)::iterator) {
            var state = level.getBlockState(mutable);

            if (state.isAir() || !state.isSuffocating(level, mutable))
                continue;

            if (state.is(ModTags.RESIN) || state.is(ModTags.VENT_BLOCKS))
                continue;

            var collision = state.getCollisionShape(level, mutable)
                .move(mutable.getX(), mutable.getY(), mutable.getZ());

            if (!Shapes.joinIsNotEmpty(collision, eyeShape, BooleanOp.AND))
                continue;

            stuck = true;

            if (state.getDestroySpeed(level, mutable) >= 0F) {
                breakable.add(mutable.immutable());
            }
        }

        return new SuffocationScan(stuck, breakable);
    }

    /**
     * Moves the mob to the closest block-centered position within {@link #NUDGE_RADIUS} where its full bounding box is
     * free of collisions. Upward positions win ties so mobs don't get pushed down into caves. Does nothing if there is
     * no open space nearby; the next escape attempt will try again.
     *
     * @return whether the mob was moved
     */
    private boolean nudgeToFreeSpace(ServerLevel level) {
        var origin = this.blockPosition();
        var dimensions = this.getDimensions(this.getPose());
        var current = this.position();

        Vec3 best = null;
        var bestScore = Double.MAX_VALUE;

        for (var dx = -NUDGE_RADIUS; dx <= NUDGE_RADIUS; dx++) {
            for (var dy = -NUDGE_RADIUS; dy <= NUDGE_RADIUS; dy++) {
                for (var dz = -NUDGE_RADIUS; dz <= NUDGE_RADIUS; dz++) {
                    var candidate = new Vec3(
                        origin.getX() + dx + 0.5D,
                        origin.getY() + dy,
                        origin.getZ() + dz + 0.5D
                    );

                    var score = candidate.distanceToSqr(current) - dy * 0.01D;
                    if (score >= bestScore)
                        continue;

                    if (!level.noCollision(this, dimensions.makeBoundingBox(candidate)))
                        continue;

                    best = candidate;
                    bestScore = score;
                }
            }
        }

        if (best == null)
            return false;

        this.getNavigation().stop();
        this.setDeltaMovement(Vec3.ZERO);
        this.teleportTo(best.x, best.y, best.z);
        return true;
    }

    @Override
    public boolean displayFireAnimation() {
        return false;
    }

    @Override
    public boolean causeFallDamage(double fallDistance, float multiplier, @NotNull DamageSource source) {
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
            this.remove(RemovalReason.KILLED);
        }
    }

    @Override
    public void die(@NotNull DamageSource source) {
        MobUtils.spawnAcid(damageSources(), source, this);
        super.die(source);
    }

    @Override
    public boolean hurtServer(@NonNull ServerLevel level, DamageSource source, float amount) {
        if (source.is(DamageTypes.IN_WALL)) {
            return false;
        }

        if (isAlive() && amount > 4F) {
            MobUtils.spawnAcid(damageSources(), source, this);
        }
        return super.hurtServer(level, source, amount);
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
