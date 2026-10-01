package mod.azure.ovomorphosis.infection;

import mod.azure.azurelib.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.blocks.ResinWebBlockEntity;
import mod.azure.ovomorphosis.data.OvomorphosisSavedData;
import mod.azure.ovomorphosis.entities.ovomorph.OvomorphEntity;
import mod.azure.ovomorphosis.network.EggmorphProgressPacket;
import mod.azure.ovomorphosis.registry.DamageTypeRegistry;
import mod.azure.ovomorphosis.registry.EntityRegistry;
import mod.azure.ovomorphosis.util.AdvancementUtils;

/**
 * Tracks hosts restrained inside resin webs while they are converted into ovomorphs.
 * <p>
 * State is keyed per dimension, and {@link #tickAll} only advances the trackers belonging to the level being ticked, so
 * conversion runs at the configured speed no matter how many dimensions are loaded.
 * <p>
 * Hosts are persisted by UUID. When a restrained host's entity is not loaded (server restart, chunk unload, player
 * logout) its entry is parked in {@link #PENDING} and resumed once the entity is loaded again, or released if the web
 * is gone or the host turns up in another dimension. That guarantees a host never stays stuck with gravity disabled.
 */
public final class EggmorphTracker {

    private static final Map<ResourceKey<Level>, Map<BlockPos, EggmorphTracker>> ACTIVE = new ConcurrentHashMap<>();

    /** Restrained hosts whose entity is not currently loaded, keyed by host UUID. */
    private static final Map<UUID, SavedEntry> PENDING = new ConcurrentHashMap<>();

    /** How often (in ticks) pending hosts are looked up. */
    private static final int PENDING_RESOLVE_INTERVAL = 10;

    private static final double MOB_PULL_STRENGTH = 0.18D;

    private static final double PLAYER_PULL_STRENGTH = 0.01D;

    /**
     * Persisted form of a restrained host. Also used for {@link #PENDING} entries.
     *
     * @param hadNoGravity whether the host already had {@code NoGravity} set before it was trapped, so releasing it
     *                     restores the original value instead of always clearing it
     */
    public record SavedEntry(
        ResourceKey<Level> dimension,
        BlockPos pos,
        UUID entityId,
        String phase,
        int ticks,
        boolean hadNoGravity
    ) {

        SavedEntry withPhase(String newPhase) {
            return new SavedEntry(dimension, pos, entityId, newPhase, 0, hadNoGravity);
        }
    }

    private static final class Entry {

        final LivingEntity entity;

        final boolean hadNoGravity;

        Phase phase;

        int ticks;

        Entry(LivingEntity entity, boolean hadNoGravity) {
            this.entity = entity;
            this.hadNoGravity = hadNoGravity;
            this.phase = Phase.SLOWING;
            this.ticks = 0;
        }
    }

    private enum Phase {

        SLOWING,
        TRAPPED,
        DONE;

        static Phase parse(String name) {
            try {
                return Phase.valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return SLOWING;
            }
        }
    }

    private final ResourceKey<Level> dimension;

    private final BlockPos blockPos;

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    private EggmorphTracker(ResourceKey<Level> dimension, BlockPos blockPos) {
        this.dimension = dimension;
        this.blockPos = blockPos;
    }

    public static List<SavedEntry> snapshotForSave() {
        var result = new ArrayList<SavedEntry>(PENDING.values());
        for (var dimensionTrackers : ACTIVE.values()) {
            for (var tracker : dimensionTrackers.values()) {
                for (var entry : tracker.entries.values()) {
                    result.add(
                        new SavedEntry(
                            tracker.dimension,
                            tracker.blockPos,
                            entry.entity.getUUID(),
                            entry.phase.name(),
                            entry.ticks,
                            entry.hadNoGravity
                        )
                    );
                }
            }
        }
        return result;
    }

    /**
     * Queues a saved host to be resumed once its entity is loaded. Entities are not loaded yet when saved data is read,
     * so restored hosts always start out pending.
     */
    public static void restorePending(SavedEntry entry) {
        PENDING.put(entry.entityId(), entry);
    }

    public static void clearAll() {
        ACTIVE.values()
            .forEach(
                dimensionTrackers -> dimensionTrackers.values()
                    .forEach(tracker -> tracker.entries.values().forEach(EggmorphTracker::releasePhysics))
            );
        ACTIVE.clear();
        PENDING.clear();
    }

    public static EggmorphTracker getOrCreate(Level level, BlockPos pos) {
        var dimension = level.dimension();
        return ACTIVE.computeIfAbsent(dimension, key -> new ConcurrentHashMap<>())
            .computeIfAbsent(pos.immutable(), p -> new EggmorphTracker(dimension, p));
    }

    public static void remove(Level level, BlockPos pos) {
        var dimensionTrackers = ACTIVE.get(level.dimension());
        if (dimensionTrackers != null) {
            var tracker = dimensionTrackers.remove(pos);
            if (tracker != null) {
                tracker.entries.values().forEach(entry -> {
                    releasePhysics(entry);
                    sendClear(entry);
                });
            }
        }

        PENDING.replaceAll(
            (uuid, pending) -> pending.dimension().equals(level.dimension()) && pending.pos().equals(pos)
                ? pending.withPhase(Phase.DONE.name())
                : pending
        );
    }

    /**
     * Counts hosts currently being restrained for eggmorphing (SLOWING or TRAPPED phase) within {@code radius} blocks
     * of {@code origin} in {@code level}'s dimension.
     *
     * @param level  the level the hive lives in
     * @param origin the position to search outward from (typically a hive's dome center)
     * @param radius maximum search radius in blocks
     * @return the number of restrained hosts within range
     */
    public static int countActiveNear(Level level, BlockPos origin, double radius) {
        var dimensionTrackers = ACTIVE.get(level.dimension());
        if (dimensionTrackers == null)
            return 0;

        var radiusSqr = radius * radius;
        var count = 0;
        for (var tracker : dimensionTrackers.values()) {
            if (origin.distSqr(tracker.blockPos) <= radiusSqr)
                count += tracker.entries.size();
        }
        return count;
    }

    public static void tickAll(ServerLevel level) {
        if (!PENDING.isEmpty() && level.getGameTime() % PENDING_RESOLVE_INTERVAL == 0) {
            resolvePending(level);
        }

        var dimensionTrackers = ACTIVE.get(level.dimension());
        if (dimensionTrackers != null) {
            var it = dimensionTrackers.entrySet().iterator();
            while (it.hasNext()) {
                var tracker = it.next().getValue();
                tracker.tick();
                if (tracker.entries.isEmpty()) {
                    it.remove();
                }
            }
        }

        if (hasAnyState()) {
            OvomorphosisSavedData.get(level).setDirty();
        }
    }

    private static boolean hasAnyState() {
        if (!PENDING.isEmpty())
            return true;
        for (var dimensionTrackers : ACTIVE.values()) {
            if (!dimensionTrackers.isEmpty())
                return true;
        }
        return false;
    }

    /**
     * Looks up pending hosts in {@code level}. A host found in its own dimension is resumed if its web still exists,
     * and released otherwise; a host found in a different dimension (it changed dimension while restrained) is
     * released.
     */
    private static void resolvePending(ServerLevel level) {
        var it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            var pending = it.next().getValue();

            if (!(level.getEntity(pending.entityId()) instanceof LivingEntity living)) {
                continue;
            }

            var sameDimension = pending.dimension().equals(level.dimension());

            if (sameDimension && !level.isLoaded(pending.pos())) {
                continue;
            }

            it.remove();

            var phase = Phase.parse(pending.phase());
            var webStillExists = sameDimension
                && level.getBlockEntity(pending.pos()) instanceof ResinWebBlockEntity;

            if (!webStillExists || phase == Phase.DONE || !living.isAlive()) {
                living.setNoGravity(pending.hadNoGravity());
                living.noPhysics = false;
                continue;
            }

            var tracker = getOrCreate(level, pending.pos());
            var entry = new Entry(living, pending.hadNoGravity());
            entry.phase = phase;
            entry.ticks = pending.ticks();
            if (entry.phase == Phase.TRAPPED) {
                tracker.trapEntity(living);
            }
            tracker.entries.put(living.getUUID(), entry);
        }
    }

    public void onEntityInside(LivingEntity entity) {
        entries.computeIfAbsent(entity.getUUID(), id -> new Entry(entity, entity.isNoGravity()));
    }

    private void tick() {
        Iterator<Entry> it = entries.values().iterator();
        while (it.hasNext()) {
            var entry = it.next();

            if (entry.entity.isRemoved()) {
                it.remove();
                parkIfUnloaded(entry);
                continue;
            }

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

    /**
     * Handles a host whose entity object was removed from the level. Killed or discarded hosts are simply dropped.
     * Hosts that were unloaded (chunk unload, player logout) or moved to another dimension are parked in
     * {@link #PENDING} so they can be resumed or released when they are loaded again. Their saved data still has
     * {@code NoGravity} set at this point, so dropping them would leave them floating forever.
     */
    private void parkIfUnloaded(Entry entry) {
        var reason = entry.entity.getRemovalReason();
        if (
            reason == null
                || reason == Entity.RemovalReason.KILLED
                || reason == Entity.RemovalReason.DISCARDED
        ) {
            return;
        }

        PENDING.put(
            entry.entity.getUUID(),
            new SavedEntry(
                dimension,
                blockPos,
                entry.entity.getUUID(),
                entry.phase.name(),
                entry.ticks,
                entry.hadNoGravity
            )
        );
    }

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

            serverPlayer.needsSync = true;
            serverPlayer.connection.send(new ClientboundSetEntityMotionPacket(serverPlayer));
        } else {
            Vec3 movement = toCenter.lengthSqr() > 0.01D
                ? toCenter.normalize().scale(MOB_PULL_STRENGTH)
                : Vec3.ZERO;
            entity.setDeltaMovement(movement);
            entity.needsSync = true;
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
        entry.entity.setNoGravity(entry.hadNoGravity);
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
        var centeredZ = blockPos.getZ() + 0.5;
        return Math.abs(entity.getX() - centeredX) <= 0.5
            && Math.abs(entity.getZ() - centeredZ) <= 0.5
            && Math.abs(entity.blockPosition().getY() - blockPos.getY()) <= 1;
    }
}
