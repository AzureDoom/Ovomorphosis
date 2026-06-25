package mod.azure.ovomorphosis.infection;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.data.OvomorphosisSavedData;
import mod.azure.ovomorphosis.entities.AbstractAlienEntity;
import mod.azure.ovomorphosis.entities.chestburster.ChestbursterEntity;
import mod.azure.ovomorphosis.registry.DamageTypeRegistry;
import mod.azure.ovomorphosis.registry.EntityRegistry;
import mod.azure.ovomorphosis.registry.SoundRegistry;

public final class InfectionManager {

    private InfectionManager() {}

    private static final Map<UUID, InfectionState> INFECTIONS = new ConcurrentHashMap<>();

    public static Map<UUID, InfectionState> snapshotForSave() {
        return new HashMap<>(INFECTIONS);
    }

    public static void restore(UUID uuid, InfectionState state) {
        INFECTIONS.put(uuid, state);
    }

    public static void clearAll() {
        INFECTIONS.clear();
    }

    public static void infect(LivingEntity host, int random) {
        if (isInfected(host))
            return;

        var range = CommonMod.getConfig().infectionMaxTicks - CommonMod.getConfig().infectionMinTicks;
        var duration = CommonMod.getConfig().infectionMinTicks + Math.abs(random % (range + 1));
        INFECTIONS.put(host.getUUID(), new InfectionState(duration));
        host.level()
            .playSound(
                host,
                host.blockPosition(),
                SoundRegistry.FACEHUGGER_IMPLANT.get(),
                SoundSource.HOSTILE,
                1.0F,
                1.0F
            );
        if (host instanceof ServerPlayer serverPlayer) {
            var advancement = serverPlayer.server.getAdvancements()
                .getAdvancement(CommonMod.modResource("facehugged"));
            if (
                advancement != null
                    && !serverPlayer.getAdvancements().getOrStartProgress(advancement).isDone()
            ) {
                for (
                    var s : serverPlayer.getAdvancements()
                        .getOrStartProgress(advancement)
                        .getRemainingCriteria()
                ) {
                    serverPlayer.getAdvancements().award(advancement, s);
                }
            }
        }
    }

    public static boolean isInfected(LivingEntity entity) {
        return INFECTIONS.containsKey(entity.getUUID());
    }

    public static void clearInfection(LivingEntity entity) {
        INFECTIONS.remove(entity.getUUID());
    }

    private static boolean hasLoggedOnce = false;

    public static void tick(ServerLevel level) {
        if (!hasLoggedOnce) {
            hasLoggedOnce = true;
        }
        var it = INFECTIONS.entrySet().iterator();

        while (it.hasNext()) {
            var entry = it.next();
            var uuid = entry.getKey();
            var state = entry.getValue();

            var entity = level.getEntity(uuid);
            if (!(entity instanceof LivingEntity host)) {
                if (state.lastKnownPos != null && !state.lastKnownPos.equals(BlockPos.ZERO)) {
                    var chunkPos = new ChunkPos(state.lastKnownPos);
                    level.getChunkSource()
                        .addRegionTicket(
                            TicketType.UNKNOWN,
                            chunkPos,
                            2,
                            chunkPos
                        );
                }
                continue;
            }

            if (!host.isAlive()) {
                it.remove();
                continue;
            }

            if (
                entity instanceof Player player
                    && (player.isCreative() || player.isSpectator())
            ) {
                state.hasBurst = false;
                it.remove();
                continue;
            }

            state.lastKnownPos = host.blockPosition();
            state.ticks++;

            var phase = state.getPhase();
            if (state.ticks % 100 == 0) {
                switch (phase) {
                    case SYMPTOMATIC -> {
                        host.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 140, 0, true, false));
                        host.addEffect(new MobEffectInstance(MobEffects.HUNGER, 140, 0, true, false));
                    }
                    case CRITICAL -> {
                        host.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 160, 1, true, false));
                        host.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 160, 1, true, false));
                        host.addEffect(new MobEffectInstance(MobEffects.HUNGER, 160, 1, true, false));
                        host.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, true, false));
                    }
                }
            }

            state.ticksSinceLastDamage++;
            if (entity instanceof Mob mob) {
                mob.setPersistenceRequired();
            }

            if (state.isInDamagePhase() && !state.hasBurst) {
                if (state.ticksSinceLastDamage >= 20) {
                    state.ticksSinceLastDamage = 0;
                    if (entity instanceof ServerPlayer serverPlayer) {
                        serverPlayer.displayClientMessage(
                            Component.translatable("msg.ovomorphosis.chest_bursting"),
                            true
                        );
                    }
                    applyInfectionDamage(host, level);
                    spawnBloodParticles(host, level, false);
                }

                if (host.getHealth() <= 1F) {
                    triggerBurst(host, level);
                    state.hasBurst = true;
                    it.remove();
                    continue;
                }
            }

            if (state.isExpired()) {
                triggerBurst(host, level);
                it.remove();
            }
        }
        if (!INFECTIONS.isEmpty()) {
            OvomorphosisSavedData.get(level).setDirty();
        }
    }

    private static void applyInfectionDamage(LivingEntity host, ServerLevel level) {
        host.hurt(DamageTypeRegistry.of(level), 1F);
    }

    private static void triggerBurst(LivingEntity host, ServerLevel level) {
        if (level.isClientSide())
            return;

        level.playSound(host, host.blockPosition(), SoundRegistry.CHEST_BURST.get(), SoundSource.HOSTILE, 1.0F, 1.0F);
        spawnBloodParticles(host, level, true);
        // AbstractAlienEntity burster = null;
        // if (host.getType().is(ModTags.XENOMORPH_HOST)) {
        // burster = new ChestbursterEntity(EntityRegistry.CHESTBURSTER.get(), level);
        // } else if (host.getType().is(ModTags.RUNNER_HOST)) {
        // burster = new RunnerEntity(EntityRegistry.RUNNER.get(), level);
        // }
        AbstractAlienEntity burster = new ChestbursterEntity(EntityRegistry.CHESTBURSTER.get(), level);
        if (burster != null) {
            var spawnPos = host.position().add(0, host.getBbHeight() * 0.5, 0);
            burster.setPos(spawnPos.x, spawnPos.y, spawnPos.z);

            var angle = host.getRandom().nextFloat() * (float) (Math.PI * 2);
            burster.setDeltaMovement(
                Mth.cos(angle) * 0.4f,
                0.6f,
                Mth.sin(angle) * 0.4f
            );
            level.addFreshEntity(burster);

            for (var effect : host.getActiveEffects()) {
                burster.addEffect(new MobEffectInstance(effect));
            }

            if (host instanceof ServerPlayer serverPlayer) {
                var advancement = serverPlayer.server.getAdvancements()
                    .getAdvancement(CommonMod.modResource("chest_burst"));
                if (
                    advancement != null
                        && !serverPlayer.getAdvancements().getOrStartProgress(advancement).isDone()
                ) {
                    for (
                        var s : serverPlayer.getAdvancements()
                            .getOrStartProgress(advancement)
                            .getRemainingCriteria()
                    ) {
                        serverPlayer.getAdvancements().award(advancement, s);
                    }
                }
            }

            host.hurt(DamageTypeRegistry.of(level), Float.MAX_VALUE);
        }
    }

    private static void spawnBloodParticles(LivingEntity host, ServerLevel level, boolean isBurst) {
        var pos = host.position();
        var rng = host.getRandom();
        var particleType = isBurst ? ParticleTypes.DAMAGE_INDICATOR : ParticleTypes.FALLING_LAVA;

        var count = isBurst ? 20 : 4;
        var spread = isBurst ? 0.6 : 0.2;
        var heightOffset = host.getBbHeight() * 0.5;

        for (var i = 0; i < count; i++) {
            level.sendParticles(
                particleType,
                pos.x + (rng.nextDouble() - 0.5) * spread,
                pos.y + heightOffset + (rng.nextDouble() - 0.5) * spread,
                pos.z + (rng.nextDouble() - 0.5) * spread,
                1,
                0,
                0,
                0,
                isBurst ? 0.15 : 0.05
            );
        }
    }

    public static InfectionState.Phase getPhase(LivingEntity entity) {
        var state = INFECTIONS.get(entity.getUUID());
        return state != null ? state.getPhase() : null;
    }
}
