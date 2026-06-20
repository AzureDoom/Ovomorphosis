package mod.azure.xenogenesis.data;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

import mod.azure.xenogenesis.blocks.EggmorphTracker;
import mod.azure.xenogenesis.infection.InfectionManager;
import mod.azure.xenogenesis.infection.InfectionState;

public final class XenogenesisSavedData extends SavedData {

    private static final String DATA_NAME = "xenogenesis_data";

    public static XenogenesisSavedData get(ServerLevel level) {
        var overworld = level.getServer().overworld();
        return overworld.getDataStorage()
            .computeIfAbsent(
                new SavedData.Factory<>(
                    XenogenesisSavedData::createEmpty,
                    (tag, provider) -> load(tag, overworld),
                    net.minecraft.util.datafix.DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES
                ),
                DATA_NAME
            );
    }

    private static XenogenesisSavedData createEmpty() {
        return new XenogenesisSavedData();
    }

    @Override
    public @NotNull CompoundTag save(CompoundTag tag, HolderLookup.@NotNull Provider provider) {
        tag.put("eggmorph", saveEggmorph());
        tag.put("infections", saveInfections());
        return tag;
    }

    private static XenogenesisSavedData load(CompoundTag tag, ServerLevel level) {
        var data = new XenogenesisSavedData();
        EggmorphTracker.clearAll();
        InfectionManager.clearAll();
        if (tag.contains("eggmorph", Tag.TAG_LIST))
            loadEggmorph(tag.getList("eggmorph", Tag.TAG_COMPOUND), level);
        if (tag.contains("infections", Tag.TAG_LIST)) {
            loadInfections(tag.getList("infections", Tag.TAG_COMPOUND));
        }
        return data;
    }

    private static ListTag saveEggmorph() {
        var list = new ListTag();
        for (var entry : EggmorphTracker.snapshotForSave().entrySet()) {
            var compound = new CompoundTag();
            compound.put("pos", NbtUtils.writeBlockPos(entry.getKey()));

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
            var pos = NbtUtils.readBlockPos(compound, "pos").orElse(null);
            if (pos == null)
                continue;

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
            if (entry.getValue().lastKnownPos != null)
                compound.put("lastKnownPos", NbtUtils.writeBlockPos(entry.getValue().lastKnownPos));
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
            if (compound.contains("lastKnownPos"))
                state.lastKnownPos = NbtUtils.readBlockPos(compound, "lastKnownPos")
                    .orElse(BlockPos.ZERO);
            InfectionManager.restore(uuid, state);
        }
    }
}
