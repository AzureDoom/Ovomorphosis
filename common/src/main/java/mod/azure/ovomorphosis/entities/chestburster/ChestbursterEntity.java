package mod.azure.ovomorphosis.entities.chestburster;

import mod.azure.azurelib.common.util.MoveAnalysis;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.ai.actions.chestburster.EatFoodAction;
import mod.azure.ovomorphosis.ai.core.AiKeys;
import mod.azure.ovomorphosis.ai.core.MobBrainRuntime;
import mod.azure.ovomorphosis.ai.goap.AiGoalType;
import mod.azure.ovomorphosis.ai.goap.GoalApplicator;
import mod.azure.ovomorphosis.ai.goap.PlannedGoal;
import mod.azure.ovomorphosis.ai.util.EmergencyDetector;
import mod.azure.ovomorphosis.ai.util.NearestHostileTargetSelector;
import mod.azure.ovomorphosis.ai.util.TargetingSystem;
import mod.azure.ovomorphosis.entities.AbstractAlienEntity;
import mod.azure.ovomorphosis.entities.xenomorph.XenomorphEntity;
import mod.azure.ovomorphosis.registry.EntityRegistry;
import mod.azure.ovomorphosis.registry.SoundRegistry;
import mod.azure.ovomorphosis.util.ClientAnimState;
import mod.azure.ovomorphosis.util.Growable;

public class ChestbursterEntity extends AbstractAlienEntity implements Growable {

    protected static final EntityDataAccessor<Float> GROWTH = SynchedEntityData.defineId(
        ChestbursterEntity.class,
        EntityDataSerializers.FLOAT
    );

    private final MobBrainRuntime<ChestbursterEntity> brainRuntime;

    public final ChestbursterAnimationDispatcher animationDispatcher;

    private final ChestbursterGoalPlanner goalPlanner;

    private final EatFoodAction eatAction = new EatFoodAction(
        16.0,
        0.5,
        0.22,
        5,
        28,
        mob -> mob.animationDispatcher.serverEating()
    );

    public ChestbursterEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        this.animationDispatcher = new ChestbursterAnimationDispatcher(this);
        this.moveAnalysis = new MoveAnalysis(this);
        this.goalPlanner = new ChestbursterGoalPlanner(eatAction);
        this.brainRuntime = new MobBrainRuntime<>(
            this,
            new TargetingSystem<>(new NearestHostileTargetSelector<>(32), 10),
            ChestbursterTree.create(eatAction)
        );
    }

    public static AttributeSupplier.Builder createAttributes() {
        return LivingEntity.createLivingAttributes()
            .add(Attributes.MAX_HEALTH, CommonMod.getConfig().entityConfigs.chestbursterConfigs.chestbursterHealth)
            .add(
                Attributes.ARMOR,
                CommonMod.getConfig().entityConfigs.chestbursterConfigs.chestbursterArmor
            )
            .add(
                Attributes.ARMOR_TOUGHNESS,
                CommonMod.getConfig().entityConfigs.chestbursterConfigs.chestbursterArmorToughness
            )
            .add(
                Attributes.KNOCKBACK_RESISTANCE,
                CommonMod.getConfig().entityConfigs.chestbursterConfigs.chestbursterKnockbackRes
            )
            .add(Attributes.FOLLOW_RANGE, 32)
            .add(Attributes.MOVEMENT_SPEED, 0.25)
            .add(Attributes.STEP_HEIGHT, 1.25D);
    }

    @Override
    public void tick() {
        super.tick();
        moveAnalysis.update();
        if (!this.level().isClientSide()) {
            tickGoalPlanner();
            brainRuntime.tick();
            if (this.isAlive()) {
                grow(this, 1);
            }
        }
    }

    private void tickGoalPlanner() {
        var blackboard = brainRuntime.getBlackboard();
        var cooldowns = brainRuntime.getCooldowns();

        var currentTick = (int) this.level().getGameTime();

        @SuppressWarnings("unchecked")
        var activeGoal = (PlannedGoal<ChestbursterEntity>) blackboard.get(AiKeys.ACTIVE_GOAL, PlannedGoal.class);

        var goalType = activeGoal != null ? activeGoal.type() : AiGoalType.NONE;
        var isPassive = goalType == AiGoalType.GROW_SAFE
            || goalType == AiGoalType.WANDER
            || goalType == AiGoalType.NONE;

        var reactiveReplan = (isPassive && blackboard.has(AiKeys.TARGET))
            || (goalType == AiGoalType.GROW_SAFE && eatAction.canStart(this, blackboard));

        var preplanUrgency = EmergencyDetector.detectPreplanUrgency(this);

        if (!reactiveReplan && preplanUrgency == null && cooldowns.isOnCooldown(AiKeys.GOAL_REPLAN))
            return;

        if (!reactiveReplan && !GoalApplicator.shouldReplan(blackboard, currentTick, preplanUrgency))
            return;

        cooldowns.set(AiKeys.GOAL_REPLAN, 20);
        var newGoal = goalPlanner.chooseGoal(this, blackboard, cooldowns);
        GoalApplicator.apply(this, blackboard, newGoal);
    }

    @Override
    public @NotNull SoundEvent getDeathSound() {
        return SoundEvents.EMPTY;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundRegistry.CHESTBUSTER_IDLE.get();
    }

    @Override
    protected @NotNull SoundEvent getHurtSound(@NotNull DamageSource damageSourceIn) {
        return SoundRegistry.CHESTBUSTER_PAIN.get();
    }

    @Override
    protected void playStepSound(@NotNull BlockPos pos, @NotNull BlockState blockIn) {
        this.playSound(SoundRegistry.CHESTBUSTER_FOOTSTEP.get(), 0.15F, 1.0F);
    }

    @Override
    public float getGrowth() {
        return entityData.get(GROWTH);
    }

    @Override
    public void setGrowth(float growth) {
        entityData.set(GROWTH, growth);
    }

    @Override
    public float getMaxGrowth() {
        return 1400;
    }

    @Override
    public LivingEntity growInto() {
        return new XenomorphEntity(EntityRegistry.XENOMORPH.get(), level());
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.@NotNull Builder builder) {
        super.defineSynchedData(builder);
        builder.define(GROWTH, 0.0F);
    }

    @Override
    public void addAdditionalSaveData(@NotNull CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("growth", getGrowth());
    }

    @Override
    public void readAdditionalSaveData(@NotNull CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.setGrowth(tag.getFloat("growth"));
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
            case SWIMMING -> animationDispatcher.clientSwim();
        }
    }

    public void updateAnimations() {
        var isMovingOnGround = moveAnalysis.isMovingHorizontally() && onGround();

        if (this.isDeadOrDying() || this.getHealth() <= 0) {
            playAnimation(ClientAnimState.DEATH);
            return;
        }

        if (this.isInWater()) {
            playAnimation(ClientAnimState.SWIMMING);
            return;
        }

        if (isMovingOnGround) {
            playAnimation(ClientAnimState.WALK);
            return;
        }

        playAnimation(ClientAnimState.IDLE);
    }
}
