package mod.azure.ovomorphosis.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

import mod.azure.ovomorphosis.ai.util.HiveMemory;
import mod.azure.ovomorphosis.infection.EggmorphTracker;
import mod.azure.ovomorphosis.infection.InfectionManager;
import mod.azure.ovomorphosis.infection.InfectionState;

public final class OvomorphosisSavedData extends SavedData {

    private HiveMemory hiveMemory = new HiveMemory();

    public static OvomorphosisSavedData get(ServerLevel level) {
        var overworld = level.getServer().overworld();

        return overworld.getDataStorage()
            .computeIfAbsent(
                tag -> load(tag, overworld),
                OvomorphosisSavedData::createEmpty,
                "ovomorphosis_data"
            );
    }

    /**
     * Returns the shared {@link HiveMemory} for the given level. Convenience shorthand for
     * {@code OvomorphosisSavedData.get(level).getHiveMemory()}.
     */
    public static HiveMemory getHiveMemory(ServerLevel level) {
        return get(level).hiveMemory;
    }

    private static OvomorphosisSavedData createEmpty() {
        return new OvomorphosisSavedData();
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag) {
        tag.put("eggmorph", saveEggmorph());
        tag.put("infections", saveInfections());
        tag.put("hiveMemory", hiveMemory.save());
        return tag;
    }

    private static OvomorphosisSavedData load(CompoundTag tag, ServerLevel level) {
        var data = new OvomorphosisSavedData();

        EggmorphTracker.clearAll();
        InfectionManager.clearAll();

        if (tag.contains("eggmorph", Tag.TAG_LIST)) {
            loadEggmorph(tag.getList("eggmorph", Tag.TAG_COMPOUND), level);
        }

        if (tag.contains("infections", Tag.TAG_LIST)) {
            loadInfections(tag.getList("infections", Tag.TAG_COMPOUND));
        }

        if (tag.contains("hiveMemory", Tag.TAG_COMPOUND)) {
            data.hiveMemory = HiveMemory.load(tag.getCompound("hiveMemory"));
        }

        return data;
    }

    private static ListTag saveEggmorph() {
        var list = new ListTag();

        for (var entry : EggmorphTracker.snapshotForSave().entrySet()) {
            var compound = new CompoundTag();

            var posTag = NbtUtils.writeBlockPos(entry.getKey());
            compound.put("pos", posTag);

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
        for (var i = 0; i < list.size(); i++) {
            var compound = list.getCompound(i);

            if (!compound.contains("pos", Tag.TAG_COMPOUND)) {
                continue;
            }

            var pos = NbtUtils.readBlockPos(compound.getCompound("pos"));

            var entriesTag = compound.getList("entries", Tag.TAG_COMPOUND);

            for (var j = 0; j < entriesTag.size(); j++) {
                var entryTag = entriesTag.getCompound(j);

                var entityId = entryTag.getInt("entityId");
                var phase = entryTag.getString("phase");
                var ticks = entryTag.getInt("ticks");

                var entity = level.getEntity(entityId);

                if (entity instanceof LivingEntity living) {
                    EggmorphTracker.restoreEntry(pos, living, phase, ticks);
                }
            }
        }
    }

    private static ListTag saveInfections() {
        var list = new ListTag();

        for (var entry : InfectionManager.snapshotForSave().entrySet()) {
            var compound = new CompoundTag();

            compound.putUUID("uuid", entry.getKey());
            compound.putInt("duration", entry.getValue().duration);
            compound.putInt("ticks", entry.getValue().ticks);
            compound.putInt("ticksSinceLastDamage", entry.getValue().ticksSinceLastDamage);
            compound.putBoolean("hasBurst", entry.getValue().hasBurst);

            if (entry.getValue().lastKnownPos != null) {
                compound.put("lastKnownPos", NbtUtils.writeBlockPos(entry.getValue().lastKnownPos));
            }

            list.add(compound);
        }

        return list;
    }

    private static void loadInfections(ListTag list) {
        for (var i = 0; i < list.size(); i++) {
            var compound = list.getCompound(i);

            var uuid = compound.getUUID("uuid");
            var state = new InfectionState(compound.getInt("duration"));

            state.ticks = compound.getInt("ticks");
            state.ticksSinceLastDamage = compound.getInt("ticksSinceLastDamage");
            state.hasBurst = compound.getBoolean("hasBurst");

            if (compound.contains("lastKnownPos", Tag.TAG_COMPOUND)) {
                state.lastKnownPos = NbtUtils.readBlockPos(compound.getCompound("lastKnownPos"));
            }

            InfectionManager.restore(uuid, state);
        }
    }
}
