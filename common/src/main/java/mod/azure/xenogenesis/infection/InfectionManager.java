package mod.azure.xenogenesis.infection;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import mod.azure.xenogenesis.entities.facehugger.FacehuggerEntity;
import mod.azure.xenogenesis.registry.DamageTypeRegistry;
import mod.azure.xenogenesis.registry.EntityRegistry;
import mod.azure.xenogenesis.registry.SoundRegistry;

public final class InfectionManager {

    private InfectionManager() {}

    private static final Map<UUID, InfectionState> INFECTIONS = new ConcurrentHashMap<>();

    public static void infect(LivingEntity host, int random) {
        if (isInfected(host))
            return;

        int range = InfectionState.MAX_DURATION - InfectionState.MIN_DURATION;
        int duration = InfectionState.MIN_DURATION + Math.abs(random % (range + 1));
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
                continue;
            }

            if (!host.isAlive()) {
                it.remove();
                continue;
            }

            state.ticks++;
            state.ticksSinceLastDamage++;

            if (state.isInDamagePhase() && !state.hasBurst) {
                if (state.ticksSinceLastDamage >= InfectionState.DAMAGE_INTERVAL) {
                    state.ticksSinceLastDamage = 0;
                    applyInfectionDamage(host, level);
                    // TODO: Spawn blood particles
                }

                if (host.getHealth() <= InfectionState.BURST_HEALTH_THRESHOLD) {
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
    }

    private static void applyInfectionDamage(LivingEntity host, ServerLevel level) {
        host.hurt(DamageTypeRegistry.of(level), InfectionState.DAMAGE_PER_INTERVAL);
    }

    private static void triggerBurst(LivingEntity host, ServerLevel level) {
        if (level.isClientSide())
            return;

        level.playSound(host, host.blockPosition(), SoundRegistry.CHEST_BURST.get(), SoundSource.HOSTILE, 1.0F, 1.0F);

        // TODO: swap FacehuggerEntity for ChestbursterEntity once available
        var burster = new FacehuggerEntity(EntityRegistry.FACEHUGGER.get(), level);

        var spawnPos = host.position().add(0, host.getBbHeight() * 0.5, 0);
        burster.setPos(spawnPos.x, spawnPos.y, spawnPos.z);

        var angle = host.getRandom().nextFloat() * (float) (Math.PI * 2);
        burster.setDeltaMovement(
            Mth.cos(angle) * 0.4f,
            0.6f,
            Mth.sin(angle) * 0.4f
        );
        burster.setIsInfertile(false);
        level.addFreshEntity(burster);

        host.kill();
    }
}
