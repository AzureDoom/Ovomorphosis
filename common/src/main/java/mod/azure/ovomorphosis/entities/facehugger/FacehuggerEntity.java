package mod.azure.ovomorphosis.entities.facehugger;

import mod.azure.azurelib.util.MoveAnalysis;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.MobBrainRuntime;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.goap.GoalApplicator;
import mod.azure.ovomorphosis.ai.goap.GoalFailureCooldowns;
import mod.azure.ovomorphosis.ai.goap.PlannedGoal;
import mod.azure.ovomorphosis.ai.util.CrawlingMovementManager;
import mod.azure.ovomorphosis.ai.util.FacehuggerHostileTargetSelector;
import mod.azure.ovomorphosis.ai.util.TargetingSystem;
import mod.azure.ovomorphosis.entities.AbstractAlienEntity;
import mod.azure.ovomorphosis.registry.SoundRegistry;
import mod.azure.ovomorphosis.util.ClientAnimState;

public class FacehuggerEntity extends AbstractAlienEntity {

    private static final EntityDataAccessor<Boolean> IS_INFERTILE = SynchedEntityData.defineId(
        FacehuggerEntity.class,
        EntityDataSerializers.BOOLEAN
    );

    private final MobBrainRuntime<FacehuggerEntity> brainRuntime;

    public final FacehuggerAnimationDispatcher animationDispatcher;

    private final FacehuggerHostileTargetSelector<FacehuggerEntity> targetSelector;

    private final FacehuggerGoalPlanner goalPlanner = new FacehuggerGoalPlanner();

    private boolean leapJustFailed = false;

    public FacehuggerEntity(EntityType<? extends AbstractAlienEntity> entityType, Level level) {
        super(entityType, level);
        this.animationDispatcher = new FacehuggerAnimationDispatcher(this);
        this.moveAnalysis = new MoveAnalysis(this);
        this.targetSelector = new FacehuggerHostileTargetSelector<>(32);

        this.brainRuntime = new MobBrainRuntime<>(
            this,
            new TargetingSystem<>(targetSelector, 2),
            FacehuggerTree.create()
        );
    }

    @Override
    public void tick() {
        super.tick();
        moveAnalysis.update();

        if (!this.level().isClientSide()) {
            tickGoalPlanner();
            brainRuntime.tick();
            tickLeapRecovery();
            CrawlingMovementManager.updateWallCrawlingPhysics(this);
        }

        this.handleAttachmentToHost();

        if (isInfertile()) {
            this.kill();
        }
        if (this.isAttachedToHost() && !this.isInfertile() && !this.isDeadOrDying()) {
            animationDispatcher.sendFaceHug();
        }
        if (this.isAttachedToHost() && this.isDeadOrDying()) {
            this.unRide();
        }
        if (this.isInfertile() || this.isDeadOrDying()) {
            animationDispatcher.clientDeath();
        }
    }

    /**
     * Runs the GOAP planner and applies any new goal to the blackboard.
     * <p>
     * Respects the active goal's {@link PlannedGoal#canReplan} commitment window, the planner is only asked to choose
     * again once the minimum commit ticks have elapsed or the goal has expired. Emergency replans (e.g. health drop
     * mid-tick) are handled by the score dominating on the next eligible replan window.
     */
    protected void tickGoalPlanner() {
        var blackboard = brainRuntime.getBlackboard();
        var cooldowns = brainRuntime.getCooldowns();

        var currentTick = (int) this.level().getGameTime();

        @SuppressWarnings("unchecked")
        var activeGoal = (PlannedGoal<FacehuggerEntity>) blackboard.get(AiKeys.ACTIVE_GOAL, PlannedGoal.class);

        var goalType = activeGoal != null ? activeGoal.type() : AiGoalType.NONE;
        var isPassive = goalType == AiGoalType.WANDER || goalType == AiGoalType.NONE;

        var infectSuppressed = GoalFailureCooldowns.getOrCreate(blackboard)
            .isSuppressed(AiGoalType.INFECT_HOST, currentTick);
        var reactiveReplan = isPassive && blackboard.has(AiKeys.TARGET) && !infectSuppressed;

        if (!reactiveReplan && cooldowns.isOnCooldown(AiKeys.GOAL_REPLAN))
            return;

        if (!reactiveReplan && !GoalApplicator.shouldReplan(blackboard, currentTick))
            return;

        cooldowns.set(AiKeys.GOAL_REPLAN, 20);
        var newGoal = goalPlanner.chooseGoal(this, blackboard, cooldowns);
        GoalApplicator.apply(this, blackboard, newGoal);
    }

    private void tickLeapRecovery() {
        var blackboard = brainRuntime.getBlackboard();
        var cooldowns = brainRuntime.getCooldowns();

        @SuppressWarnings("unchecked")
        var activeGoal = (PlannedGoal<FacehuggerEntity>) blackboard.get(AiKeys.ACTIVE_GOAL, PlannedGoal.class);
        if (activeGoal == null) {
            leapJustFailed = false;
            return;
        }

        var goalType = activeGoal.type();
        if (goalType != AiGoalType.INFECT_HOST && goalType != AiGoalType.STALK_HOST) {
            leapJustFailed = false;
            return;
        }

        if (brainRuntime.getCurrentAction() == null && blackboard.has(AiKeys.TARGET)) {
            if (leapJustFailed) {
                leapJustFailed = false;
                cooldowns.set(AiKeys.GOAL_REPLAN, 0);
                var newGoal = goalPlanner.chooseGoal(this, blackboard, cooldowns);
                GoalApplicator.apply(this, blackboard, newGoal);
            } else {
                leapJustFailed = true;
            }
        } else {
            leapJustFailed = false;
        }
    }

    @Override
    public boolean killedEntity(@NotNull ServerLevel level, @NotNull LivingEntity entity) {
        targetSelector.onTargetKilled();
        return super.killedEntity(level, entity);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();

        this.entityData.define(IS_INFERTILE, false);
    }

    @Override
    public void addAdditionalSaveData(@NotNull CompoundTag nbt) {
        super.addAdditionalSaveData(nbt);
        nbt.putBoolean("isInfertile", isInfertile());
    }

    @Override
    public void readAdditionalSaveData(@NotNull CompoundTag nbt) {
        super.readAdditionalSaveData(nbt);
        if (nbt.contains("isInfertile"))
            setIsInfertile(nbt.getBoolean("isInfertile"));
    }

    @Override
    public @NotNull SoundEvent getDeathSound() {
        return SoundRegistry.FACEHUGGER_DEATH.get();
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.EMPTY;
    }

    @Override
    protected @NotNull SoundEvent getHurtSound(@NotNull DamageSource damageSourceIn) {
        return SoundRegistry.FACEHUGGER_HURT.get();
    }

    @Override
    protected void playStepSound(@NotNull BlockPos pos, @NotNull BlockState blockIn) {
        this.playSound(SoundRegistry.FACEHUGGER_RUN.get(), 0.15F, 1.0F);
    }

    public void grabTarget(LivingEntity entity) {
        this.startRiding(entity, true);
        this.setAggressive(false);
        entity.setSpeed(0.0f);
        entity.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 1200, 0));
        if (entity instanceof ServerPlayer player && (!player.isCreative() || !player.isSpectator()))
            player.connection.send(new ClientboundSetPassengersPacket(entity));
    }

    public void handleAttachmentToHost() {
        if (isAttachedToHost()) {
            var host = this.getVehicle();
            if (!(host instanceof LivingEntity livingEntity))
                return;
            if (host instanceof Player player && (player.isCreative() || player.isSpectator())) {
                this.unRide();
                setIsInfertile(true);
                this.kill();
            }
            livingEntity.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 1000, 10, false, false));
            if (livingEntity.getHealth() > livingEntity.getMaxHealth())
                livingEntity.heal(6);
            if (getVehicle() instanceof Player player && player.getFoodData().needsFood())
                player.getFoodData().setFoodLevel(20);
        }
    }

    public boolean isAttachedToHost() {
        return this.getVehicle() instanceof LivingEntity && this.getVehicle().isAlive();
    }

    public boolean isInfertile() {
        return entityData.get(IS_INFERTILE);
    }

    public void setIsInfertile(boolean value) {
        entityData.set(IS_INFERTILE, value);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return LivingEntity.createLivingAttributes()
            .add(Attributes.MAX_HEALTH, CommonMod.getConfig().entityConfigs.facehuggerConfigs.facehuggerHealth)
            .add(Attributes.ARMOR, CommonMod.getConfig().entityConfigs.facehuggerConfigs.facehuggerArmor)
            .add(
                Attributes.ARMOR_TOUGHNESS,
                CommonMod.getConfig().entityConfigs.facehuggerConfigs.facehuggerArmorToughness
            )
            .add(
                Attributes.KNOCKBACK_RESISTANCE,
                CommonMod.getConfig().entityConfigs.facehuggerConfigs.facehuggerKnockbackRes
            )
            .add(Attributes.FOLLOW_RANGE, 32.0)
            .add(Attributes.MOVEMENT_SPEED, 0.25);
    }

    public void resetAnimationState() {
        currentClientAnim = null;
    }

    public void playAnimation(ClientAnimState next) {
        if (currentClientAnim == next) {
            return;
        }

        currentClientAnim = next;

        switch (next) {
            case DEATH -> animationDispatcher.clientDeath();
            case WALK -> animationDispatcher.clientWalk();
            case RUN -> animationDispatcher.clientRun();
            case IDLE -> animationDispatcher.clientIdle();
            case IN_AIR -> animationDispatcher.clientInAir();
            case SWIMMING -> animationDispatcher.clientSwim();
        }
    }

    public void updateAnimations() {
        if (this.isDeadOrDying() || this.isInfertile()) {
            playAnimation(ClientAnimState.DEATH);
            return;
        }

        if (this.isAttachedToHost()) {
            return;
        }

        if (!this.onGround() && !this.isInWater()) {
            playAnimation(ClientAnimState.IN_AIR);
            return;
        }

        if (this.isInWater()) {
            playAnimation(ClientAnimState.SWIMMING);
            return;
        }

        if (moveAnalysis.isMoving()) {
            if (this.isAggressive()) {
                playAnimation(ClientAnimState.RUN);
            } else {
                playAnimation(ClientAnimState.WALK);
            }
            return;
        }

        playAnimation(ClientAnimState.IDLE);
    }
}
