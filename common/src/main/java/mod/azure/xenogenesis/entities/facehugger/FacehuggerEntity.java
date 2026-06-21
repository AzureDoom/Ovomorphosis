package mod.azure.xenogenesis.entities.facehugger;

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

import mod.azure.xenogenesis.CommonMod;
import mod.azure.xenogenesis.ai.core.MobBrainRuntime;
import mod.azure.xenogenesis.ai.util.CrawlingManager;
import mod.azure.xenogenesis.ai.util.FacehuggerHostileTargetSelector;
import mod.azure.xenogenesis.ai.util.TargetingSystem;
import mod.azure.xenogenesis.entities.AbstractAlienEntity;
import mod.azure.xenogenesis.registry.SoundRegistry;

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
                return FacehuggerEntity.this.onGameEvent(eventHolder, context);
            }
        });

        this.brainRuntime = new MobBrainRuntime<>(
            this,
            new TargetingSystem<>(targetSelector, 10),
            FacehuggerTree.create()
        );
    }

    private boolean onGameEvent(Holder<GameEvent> eventHolder, GameEvent.Context context) {
        if (this.isDeadOrDying() || this.isInfertile()) {
            return false;
        }

        if (!(context.sourceEntity() instanceof LivingEntity living)) {
            return false;
        }

        if (living instanceof AbstractAlienEntity) {
            return false;
        }

        int suspicion = suspicionForEvent(eventHolder);
        if (suspicion <= 0) {
            return false;
        }

        targetSelector.hearSound(living, suspicion);
        return true;
    }

    private static int suspicionForEvent(Holder<GameEvent> event) {
        return event.unwrapKey().map(key -> {
            if (key == GameEvent.STEP.key())
                return 2;
            if (key == GameEvent.HIT_GROUND.key())
                return 6;
            if (key == GameEvent.SWIM.key())
                return 2;
            if (key == GameEvent.SPLASH.key())
                return 8;
            if (key == GameEvent.ELYTRA_GLIDE.key())
                return 2;
            if (key == GameEvent.FLAP.key())
                return 2;
            if (key == GameEvent.ENTITY_INTERACT.key())
                return 10;
            if (key == GameEvent.ENTITY_DAMAGE.key())
                return 20;
            if (key == GameEvent.ENTITY_DIE.key())
                return 25;
            if (key == GameEvent.ENTITY_ACTION.key())
                return 4;
            if (key == GameEvent.BLOCK_CHANGE.key())
                return 10;
            if (key == GameEvent.BLOCK_DESTROY.key())
                return 12;
            if (key == GameEvent.BLOCK_PLACE.key())
                return 10;
            if (key == GameEvent.PROJECTILE_SHOOT.key())
                return 8;
            if (key == GameEvent.PROJECTILE_LAND.key())
                return 8;
            if (key == GameEvent.EXPLODE.key())
                return 30;
            if (key == GameEvent.EAT.key())
                return 4;
            if (key == GameEvent.DRINK.key())
                return 4;
            return 0;
        }).orElse(0);
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
            .add(Attributes.FOLLOW_RANGE, 0.0)
            .add(Attributes.MOVEMENT_SPEED, 0.0);
    }

    public void updateAnimations() {
        var isMovingOnGround = moveAnalysis.isMovingHorizontally() && onGround();

        if (this.isDeadOrDying() || this.isInfertile()) {
            animationDispatcher.clientDeath();
            return;
        }

        if (this.isAttachedToHost()) {
            return;
        }

        if (isMovingOnGround) {
            if (this.isAggressive() && !this.swinging) {
                animationDispatcher.clientRun();
            } else {
                animationDispatcher.clientWalk();
            }
            return;
        }

        animationDispatcher.clientIdle();
    }
}
