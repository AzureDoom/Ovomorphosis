package mod.azure.xenogenesis.blocks;

import mod.azure.azurelib.common.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import mod.azure.xenogenesis.CommonMod;
import mod.azure.xenogenesis.data.XenogenesisSavedData;
import mod.azure.xenogenesis.entities.ovomorph.OvomorphEntity;
import mod.azure.xenogenesis.network.EggmorphProgressPacket;
import mod.azure.xenogenesis.registry.BlockRegistry;
import mod.azure.xenogenesis.registry.DamageTypeRegistry;
import mod.azure.xenogenesis.registry.EntityRegistry;

public final class EggmorphTracker {

    public static final float SLOW_FACTOR = 0.15F;

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

    public static void tickAll(ServerLevel level) {
        var it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            var mapEntry = it.next();
            var tracker = mapEntry.getValue();
            tracker.tick(level);
            if (tracker.entries.isEmpty()) {
                it.remove();
            }
        }
        if (!ACTIVE.isEmpty()) {
            XenogenesisSavedData.get(level).setDirty();
        }
    }

    public void onEntityInside(LivingEntity entity) {
        entries.computeIfAbsent(entity.getId(), id -> new Entry(entity));
    }

    private void tick(ServerLevel level) {
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

                    if (
                        !isInsideBlock(entry.entity)
                            || !entry.entity.getInBlockState()
                                .is(BlockRegistry.RESIN_WEB_CROSS.get())
                    ) {
                        releasePhysics(entry);
                        sendClear(entry);
                        it.remove();
                        break;
                    }

                    if (entry.ticks >= 100) {
                        entry.phase = Phase.TRAPPED;
                        entry.ticks = 0;
                        trapEntity(entry.entity);
                    }
                }
                case TRAPPED -> {
                    if (
                        !entry.entity.getInBlockState()
                            .is(BlockRegistry.RESIN_WEB_CROSS.get())
                    ) {
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

    private void applySlow(LivingEntity entity) {
        var vel = entity.getDeltaMovement();
        entity.setDeltaMovement(vel.x * SLOW_FACTOR, vel.y * SLOW_FACTOR, vel.z * SLOW_FACTOR);
        if (entity instanceof Mob mob) {
            mob.getNavigation().stop();
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
        if (entry.entity instanceof ServerPlayer serverPlayer) {
            var advancement = serverPlayer.server.getAdvancements()
                .get(CommonMod.modResource("eggmorphed"));
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

        entry.entity.hurt(DamageTypeRegistry.of(entry.entity.level()), Float.MAX_VALUE);
    }

    private boolean isInsideBlock(LivingEntity entity) {
        var entityPos = entity.blockPosition();
        return entityPos.getX() == blockPos.getX()
            && entityPos.getZ() == blockPos.getZ()
            && Math.abs(entityPos.getY() - blockPos.getY()) <= 1;
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
