package mod.azure.ovomorphosis.data;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.ai.util.HiveMemory;
import mod.azure.ovomorphosis.infection.EggmorphTracker;
import mod.azure.ovomorphosis.infection.InfectionManager;
import mod.azure.ovomorphosis.infection.InfectionState;
import mod.azure.ovomorphosis.util.BlockBreakProgressManager;

public final class OvomorphosisSavedData extends SavedData {

    /**
     * Save/load lives on the type rather than as overrides on SavedData. The CompoundTag-based format is kept by
     * wrapping it in {@link CompoundTag#CODEC}, so worlds saved before the port still load.
     * <p>
     * The constructor slot is {@link #createEmpty}, not the plain constructor: it is only invoked when the world has no
     * saved data yet, which is exactly when the static runtime state from a previous world must be thrown away.
     */
    public static final SavedDataType<OvomorphosisSavedData> TYPE = new SavedDataType<>(
        CommonMod.modResource("ovomorphosis_data"),
        OvomorphosisSavedData::createEmpty,
        CompoundTag.CODEC.xmap(OvomorphosisSavedData::fromTag, OvomorphosisSavedData::toTag),
        DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES
    );

    private UUID hiveId = UUID.randomUUID();

    private final Map<ResourceKey<Level>, List<HiveMemory>> hives = new HashMap<>();

    private static final double HIVE_JOIN_RADIUS = 256.0D;

    private static final double HIVE_JOIN_RADIUS_SQR = HIVE_JOIN_RADIUS * HIVE_JOIN_RADIUS;

    private OvomorphosisSavedData() {}

    public static OvomorphosisSavedData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /**
     * Used when the world has no {@code ovomorphosis_data} file yet (any brand new world).
     * <p>
     * Infections, eggmorph trackers and block-break progress live in static maps, which outlive a single world in
     * singleplayer. {@link #fromTag} resets them on load, but this path previously didn't, so leaving one world and
     * creating a new one carried the old world's infections over (a singleplayer player has the same UUID in every
     * world).
     */
    private static OvomorphosisSavedData createEmpty() {
        resetRuntimeState();
        return new OvomorphosisSavedData();
    }

    /**
     * Clears all static, per-server runtime state. Called whenever a server's saved data is first created or loaded.
     */
    private static void resetRuntimeState() {
        EggmorphTracker.clearAll();
        InfectionManager.clearAll();
        BlockBreakProgressManager.clearAll();
    }

    public static HiveMemory getOrCreateHive(ServerLevel level, BlockPos origin) {
        var data = get(level);
        var dimensionHives = data.hives.computeIfAbsent(level.dimension(), _ -> new ArrayList<>());

        var nearest = getNearest(origin, dimensionHives);
        if (nearest != null)
            return nearest;

        var created = new HiveMemory();
        var center = HiveMemory.resolveOpenCenter(level, origin);
        HiveMemory.ensureCenterClear(level, center);
        created.claimDomeCenter(center);

        dimensionHives.add(created);
        data.setDirty();

        return created;
    }

    /**
     * Finds the nearest existing hive to {@code origin} within the usual join radius, without creating one if none is
     * found — unlike {@link #getOrCreateHive}, which is meant for the AI's own hive-expansion logic and would
     * spuriously spawn a brand-new (dome-less) hive entry if used for something like a block-placement hook.
     */
    public static Optional<HiveMemory> findNearestHive(ServerLevel level, BlockPos origin) {
        var dimensionHives = get(level).hives.get(level.dimension());
        if (dimensionHives == null)
            return Optional.empty();

        return Optional.ofNullable(getNearest(origin, dimensionHives));
    }

    private static @Nullable HiveMemory getNearest(BlockPos origin, List<HiveMemory> dimensionHives) {
        HiveMemory nearest = null;
        var nearestDistanceSq = Double.MAX_VALUE;
        for (var hive : dimensionHives) {
            var center = hive.getDomeCenter().orElse(null);
            if (center == null)
                continue;

            var distanceSq = center.distSqr(origin);
            if (distanceSq <= HIVE_JOIN_RADIUS_SQR && distanceSq < nearestDistanceSq) {
                nearest = hive;
                nearestDistanceSq = distanceSq;
            }
        }
        return nearest;
    }

    public static void markHiveDirty(ServerLevel level) {
        get(level).setDirty();
    }

    /**
     * Drops {@code hive} from {@code level}'s dimension once every block it ever had has been destroyed (see
     * {@link HiveMemory#isFullyDestroyed()}), so it no longer shows up for {@link #findNearestHive} or
     * {@link #getOrCreateHive}.
     *
     * @return {@code true} if the hive was actually removed
     */
    public static boolean removeHiveIfDestroyed(ServerLevel level, HiveMemory hive) {
        if (!hive.isFullyDestroyed())
            return false;

        var data = get(level);
        var dimensionHives = data.hives.get(level.dimension());

        if (dimensionHives != null && dimensionHives.remove(hive)) {
            data.setDirty();
            return true;
        }

        return false;
    }

    public static @Nullable HiveMemory findHiveById(ServerLevel level, UUID hiveId) {
        var dimensionHives = get(level).hives.get(level.dimension());
        if (dimensionHives == null)
            return null;

        for (var hive : dimensionHives) {
            if (hive.getHiveId().equals(hiveId))
                return hive;
        }

        return null;
    }

    private CompoundTag toTag() {
        var tag = new CompoundTag();
        tag.store("hiveId", UUIDUtil.CODEC, hiveId);
        tag.put("eggmorph", saveEggmorph());
        tag.put("infections", saveInfections());
        tag.put("hives", saveHives());
        return tag;
    }

    private static OvomorphosisSavedData fromTag(CompoundTag tag) {
        var data = new OvomorphosisSavedData();
        resetRuntimeState();

        tag.read("hiveId", UUIDUtil.CODEC).ifPresent(id -> data.hiveId = id);

        loadEggmorph(tag.getListOrEmpty("eggmorph"));
        loadInfections(tag.getListOrEmpty("infections"));

        if (tag.contains("hives")) {
            data.loadHives(tag.getCompoundOrEmpty("hives"));
        } else if (tag.contains("hiveMemory")) {
            var legacyHive = HiveMemory.load(tag.getCompoundOrEmpty("hiveMemory"));
            data.hives.computeIfAbsent(Level.OVERWORLD, _ -> new ArrayList<>()).add(legacyHive);
            data.setDirty();
        }
        return data;
    }

    private CompoundTag saveHives() {
        var root = new CompoundTag();
        var list = new ListTag();

        for (var dimensionEntry : hives.entrySet()) {
            var dimension = dimensionEntry.getKey();
            for (var hive : dimensionEntry.getValue()) {
                var hiveTag = new CompoundTag();
                hiveTag.putString("dimension", dimension.identifier().toString());
                hiveTag.put("data", hive.save());
                list.add(hiveTag);
            }
        }

        root.put("entries", list);
        return root;
    }

    private void loadHives(CompoundTag root) {
        hives.clear();

        root.getListOrEmpty("entries").compoundStream().forEach(entry -> {
            var dimension = entry.getString("dimension").map(OvomorphosisSavedData::parseDimension).orElse(null);
            var hiveData = entry.getCompound("data").orElse(null);
            if (dimension == null || hiveData == null)
                return;

            hives.computeIfAbsent(dimension, _ -> new ArrayList<>()).add(HiveMemory.load(hiveData));
        });
    }

    /**
     * Eggmorph hosts are saved as a flat list keyed by host UUID and dimension. The entity's runtime int id, which the
     * previous format used, is reassigned every time the entity loads, so it can never be resolved after a restart.
     */
    private static ListTag saveEggmorph() {
        var list = new ListTag();
        for (var entry : EggmorphTracker.snapshotForSave()) {
            var compound = new CompoundTag();
            compound.putString("dimension", entry.dimension().identifier().toString());
            compound.store("pos", BlockPos.CODEC, entry.pos());
            compound.store("uuid", UUIDUtil.CODEC, entry.entityId());
            compound.putString("phase", entry.phase());
            compound.putInt("ticks", entry.ticks());
            compound.putBoolean("hadNoGravity", entry.hadNoGravity());
            list.add(compound);
        }
        return list;
    }

    /**
     * Entities are not loaded yet when saved data is read (and the codec has no level anyway), so every host is
     * restored as pending and resumed by {@link EggmorphTracker} once its entity loads. Entries in the old format (no
     * {@code uuid}) cannot be resolved and are skipped.
     */
    private static void loadEggmorph(ListTag list) {
        list.compoundStream().forEach(compound -> {
            var uuid = compound.read("uuid", UUIDUtil.CODEC).orElse(null);
            var pos = compound.read("pos", BlockPos.CODEC).orElse(null);
            var dimension = compound.getString("dimension").map(OvomorphosisSavedData::parseDimension).orElse(null);
            if (uuid == null || pos == null || dimension == null)
                return;

            EggmorphTracker.restorePending(
                new EggmorphTracker.SavedEntry(
                    dimension,
                    pos,
                    uuid,
                    compound.getStringOr("phase", ""),
                    compound.getIntOr("ticks", 0),
                    compound.getBooleanOr("hadNoGravity", false)
                )
            );
        });
    }

    private static @Nullable ResourceKey<Level> parseDimension(String raw) {
        var location = Identifier.tryParse(raw);
        return location == null ? null : ResourceKey.create(Registries.DIMENSION, location);
    }

    private static ListTag saveInfections() {
        var list = new ListTag();
        for (var entry : InfectionManager.snapshotForSave().entrySet()) {
            var state = entry.getValue();
            var compound = new CompoundTag();
            compound.store("uuid", UUIDUtil.CODEC, entry.getKey());
            compound.putInt("duration", state.duration);
            compound.putInt("ticks", state.ticks);
            compound.putInt("ticksSinceLastDamage", state.ticksSinceLastDamage);
            compound.putBoolean("hasBurst", state.hasBurst);
            compound.storeNullable("lastKnownPos", BlockPos.CODEC, state.lastKnownPos);
            if (state.dimension != null)
                compound.putString("dimension", state.dimension.identifier().toString());
            compound.putBoolean("isPlayer", state.isPlayer);
            list.add(compound);
        }
        return list;
    }

    private static void loadInfections(ListTag list) {
        list.compoundStream().forEach(compound -> {
            var uuid = compound.read("uuid", UUIDUtil.CODEC).orElse(null);
            var duration = compound.getInt("duration").orElse(null);
            if (uuid == null || duration == null)
                return;

            var state = new InfectionState(duration);
            state.ticks = compound.getIntOr("ticks", 0);
            state.ticksSinceLastDamage = compound.getIntOr("ticksSinceLastDamage", 0);
            state.hasBurst = compound.getBooleanOr("hasBurst", false);
            state.lastKnownPos = compound.read("lastKnownPos", BlockPos.CODEC).orElse(null);
            state.dimension = compound.getString("dimension").map(OvomorphosisSavedData::parseDimension).orElse(null);
            state.isPlayer = compound.getBooleanOr("isPlayer", false);
            InfectionManager.restore(uuid, state);
        });
    }
}
