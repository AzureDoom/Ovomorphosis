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
import net.minecraft.world.entity.LivingEntity;
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

public final class OvomorphosisSavedData extends SavedData {

    /**
     * Save/load now lives on the type rather than as overrides on SavedData. The existing CompoundTag-based format is
     * kept as-is by wrapping it in {@link CompoundTag#CODEC}, so worlds saved before the port still load.
     */
    public static final SavedDataType<OvomorphosisSavedData> TYPE = new SavedDataType<>(
        CommonMod.modResource("ovomorphosis_data"),
        OvomorphosisSavedData::new,
        CompoundTag.CODEC.xmap(OvomorphosisSavedData::fromTag, OvomorphosisSavedData::toTag),
        DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES
    );

    private UUID hiveId = UUID.randomUUID();

    private final Map<ResourceKey<Level>, List<HiveMemory>> hives = new HashMap<>();

    /**
     * Eggmorph entries need a live level to resolve their entities, which the codec no longer has access to. They're
     * parked here on load and restored the first time {@link #get} runs with a level in hand.
     */
    private @Nullable ListTag pendingEggmorph;

    private static final double HIVE_JOIN_RADIUS = 256.0D;

    private static final double HIVE_JOIN_RADIUS_SQR = HIVE_JOIN_RADIUS * HIVE_JOIN_RADIUS;

    private OvomorphosisSavedData() {}

    public static OvomorphosisSavedData get(ServerLevel level) {
        var overworld = level.getServer().overworld();
        var data = overworld.getDataStorage().computeIfAbsent(TYPE);
        data.restorePendingEggmorph(overworld);
        return data;
    }

    private void restorePendingEggmorph(ServerLevel level) {
        if (pendingEggmorph == null)
            return;
        var list = pendingEggmorph;
        pendingEggmorph = null;
        loadEggmorph(list, level);
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

    // ---------------------------------------------------------------------------------------------------------------
    // Serialization (formerly save(CompoundTag, Provider) / load)
    // ---------------------------------------------------------------------------------------------------------------

    private CompoundTag toTag() {
        var tag = new CompoundTag();
        tag.store("hiveId", UUIDUtil.CODEC, hiveId);
        // If eggmorph restore hasn't run yet this session, write back what was loaded rather than an empty snapshot.
        tag.put("eggmorph", pendingEggmorph != null ? pendingEggmorph.copy() : saveEggmorph());
        tag.put("infections", saveInfections());
        tag.put("hives", saveHives());
        return tag;
    }

    private static OvomorphosisSavedData fromTag(CompoundTag tag) {
        var data = new OvomorphosisSavedData();
        EggmorphTracker.clearAll();
        InfectionManager.clearAll();

        tag.read("hiveId", UUIDUtil.CODEC).ifPresent(id -> data.hiveId = id);

        tag.getList("eggmorph").ifPresent(list -> data.pendingEggmorph = list);
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
            var dimensionLocation = entry.getString("dimension").map(Identifier::tryParse).orElse(null);
            var hiveData = entry.getCompound("data").orElse(null);
            if (dimensionLocation == null || hiveData == null)
                return;

            var dimension = ResourceKey.create(Registries.DIMENSION, dimensionLocation);
            hives.computeIfAbsent(dimension, _ -> new ArrayList<>()).add(HiveMemory.load(hiveData));
        });
    }

    private static ListTag saveEggmorph() {
        var list = new ListTag();
        for (var entry : EggmorphTracker.snapshotForSave().entrySet()) {
            var compound = new CompoundTag();
            compound.store("pos", BlockPos.CODEC, entry.getKey());

            var entriesTag = new ListTag();
            for (var e : entry.getValue().entrySet()) {
                var entryTag = new CompoundTag();
                entryTag.putInt("entityId", e.getKey());
                entryTag.putString("phase", e.getValue().phase().toLowerCase(Locale.ROOT));
                entryTag.putInt("ticks", e.getValue().ticks());
                entriesTag.add(entryTag);
            }
            compound.put("entries", entriesTag);
            list.add(compound);
        }
        return list;
    }

    private static void loadEggmorph(ListTag list, ServerLevel level) {
        list.compoundStream().forEach(compound -> {
            var pos = compound.read("pos", BlockPos.CODEC).orElse(null);
            if (pos == null)
                return;

            compound.getListOrEmpty("entries").compoundStream().forEach(entryTag -> {
                var entityId = entryTag.getIntOr("entityId", -1);
                var phase = entryTag.getStringOr("phase", "");
                var ticks = entryTag.getIntOr("ticks", -1);

                if (level.getEntity(entityId) instanceof LivingEntity living) {
                    EggmorphTracker.restoreEntry(pos, living, phase, ticks);
                }
            });
        });
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
            InfectionManager.restore(uuid, state);
        });
    }
}
