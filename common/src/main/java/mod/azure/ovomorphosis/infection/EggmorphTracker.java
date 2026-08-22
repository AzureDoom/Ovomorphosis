package mod.azure.ovomorphosis.infection;

import mod.azure.azurelib.common.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.data.OvomorphosisSavedData;
import mod.azure.ovomorphosis.entities.ovomorph.OvomorphEntity;
import mod.azure.ovomorphosis.network.EggmorphProgressPacket;
import mod.azure.ovomorphosis.registry.DamageTypeRegistry;
import mod.azure.ovomorphosis.registry.EntityRegistry;
import mod.azure.ovomorphosis.util.AdvancementUtils;

public final class EggmorphTracker {

    private static final Map<BlockPos, EggmorphTracker> ACTIVE = new ConcurrentHashMap<>();

    public record EntrySnapshot(
        String phase,
        int ticks
    ) {}

    private static final class Entry {

        final LivingEntity entity;

        Phase phase;

        int ticks;

        Entry(LivingEntity entity) {
            this.entity = entity;
            this.phase = Phase.SLOWING;
            this.ticks = 0;
        }
    }

    private enum Phase {
        SLOWING,
        TRAPPED,
        DONE
    }

    private final BlockPos blockPos;

    private final Map<Integer, Entry> entries = new ConcurrentHashMap<>();

    public static Map<BlockPos, Map<Integer, EntrySnapshot>> snapshotForSave() {
        var result = new HashMap<BlockPos, Map<Integer, EntrySnapshot>>();
        for (var outer : ACTIVE.entrySet()) {
            var inner = new HashMap<Integer, EntrySnapshot>();
            for (var e : outer.getValue().entries.entrySet()) {
                inner.put(e.getKey(), new EntrySnapshot(e.getValue().phase.name(), e.getValue().ticks));
            }
            if (!inner.isEmpty())
                result.put(outer.getKey(), inner);
        }
        return result;
    }

    private EggmorphTracker(BlockPos blockPos) {
        this.blockPos = blockPos;
    }

    public static void clearAll() {
        ACTIVE.values().forEach(tracker -> tracker.entries.values().forEach(EggmorphTracker::releasePhysics));
        ACTIVE.clear();
    }

    public static EggmorphTracker getOrCreate(BlockPos pos) {
        return ACTIVE.computeIfAbsent(pos.immutable(), EggmorphTracker::new);
    }

    public static void remove(BlockPos pos) {
        var tracker = ACTIVE.remove(pos);
        if (tracker != null) {
            tracker.entries.values().forEach(EggmorphTracker::releasePhysics);
        }
    }

    /**
     * Counts hosts currently being restrained for eggmorphing (SLOWING or TRAPPED phase) within {@code radius} blocks
     * of {@code origin}. An {@link ACTIVE} entry only exists while a host is actively restrained — it's removed the
     * tick a host escapes, finishes eggmorphing, or dies — so this is exactly the "available restrained hosts" count a
     * hive's population needs tracking wants: victims currently being converted, not victims already turned into eggs.
     *
     * @param origin the position to search outward from (typically a hive's dome center)
     * @param radius maximum search radius in blocks
     * @return the number of restrained-host trackers within range
     */
    public static int countActiveNear(BlockPos origin, double radius) {
        var radiusSqr = radius * radius;
        var count = 0;
        for (var pos : ACTIVE.keySet()) {
            if (origin.distSqr(pos) <= radiusSqr)
                count++;
        }
        return count;
    }

    public static void tickAll(ServerLevel level) {
        var it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            var mapEntry = it.next();
            var tracker = mapEntry.getValue();
            tracker.tick();
            if (tracker.entries.isEmpty()) {
                it.remove();
            }
        }
        if (!ACTIVE.isEmpty()) {
            OvomorphosisSavedData.get(level).setDirty();
        }
    }

    public void onEntityInside(LivingEntity entity) {
        entries.computeIfAbsent(entity.getId(), id -> new Entry(entity));
    }

    private void tick() {
        Iterator<Entry> it = entries.values().iterator();
        while (it.hasNext()) {
            var entry = it.next();

            if (
                entry.entity instanceof Player player
                    && (player.isCreative() || player.isSpectator())
            ) {
                releasePhysics(entry);
                sendClear(entry);
                it.remove();
                continue;
            }

            if (!entry.entity.isAlive()) {
                releasePhysics(entry);
                sendClear(entry);
                it.remove();
                continue;
            }

            entry.ticks++;

            if (entry.ticks % 10 == 0) {
                syncProgress(entry);
            }

            switch (entry.phase) {
                case SLOWING -> {
                    applySlow(entry.entity);

                    if (!isInsideBlock(entry.entity)) {
                        releasePhysics(entry);
                        sendClear(entry);
                        it.remove();
                        break;
                    }

                    if (entry.ticks >= 100) {
                        entry.phase = Phase.TRAPPED;
                        entry.ticks = 0;
                        entry.entity.setPos(
                            blockPos.getX() + 0.5,
                            blockPos.getY(),
                            blockPos.getZ() + 0.5
                        );
                        trapEntity(entry.entity);
                    }
                }
                case TRAPPED -> {
                    if (!isInsideBlock(entry.entity)) {
                        releasePhysics(entry);
                        sendClear(entry);
                        it.remove();
                        break;
                    }

                    trapEntity(entry.entity);

                    if (entry.ticks >= CommonMod.getConfig().eggmorphTotalTicks) {
                        eggmorph(entry);
                        it.remove();
                    }
                }
                case DONE -> it.remove();
            }
        }
    }

    private static final double MOB_PULL_STRENGTH = 0.18D;

    private static final double PLAYER_PULL_STRENGTH = 0.01D;

    private void applySlow(LivingEntity entity) {
        if (entity instanceof Mob mob) {
            mob.getNavigation().stop();
            mob.setTarget(null);
        }

        var cx = blockPos.getX() + 0.5;
        var cy = blockPos.getY() + 0.5;
        var cz = blockPos.getZ() + 0.5;
        var toCenter = new Vec3(
            cx - entity.getX(),
            cy - entity.getY(),
            cz - entity.getZ()
        );

        if (entity instanceof ServerPlayer serverPlayer) {
            boolean hasInput = Math.abs(serverPlayer.xxa) > 0.01F
                || Math.abs(serverPlayer.zza) > 0.01F;

            if (toCenter.lengthSqr() > 0.01D) {
                var pull = toCenter.normalize().scale(PLAYER_PULL_STRENGTH);
                if (hasInput) {
                    var current = serverPlayer.getDeltaMovement();
                    serverPlayer.setDeltaMovement(
                        current.x + pull.x,
                        current.y + pull.y,
                        current.z + pull.z
                    );
                } else {
                    serverPlayer.setDeltaMovement(pull);
                }
            }

            serverPlayer.hasImpulse = true;
            serverPlayer.connection.send(new ClientboundSetEntityMotionPacket(serverPlayer));
        } else {
            Vec3 movement = toCenter.lengthSqr() > 0.01D
                ? toCenter.normalize().scale(MOB_PULL_STRENGTH)
                : Vec3.ZERO;
            entity.setDeltaMovement(movement);
            entity.hasImpulse = true;
        }
    }

    private void trapEntity(LivingEntity entity) {
        entity.setDeltaMovement(0, 0, 0);
        entity.fallDistance = 0F;
        entity.setNoGravity(true);
        entity.noPhysics = true;
        if (entity instanceof Mob mob) {
            mob.getNavigation().stop();
            mob.setTarget(null);
        }
    }

    private static void releasePhysics(Entry entry) {
        entry.entity.setNoGravity(false);
        entry.entity.noPhysics = false;
    }

    private static void sendClear(Entry entry) {
        if (entry.entity.level() instanceof ServerLevel) {
            Services.NETWORK.sendToTrackingEntityAndSelf(
                new EggmorphProgressPacket(entry.entity.getId(), 0f),
                entry.entity
            );
        }
    }

    private void syncProgress(Entry entry) {
        var progress = entry.phase == Phase.TRAPPED ? entry.ticks / CommonMod.getConfig().eggmorphTotalTicks : 0f;
        if (entry.entity.level() instanceof ServerLevel) {
            Services.NETWORK.sendToTrackingEntityAndSelf(
                new EggmorphProgressPacket(entry.entity.getId(), progress),
                entry.entity
            );
        }
    }

    private void eggmorph(Entry entry) {
        releasePhysics(entry);
        sendClear(entry);

        var ovomorph = new OvomorphEntity(EntityRegistry.OVOMORPH.get(), entry.entity.level());
        ovomorph.setPos(blockPos.getX() + 0.5, blockPos.getY(), blockPos.getZ() + 0.5);
        ovomorph.noPhysics = true;
        entry.entity.level().addFreshEntity(ovomorph);
        ovomorph.noPhysics = false;

        for (var effect : entry.entity.getActiveEffects()) {
            ovomorph.addEffect(new MobEffectInstance(effect));
        }

        if (entry.entity instanceof ServerPlayer serverPlayer) {
            AdvancementUtils.triggerAdvancement(serverPlayer, "eggmorphed");
        }

        entry.entity.hurt(DamageTypeRegistry.of(entry.entity.level(), DamageTypeRegistry.EGGMORPH), Float.MAX_VALUE);
    }

    private boolean isInsideBlock(LivingEntity entity) {
        var centeredX = blockPos.getX() + 0.5;
        var CenteredZ = blockPos.getZ() + 0.5;
        return Math.abs(entity.getX() - centeredX) <= 0.5
            && Math.abs(entity.getZ() - CenteredZ) <= 0.5
            && Math.abs(entity.blockPosition().getY() - blockPos.getY()) <= 1;
    }

    public static void restoreEntry(BlockPos pos, LivingEntity entity, String phaseName, int ticks) {
        var tracker = ACTIVE.computeIfAbsent(pos.immutable(), EggmorphTracker::new);
        var entry = new Entry(entity);
        try {
            entry.phase = Phase.valueOf(phaseName);
        } catch (IllegalArgumentException e) {
            entry.phase = Phase.SLOWING;
        }
        entry.ticks = ticks;
        if (entry.phase == Phase.TRAPPED) {
            entity.setNoGravity(true);
            entity.noPhysics = true;
        }
        tracker.entries.put(entity.getId(), entry);
    }
}
