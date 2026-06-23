package mod.azure.ovomorphosis.entities.xenomorph;

import mod.azure.azurelib.common.util.MoveAnalysis;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
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

import java.util.SplittableRandom;
import java.util.function.BiConsumer;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.ai.core.MobBrainRuntime;
import mod.azure.ovomorphosis.ai.util.CrawlingManager;
import mod.azure.ovomorphosis.ai.util.TargetingSystem;
import mod.azure.ovomorphosis.ai.util.XenomorphHostileTargetSelector;
import mod.azure.ovomorphosis.entities.AbstractAlienEntity;
import mod.azure.ovomorphosis.registry.SoundRegistry;
import mod.azure.ovomorphosis.util.ClientAnimState;
import mod.azure.ovomorphosis.util.Growable;

public class XenomorphEntity extends AbstractAlienEntity implements Growable {

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

    private final MobBrainRuntime<XenomorphEntity> brainRuntime;

    public final XenomorphAnimationDispatcher animationDispatcher;

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
                @NotNull Holder<GameEvent> eventHolder,
                GameEvent.@NotNull Context context,
                @NotNull Vec3 pos
            ) {
                return XenomorphEntity.this.onGameEvent(eventHolder, context, pos);
            }
        });

        this.brainRuntime = new MobBrainRuntime<>(
            this,
            new TargetingSystem<>(targetSelector, 10),
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
            .add(Attributes.ATTACK_DAMAGE, CommonMod.getConfig().entityConfigs.xenomorphConfigs.xenoAttackDamage);
    }

    private boolean onGameEvent(Holder<GameEvent> eventHolder, GameEvent.Context context, Vec3 pos) {
        if (this.isDeadOrDying()) {
            return false;
        }

        if (!isSignificantEvent(eventHolder)) {
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
        targetSelector.hearSound(source instanceof LivingEntity living ? living : null, investigatePos);
        return true;
    }

    private static boolean isSignificantEvent(Holder<GameEvent> event) {
        return event.unwrapKey()
            .map(
                key -> key == GameEvent.STEP.key()
                    || key == GameEvent.HIT_GROUND.key()
                    || key == GameEvent.SWIM.key()
                    || key == GameEvent.SPLASH.key()
                    || key == GameEvent.ELYTRA_GLIDE.key()
                    || key == GameEvent.FLAP.key()
                    || key == GameEvent.ENTITY_INTERACT.key()
                    || key == GameEvent.ENTITY_DAMAGE.key()
                    || key == GameEvent.ENTITY_DIE.key()
                    || key == GameEvent.ENTITY_ACTION.key()
                    || key == GameEvent.BLOCK_CHANGE.key()
                    || key == GameEvent.BLOCK_DESTROY.key()
                    || key == GameEvent.BLOCK_PLACE.key()
                    || key == GameEvent.PROJECTILE_SHOOT.key()
                    || key == GameEvent.PROJECTILE_LAND.key()
                    || key == GameEvent.EXPLODE.key()
                    || key == GameEvent.EAT.key()
                    || key == GameEvent.DRINK.key()
            )
            .orElse(false);
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
        moveAnalysis.update();
        if (!this.level().isClientSide()) {
            brainRuntime.tick();
            CrawlingManager.updateWallCrawlingPhysics(this);
            if (this.isAlive()) {
                grow(this, 1);
            }
        }
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
        @Nullable SpawnGroupData spawnGroupData
    ) {
        if (spawnType == MobSpawnType.SPAWN_EGG || spawnType == MobSpawnType.COMMAND)
            setGrowth(1200);
        return super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData);
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
        return 1200;
    }

    @Override
    public LivingEntity growInto() {
        return null;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.@NotNull Builder builder) {
        super.defineSynchedData(builder);
        builder.define(GROWTH, 0.0F);
        builder.define(IS_EXECUTION, false);
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
    }

    @Override
    protected boolean canRide(@NotNull Entity vehicle) {
        return false;
    }

    @Override
    public void positionRider(@NotNull Entity entity, @NotNull MoveFunction moveFunction) {
        if (entity instanceof LivingEntity mob) {
            var random = new SplittableRandom();
            mob.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 100, true, true));
            mob.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 40, 1, true, true));
            var f = Mth.sin(this.yBodyRot * ((float) Math.PI / 180));
            var g = Mth.cos(this.yBodyRot * ((float) Math.PI / 180));
            var y1 = random.nextFloat(0.14F, 0.15F);
            var y3 = random.nextFloat(0.44F, 0.45F);
            var y = random.nextFloat(0.74F, 0.75f);
            var y2 = random.nextFloat(1.14F, 1.15f);
            mob.setPos(
                this.getX() + ((this.isExecuting() ? -1.4f : -1.85f) * f),
                this.getY() + (this.isExecuting()
                    ? (mob.getBbHeight() < 1.4 ? y2 : y)
                    : (mob.getBbHeight() < 1.4 ? y3 : y1)),
                this.getZ() - ((this.isExecuting() ? -1.4f : -1.85f) * g)
            );
            mob.yBodyRot = this.yBodyRot;
            mob.setSpeed(0);
        }
    }

    @Override
    @NotNull
    public EntityDimensions getDefaultDimensions(@NotNull Pose pose) {
        if (this.ovomorphosis$isWallCrawling())
            return EntityDimensions.scalable(0.9F, 0.9F);
        return super.getDefaultDimensions(pose);
    }

    @Override
    public boolean doHurtTarget(@NotNull Entity target) {
        if (
            target instanceof LivingEntity livingEntity
                && !this.level().isClientSide
                && this.getRandom().nextInt(100) < 5
        ) {
            disarmTarget(livingEntity);
        }

        this.heal(1.0833F);
        return super.doHurtTarget(target);
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

            if (this.isAggressive() && !this.swinging) {
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

        if (moveAnalysis.isMoving()) {
            if (newEntityTick) {
                lookTicks = 0;
            }

            if (this.ovomorphosis$isWallCrawling()) {
                playAnimation(ClientAnimState.CRAWLING);
            } else if (this.isAggressive() && !this.swinging) {
                playAnimation(ClientAnimState.RUN);
            } else {
                playAnimation(ClientAnimState.WALK);
            }

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
