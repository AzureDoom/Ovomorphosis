package mod.azure.ovomorphosis.structuremodifier;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import mod.azure.ovomorphosis.CommonMod;

public final class StructureModifierManager extends SimpleJsonResourceReloadListener implements IdentifiableResourceReloadListener {

    public static final StructureModifierManager INSTANCE = new StructureModifierManager();

    private Map<ResourceLocation, StructureModifierEntry> entries = Collections.emptyMap();

    private StructureModifierManager() {
        super(new Gson(), "fabric/structure_modifier");
    }

    @Override
    protected void apply(
        Map<ResourceLocation, JsonElement> object,
        ResourceManager resourceManager,
        ProfilerFiller profiler
    ) {
        Map<ResourceLocation, StructureModifierEntry> parsed = new HashMap<>();

        for (var pair : object.entrySet()) {
            var id = pair.getKey();
            var json = pair.getValue();

            var result = StructureModifierEntry.CODEC.parse(
                JsonOps.INSTANCE,
                json
            );

            result.resultOrPartial(
                error -> CommonMod.LOGGER.error(
                    "Failed to parse structure modifier {}: {}",
                    id,
                    error
                )
            ).ifPresent(entry -> parsed.put(id, entry));
        }

        this.entries = Map.copyOf(parsed);
    }

    public Map<ResourceLocation, StructureModifierEntry> getEntries() {
        return entries;
    }

    @Override
    public ResourceLocation getFabricId() {
        return CommonMod.modResource("structure_modifier");
    }
}
