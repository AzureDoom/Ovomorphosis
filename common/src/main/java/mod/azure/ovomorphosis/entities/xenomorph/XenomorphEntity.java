package mod.azure.ovomorphosis.entities.xenomorph;

import com.azure.azurecortex.api.blackboard.CommonBlackboardKeys;
import com.azure.azurecortex.goap.EmergencyDetector;
import com.azure.azurecortex.goap.GoalExecutor;
import com.azure.azurecortex.goap.GoalFailureCooldowns;
import com.azure.azurecortex.goap.PlannedGoal;
import com.azure.azurecortex.navigation.crawl.CrawlController;
import com.azure.azurecortex.runtime.CortexRuntime;
import com.azure.azurecortex.sensing.TargetSensor;
import mod.azure.azurelib.util.MoveAnalysis;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.*;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.BiConsumer;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.goap.*;
import mod.azure.ovomorphosis.ai.util.*;
import mod.azure.ovomorphosis.data.OvomorphosisSavedData;
import mod.azure.ovomorphosis.entities.AbstractAlienEntity;
import mod.azure.ovomorphosis.registry.SoundRegistry;
import mod.azure.ovomorphosis.util.ClientAnimState;
import mod.azure.ovomorphosis.util.Growable;

public class XenomorphEntity extends AbstractAlienEntity implements Growable {

    @Nullable
    private UUID hiveId;

    protected static final EntityDataAccessor<Float> GROWTH = SynchedEntityData.defineId(
        XenomorphEntity.class,
        EntityDataSerializers.FLOAT
    );

    protected static final EntityDataAccessor<Boolean> IS_EXECUTION = SynchedEntityData.defineId(
        XenomorphEntity.class,
        EntityDataSerializers.BOOLEAN
    );

    private final XenomorphHostileTargetSelector<XenomorphEntity> targetSelector;

    private final DynamicGameEventListener<GameEventListener> dynamicGameEventListener;

    private final CortexRuntime<XenomorphEntity, AiGoalType> brainRuntime;

    private final XenomorphGoalPlanner goalPlanner = new XenomorphGoalPlanner();

    public final XenomorphAnimationDispatcher animationDispatcher;

    private float lastGrowthScale = 1.0F;

    public XenomorphEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        this.animationDispatcher = new XenomorphAnimationDispatcher(this);
        this.moveAnalysis = new MoveAnalysis(this);
        this.targetSelector = new XenomorphHostileTargetSelector<>(
            CommonMod.getConfig().entityConfigs.xenomorphConfigs.xenoHostileRange
        );

        var positionSource = new EntityPositionSource(this, this.getEyeHeight());
        this.dynamicGameEventListener = new DynamicGameEventListener<>(new GameEventListener() {

            @Override
            public @NotNull PositionSource getListenerSource() {
                return positionSource;
            }

            @Override
            public int getListenerRadius() {
                return 32;
            }

            @Override
            public boolean handleGameEvent(
                @NotNull ServerLevel serverLevel,
                @NotNull GameEvent gameEvent,
                GameEvent.@NotNull Context context,
                @NotNull Vec3 pos
            ) {
                return XenomorphEntity.this.onGameEvent(gameEvent, context, pos);
            }
        });

        this.brainRuntime = new CortexRuntime<>(
            this,
            new TargetSensor<>(targetSelector, 10, TargetSensor.lineOfSight()),
            XenomorphTree.create()
        );
    }

    public static AttributeSupplier.Builder createAttributes() {
        return LivingEntity.createLivingAttributes()
            .add(Attributes.MAX_HEALTH, CommonMod.getConfig().entityConfigs.xenomorphConfigs.xenoHealth)
            .add(
                Attributes.ARMOR,
                CommonMod.getConfig().entityConfigs.xenomorphConfigs.xenoArmor
            )
            .add(Attributes.ARMOR_TOUGHNESS, CommonMod.getConfig().entityConfigs.xenomorphConfigs.xenoArmorToughness)
            .add(
                Attributes.KNOCKBACK_RESISTANCE,
                CommonMod.getConfig().entityConfigs.xenomorphConfigs.xenoKnockbackRes
            )
            .add(Attributes.FOLLOW_RANGE, CommonMod.getConfig().entityConfigs.xenomorphConfigs.xenoHostileRange)
            .add(Attributes.MOVEMENT_SPEED, 0.25)
            .add(Attributes.ATTACK_DAMAGE, CommonMod.getConfig().entityConfigs.xenomorphConfigs.xenoAttackDamage)
            .add(Attributes.ATTACK_KNOCKBACK, 1.0);
    }

    private boolean onGameEvent(GameEvent gameEvent, GameEvent.Context context, Vec3 pos) {
        if (this.isDeadOrDying()) {
            return false;
        }

        if (!isSignificantEvent(gameEvent)) {
            return false;
        }

        var source = context.sourceEntity();

        if (source instanceof AbstractAlienEntity) {
            return false;
        }

        if (source instanceof Player player && (player.isCreative() || player.isSpectator())) {
            return false;
        }

        var investigatePos = source != null ? source.position() : pos;
        targetSelector.hearSound(investigatePos);
        return true;
    }

    private static boolean isSignificantEvent(GameEvent event) {
        return event == GameEvent.STEP
            || event == GameEvent.HIT_GROUND
            || event == GameEvent.SWIM
            || event == GameEvent.SPLASH
            || event == GameEvent.ELYTRA_GLIDE
            || event == GameEvent.FLAP
            || event == GameEvent.ENTITY_INTERACT
            || event == GameEvent.ENTITY_DAMAGE
            || event == GameEvent.ENTITY_DIE
            || event == GameEvent.PROJECTILE_SHOOT
            || event == GameEvent.PROJECTILE_LAND
            || event == GameEvent.EXPLODE
            || event == GameEvent.EAT
            || event == GameEvent.DRINK;
    }

    @Override
    public void updateDynamicGameEventListener(
        @NotNull BiConsumer<DynamicGameEventListener<?>, ServerLevel> listenerConsumer
    ) {
        if (this.level() instanceof ServerLevel serverLevel) {
            listenerConsumer.accept(this.dynamicGameEventListener, serverLevel);
        }
    }

    @Override
    public boolean killedEntity(@NotNull ServerLevel level, @NotNull LivingEntity entity) {
        targetSelector.onTargetKilled();
        return super.killedEntity(level, entity);
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide()) {
            if (this.level() instanceof ServerLevel serverLevel) {
                var hiveMemory = brainRuntime.getBlackboard().get(AiKeys.HIVE_MEMORY);

                if (hiveMemory == null) {
                    ensureHiveAssignment(serverLevel);
                }
            }
            if (!this.isNoAi()) {
                tickGoalPlanner();
                brainRuntime.tick();
                CrawlController.updateWallCrawlingPhysics(this);
            }
            if (this.isAlive()) {
                grow(this, 1);
            }
        }
    }

    public void updateScaleFromGrowth() {
        var newScale = this.getGrowthScale();

        if (Math.abs(this.lastGrowthScale - newScale) > 0.001F) {
            this.lastGrowthScale = newScale;
            this.refreshDimensions();
        }
    }

    public float getGrowthScale() {
        return Mth.clamp(
            0.5F + ((this.getGrowth() / this.getMaxGrowth()) * 0.5F),
            0.5F,
            1.0F
        );
    }

    private void tickGoalPlanner() {
        var blackboard = brainRuntime.getBlackboard();
        var cooldowns = brainRuntime.getCooldowns();

        int currentTick = (int) this.level().getGameTime();

        @SuppressWarnings("unchecked")
        var activeGoal = (PlannedGoal<XenomorphEntity, AiGoalType>) blackboard.get(CommonBlackboardKeys.ACTIVE_GOAL);

        var goalType = activeGoal != null ? activeGoal.type() : AiGoalType.NONE;
        var isPassive = goalType == AiGoalType.WANDER
            || goalType == AiGoalType.NONE
            || goalType == AiGoalType.EXPAND_HIVE
            || goalType == AiGoalType.KILL_LIGHTS;

        var huntSuppressed = GoalFailureCooldowns.getOrCreate(blackboard)
            .isSuppressed(AiGoalType.HUNT_TARGET, currentTick);
        var reactiveReplan = isPassive && blackboard.has(CommonBlackboardKeys.TARGET) && !huntSuppressed;

        var preplanUrgency = EmergencyDetector.detectPreplanUrgency(this, EmergencyDetector.defaultProbes());

        if (!reactiveReplan && preplanUrgency == null && cooldowns.isOnCooldown(CommonBlackboardKeys.GOAL_REPLAN))
            return;

        if (!reactiveReplan && !GoalExecutor.shouldReplan(blackboard, currentTick, preplanUrgency, this))
            return;

        cooldowns.set(CommonBlackboardKeys.GOAL_REPLAN, 20);
        var newGoal = goalPlanner.chooseGoal(this, blackboard, cooldowns);

        GoalExecutor.apply(this, blackboard, newGoal);
    }

    @Override
    public @NotNull SoundEvent getDeathSound() {
        return SoundRegistry.XENOMORPH_DEATH.get();
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundRegistry.XENOMORPH_IDLE.get();
    }

    @Override
    protected @NotNull SoundEvent getHurtSound(@NotNull DamageSource damageSourceIn) {
        return SoundRegistry.XENOMORPH_HURT.get();
    }

    @Override
    protected void playStepSound(@NotNull BlockPos pos, @NotNull BlockState blockIn) {}

    @Override
    public @Nullable SpawnGroupData finalizeSpawn(
        @NotNull ServerLevelAccessor level,
        @NotNull DifficultyInstance difficulty,
        @NotNull MobSpawnType spawnType,
        @Nullable SpawnGroupData spawnData,
        @Nullable CompoundTag dataTag
    ) {
        if (spawnType == MobSpawnType.SPAWN_EGG || spawnType == MobSpawnType.COMMAND)
            setGrowth(1200);

        if (level instanceof ServerLevel serverLevel) {
            ensureHiveAssignment(serverLevel);
        }

        return super.finalizeSpawn(level, difficulty, spawnType, spawnData, dataTag);
    }

    @Override
    public float getGrowth() {
        return entityData.get(GROWTH);
    }

    @Override
    public void setGrowth(float growth) {
        entityData.set(GROWTH, growth);
        this.updateScaleFromGrowth();
    }

    @Override
    public float getMaxGrowth() {
        return 1200;
    }

    @Override
    public LivingEntity growInto() {
        return null;
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(GROWTH, 0.0F);
        this.entityData.define(IS_EXECUTION, false);
    }

    @Override
    public void addAdditionalSaveData(@NotNull CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("growth", getGrowth());
        tag.putBoolean("isExecuting", this.isExecuting());
    }

    @Override
    public void readAdditionalSaveData(@NotNull CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.setGrowth(tag.getFloat("growth"));
        this.setIsExecuting(tag.getBoolean("isExecuting"));

        if (tag.hasUUID("HiveId")) {
            this.hiveId = tag.getUUID("HiveId");
        } else {
            this.hiveId = null;
        }

        if (this.level() instanceof ServerLevel serverLevel) {
            ensureHiveAssignment(serverLevel);
        }
    }

    @Override
    protected boolean canRide(@NotNull Entity vehicle) {
        return false;
    }

    @Override
    public void positionRider(@NotNull Entity entity, @NotNull MoveFunction moveFunction) {
        if (!(entity instanceof LivingEntity mob)) {
            return;
        }

        if (this.tickCount % 20 == 0) {
            mob.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 100, true, true));
            mob.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 40, 1, true, true));
        }

        var radians = this.yBodyRot * ((float) Math.PI / 180.0F);
        var sin = Mth.sin(radians);
        var cos = Mth.cos(radians);

        var executing = this.isExecuting();
        var smallMob = mob.getBbHeight() < 1.4F;

        var xzOffset = -0.95F;

        var yOffset = executing
            ? smallMob ? 1.145F : 0.745F
            : smallMob ? 0.445F : 0.145F;

        mob.setPos(
            this.getX() + (xzOffset * sin),
            this.getY() + yOffset,
            this.getZ() - (xzOffset * cos)
        );

        mob.yBodyRot = this.yBodyRot;
        mob.setSpeed(0);
    }

    @Override
    public @NotNull EntityDimensions getDimensions(@NotNull Pose pose) {
        var growthScale = this.getGrowthScale();

        if (CrawlController.isWallCrawling(this)) {
            return EntityDimensions.scalable(0.6F * growthScale, 0.6F * growthScale);
        }

        var base = super.getDimensions(pose);

        return EntityDimensions.scalable(
            base.width * growthScale,
            base.height * growthScale
        );
    }

    @Override
    public boolean doHurtTarget(@NotNull Entity target) {
        if (
            CommonMod.getConfig().entityConfigs.xenomorphConfigs.enableXenomorphItemSlap &&
                target instanceof LivingEntity livingEntity
                && !this.level().isClientSide
                && this.getRandom().nextInt(100) < 5
        ) {
            disarmTarget(livingEntity);
        }

        this.heal(1.0833F);
        return super.doHurtTarget(target);
    }

    private void bindToHive(HiveMemory hive) {
        this.hiveId = hive.getHiveId();

        brainRuntime.getBlackboard()
            .set(
                AiKeys.HIVE_MEMORY,
                hive
            );
    }

    private void ensureHiveAssignment(ServerLevel level) {
        if (hiveId != null) {
            var existing =
                OvomorphosisSavedData.findHiveById(
                    level,
                    hiveId
                );

            if (existing != null) {
                bindToHive(existing);
                return;
            }
        }

        var hive =
            OvomorphosisSavedData.getOrCreateHive(
                level,
                blockPosition()
            );

        bindToHive(hive);
    }

    private void disarmTarget(LivingEntity livingEntity) {
        if (livingEntity instanceof Player player) {
            var selectedItem = player.getInventory().getSelected();

            if (!selectedItem.isEmpty()) {
                player.drop(selectedItem, false);
                player.getInventory().setItem(player.getInventory().selected, ItemStack.EMPTY);
            }
        } else if (livingEntity instanceof Mob mob) {
            var mainHandItem = mob.getMainHandItem();

            if (!mainHandItem.isEmpty()) {
                this.drop(mob, mainHandItem);
                mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            }
        }

        livingEntity.playSound(SoundEvents.ITEM_FRAME_REMOVE_ITEM, 1.0F, 1.0F);
    }

    public void drop(LivingEntity target, ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return;
        }

        var level = target.level();

        var spawnX = target.getX();
        var spawnY = target.getEyeY() - 0.3D;
        var spawnZ = target.getZ();

        var droppedItem = new ItemEntity(level, spawnX, spawnY, spawnZ, itemStack);
        droppedItem.setPickUpDelay(40);

        var pitchRadians = this.getXRot() * ((float) Math.PI / 180F);
        var yawRadians = this.getYRot() * ((float) Math.PI / 180F);

        var pitchSin = Mth.sin(pitchRadians);
        var pitchCos = Mth.cos(pitchRadians);
        var yawSin = Mth.sin(yawRadians);
        var yawCos = Mth.cos(yawRadians);

        var randomAngle = this.random.nextFloat() * ((float) Math.PI * 2F);
        var randomSpread = 0.02F * this.random.nextFloat();

        var velocityX = (-yawSin * pitchCos * 0.3F) + Math.cos(randomAngle) * randomSpread;
        var velocityY = (-pitchSin * 0.3F) + 0.01F;
        var velocityZ = (yawCos * pitchCos * 0.3F) + Math.sin(randomAngle) * randomSpread;

        droppedItem.setDeltaMovement(velocityX, velocityY, velocityZ);

        level.addFreshEntity(droppedItem);
    }

    public boolean isExecuting() {
        return entityData.get(IS_EXECUTION);
    }

    public void setIsExecuting(boolean isExecuting) {
        entityData.set(IS_EXECUTION, isExecuting);
    }

    private void playAnimation(ClientAnimState next) {
        if (currentClientAnim == next) {
            return;
        }

        currentClientAnim = next;

        switch (next) {
            case DEATH -> animationDispatcher.clientDeath();
            case WALK -> animationDispatcher.clientWalk();
            case RUN -> animationDispatcher.clientRun();
            case IDLE -> animationDispatcher.clientIdle();
            case LOOK -> animationDispatcher.clientIdle2();
            case CRAWLING -> animationDispatcher.clientCrawling();
            case CARRYING -> animationDispatcher.serverCarry();
            case SWIMMING -> animationDispatcher.clientSwim();
        }
    }

    public void updateAnimations() {
        var newEntityTick = this.tickCount != lastAnimationTick;

        if (newEntityTick) {
            lastAnimationTick = this.tickCount;
        }

        if (this.isDeadOrDying() || this.getHealth() <= 0) {
            playAnimation(ClientAnimState.DEATH);
            return;
        }

        if (this.isInWater()) {
            playAnimation(ClientAnimState.SWIMMING);
            return;
        }

        if (moveAnalysis.isMoving()) {
            if (newEntityTick) {
                lookTicks = 0;
            }

            if (CrawlController.isWallCrawling(this)) {
                playAnimation(ClientAnimState.CRAWLING);
            } else if (this.isAggressive() && !this.swinging) {
                playAnimation(ClientAnimState.RUN);
            } else {
                playAnimation(ClientAnimState.WALK);
            }

            return;
        }

        if (lookTicks > 0) {
            if (newEntityTick) {
                lookTicks--;
            }

            playAnimation(ClientAnimState.LOOK);
            return;
        }

        if (lookCooldown > 0) {
            if (newEntityTick) {
                lookCooldown--;
            }

            playAnimation(ClientAnimState.IDLE);
            return;
        }

        if (this.getRandom().nextDouble() < 0.15) {
            lookTicks = 80;
            lookCooldown = 120;
            playAnimation(ClientAnimState.LOOK);
            return;
        }

        lookCooldown = 120;
        playAnimation(ClientAnimState.IDLE);
    }
}
