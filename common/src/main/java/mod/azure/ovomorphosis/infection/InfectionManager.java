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
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.data.OvomorphosisSavedData;
import mod.azure.ovomorphosis.entities.AbstractAlienEntity;
import mod.azure.ovomorphosis.entities.chestburster.ChestbursterEntity;
import mod.azure.ovomorphosis.entities.runner.RunnerEntity;
import mod.azure.ovomorphosis.registry.DamageTypeRegistry;
import mod.azure.ovomorphosis.registry.EntityRegistry;
import mod.azure.ovomorphosis.registry.SoundRegistry;
import mod.azure.ovomorphosis.util.AdvancementUtils;
import mod.azure.ovomorphosis.util.ModTags;

public final class InfectionManager {

    private InfectionManager() {}

    private static final Map<UUID, InfectionState> INFECTIONS = new ConcurrentHashMap<>();

    public static Map<UUID, InfectionState> snapshotForSave() {
        return new HashMap<>(INFECTIONS);
    }

    public static void restore(UUID uuid, InfectionState state) {
        INFECTIONS.put(uuid, state);
    }

    public static void removeInfection(UUID uuid) {
        INFECTIONS.remove(uuid);
    }

    public static void clearAll() {
        INFECTIONS.clear();
    }

    public static void infect(LivingEntity host, int random) {
        if (isInfected(host))
            return;

        var duration = rollInfectionDuration(random);
        var state = new InfectionState(duration);
        state.lastKnownPos = host.blockPosition();
        state.dimension = host.level().dimension();
        state.isPlayer = host instanceof Player;
        INFECTIONS.put(host.getUUID(), state);
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
            AdvancementUtils.triggerAdvancement(serverPlayer, "facehugged");
        }
    }

    /**
     * Picks an infection duration between the configured min and max (inclusive). Guards against misconfiguration: a
     * non-positive minimum is raised to 1 tick, and a maximum below the minimum is treated as equal to it, so a swapped
     * min/max can no longer produce a divide-by-zero or a negative duration. {@code floorMod} keeps the roll in range
     * even for {@link Integer#MIN_VALUE}, where {@code Math.abs} would stay negative.
     */
    static int rollInfectionDuration(int random) {
        var min = Math.max(1, CommonMod.getConfig().infectionMinTicks);
        var max = Math.max(min, CommonMod.getConfig().infectionMaxTicks);
        var span = (long) max - min + 1L;
        return (int) (min + Math.floorMod(random, span));
    }

    public static boolean isInfected(LivingEntity entity) {
        return INFECTIONS.containsKey(entity.getUUID());
    }

    public static void clearInfection(LivingEntity entity) {
        INFECTIONS.remove(entity.getUUID());
    }

    public static void tick(ServerLevel level) {
        var it = INFECTIONS.entrySet().iterator();

        while (it.hasNext()) {
            var entry = it.next();
            var uuid = entry.getKey();
            var state = entry.getValue();

            var entity = level.getEntity(uuid);
            if (!(entity instanceof LivingEntity host)) {
                keepHostChunkLoaded(level, state);
                continue;
            }

            state.dimension = level.dimension();
            state.isPlayer = host instanceof Player;

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

            state.ticksSinceLastDamage++;
            if (entity instanceof Mob mob) {
                mob.setPersistenceRequired();
            }

            var phase = state.getPhase();
            if (state.ticks % 100 == 0) {
                switch (phase) {
                    case SYMPTOMATIC -> {
                        host.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 140, 0, true, false));
                        host.addEffect(new MobEffectInstance(MobEffects.HUNGER, 140, 0, true, false));
                    }
                    case CRITICAL -> {
                        host.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 160, 1, true, false));
                        host.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 160, 1, true, false));
                        host.addEffect(new MobEffectInstance(MobEffects.HUNGER, 160, 1, true, false));
                        host.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, true, false));
                    }
                }
            }

            if (state.isInDamagePhase() && !state.hasBurst) {
                if (state.ticksSinceLastDamage >= 20) {
                    state.ticksSinceLastDamage = 0;
                    if (entity instanceof ServerPlayer serverPlayer) {
                        serverPlayer.sendOverlayMessage(
                            Component.translatable("msg.ovomorphosis.chest_bursting")
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

    /**
     * {@link #tick} runs once per dimension over the shared infection map, so a host that isn't in {@code level} is
     * very often simply in another dimension. Only re-ticket the host's last known chunk when the host is a non-player
     * that was last seen in this dimension. Infections from older saves without a recorded dimension are assumed to be
     * in the Overworld until the host is found.
     */
    private static void keepHostChunkLoaded(ServerLevel level, InfectionState state) {
        if (state.isPlayer)
            return;

        var lastDimension = state.dimension != null ? state.dimension : Level.OVERWORLD;
        if (!lastDimension.equals(level.dimension()))
            return;

        if (state.lastKnownPos == null || state.lastKnownPos.equals(BlockPos.ZERO))
            return;

        var chunkPos = new ChunkPos(state.lastKnownPos.getX(), state.lastKnownPos.getZ());
        level.getChunkSource()
            .addTicketWithRadius(
                TicketType.UNKNOWN,
                chunkPos,
                2
            );
    }

    private static void applyInfectionDamage(LivingEntity host, ServerLevel level) {
        host.hurtServer(level, DamageTypeRegistry.of(level, DamageTypeRegistry.XENOMORPH_INFECTION), 1F);
    }

    @SuppressWarnings("deprecation")
    private static void triggerBurst(LivingEntity host, ServerLevel level) {
        if (level.isClientSide())
            return;

        level.playSound(host, host.blockPosition(), SoundRegistry.CHEST_BURST.get(), SoundSource.HOSTILE, 1.0F, 1.0F);
        spawnBloodParticles(host, level, true);
        if (host.getType().builtInRegistryHolder().is(ModTags.XENOMORPH_HOST)) {
            spawnMob(host, level, new ChestbursterEntity(EntityRegistry.CHESTBURSTER.get(), level));
        } else if (host.getType().builtInRegistryHolder().is(ModTags.RUNNER_HOST)) {
            spawnMob(host, level, new RunnerEntity(EntityRegistry.RUNNER.get(), level));
        }

        if (host instanceof ServerPlayer serverPlayer) {
            AdvancementUtils.triggerAdvancement(serverPlayer, "chest_burst");
        }

        host.hurt(DamageTypeRegistry.of(level, DamageTypeRegistry.XENOMORPH_INFECTION), Float.MAX_VALUE);
    }

    public static void spawnMob(LivingEntity host, ServerLevel level, AbstractAlienEntity alien) {
        var spawnPos = host.position().add(0, host.getBbHeight() * 0.5, 0);
        alien.setPos(spawnPos.x, spawnPos.y, spawnPos.z);

        var angle = host.getRandom().nextFloat() * (float) (Math.PI * 2);
        alien.setDeltaMovement(
            Mth.cos(angle) * 0.4f,
            0.6f,
            Mth.sin(angle) * 0.4f
        );
        level.addFreshEntity(alien);
        if (host.hasCustomName())
            alien.setCustomName(host.getCustomName());
        for (var effect : host.getActiveEffects()) {
            alien.addEffect(new MobEffectInstance(effect));
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

    public static int getInfectionRemainingTime(LivingEntity entity) {
        var state = INFECTIONS.get(entity.getUUID());
        return state != null ? Math.max(0, state.duration - state.ticks) : 0;
    }
}
