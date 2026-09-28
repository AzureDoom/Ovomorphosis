package mod.azure.ovomorphosis.structuremodifier;

import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jspecify.annotations.NonNull;

import java.util.Map;

import mod.azure.ovomorphosis.CommonMod;

@SuppressWarnings("unused")
public final class StructureModifierManager extends SimpleJsonResourceReloadListener<StructureModifierEntry> {

    public static final Identifier ID = CommonMod.modResource("structure_modifier");

    public static final StructureModifierManager INSTANCE = new StructureModifierManager();

    private Map<Identifier, StructureModifierEntry> entries = Map.of();

    private StructureModifierManager() {
        super(StructureModifierEntry.CODEC, FileToIdConverter.json("fabric/structure_modifier"));
    }

    @Override
    protected void apply(
        @NonNull Map<Identifier, StructureModifierEntry> parsed,
        @NonNull ResourceManager resourceManager,
        @NonNull ProfilerFiller profiler
    ) {
        this.entries = Map.copyOf(parsed);
        CommonMod.LOGGER.info("Loaded {} structure modifier entries", entries.size());
    }

    public Map<Identifier, StructureModifierEntry> getEntries() {
        return entries;
    }
}
