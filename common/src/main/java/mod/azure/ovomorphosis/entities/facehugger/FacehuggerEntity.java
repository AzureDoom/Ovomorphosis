package mod.azure.ovomorphosis.entities.facehugger;

import mod.azure.azurelib.common.util.MoveAnalysis;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
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
import net.minecraft.world.level.gameevent.DynamicGameEventListener;
import net.minecraft.world.level.gameevent.EntityPositionSource;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.function.BiConsumer;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.ai.core.MobBrainRuntime;
import mod.azure.ovomorphosis.ai.util.CrawlingManager;
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

    private final DynamicGameEventListener<GameEventListener> dynamicGameEventListener;

    public FacehuggerEntity(EntityType<? extends AbstractAlienEntity> entityType, Level level) {
        super(entityType, level);
        this.animationDispatcher = new FacehuggerAnimationDispatcher(this);
        this.moveAnalysis = new MoveAnalysis(this);
        this.targetSelector = new FacehuggerHostileTargetSelector<>(32);

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
                return FacehuggerEntity.this.onGameEvent(eventHolder, context, pos);
            }
        });

        this.brainRuntime = new MobBrainRuntime<>(
            this,
            new TargetingSystem<>(targetSelector, 2),
            FacehuggerTree.create()
        );
    }

    private boolean onGameEvent(Holder<GameEvent> eventHolder, GameEvent.Context context, Vec3 pos) {
        if (this.isDeadOrDying() || this.isInfertile()) {
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
    public void defineSynchedData(SynchedEntityData.@NotNull Builder builder) {
        super.defineSynchedData(builder);
        builder.define(IS_INFERTILE, false);
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
    public void tick() {
        super.tick();
        moveAnalysis.update();
        if (!this.level().isClientSide()) {
            brainRuntime.tick();
            CrawlingManager.updateWallCrawlingPhysics(this);
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
            case IN_AIR -> animationDispatcher.clientInAir();
            case SWIMMING -> animationDispatcher.clientSwim();
        }
    }

    public void updateAnimations() {
        var isMovingOnGround = moveAnalysis.isMovingHorizontally() && onGround();

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

        if (isMovingOnGround) {
            if (this.isAggressive() && !this.swinging) {
                playAnimation(ClientAnimState.RUN);
            } else {
                playAnimation(ClientAnimState.WALK);
            }
            return;
        }

        playAnimation(ClientAnimState.IDLE);
    }
}
