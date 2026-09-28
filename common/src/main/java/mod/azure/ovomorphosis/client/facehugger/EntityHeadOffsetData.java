package mod.azure.ovomorphosis.client.facehugger;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import mod.azure.ovomorphosis.CommonMod;

/**
 * Datapack-driven version of the original head-offset table.
 * <p>
 * Datapack location: {@code
 * data/<namespace>/ovomorphosis_head_offsets/<entity>.json}
 * <p>
 * Each JSON file looks like:
 *
 * <pre>{@code { "entity":
 * "minecraft:cow", "vertical_offset": "-size_y", "face_offset": "size_z + (size_z / 2) + parasite_height" } }</pre>
 * <p>
 * See {@link OffsetExpression} for the supported expression grammar.
 * <p>
 * Credit to Boston for the original head-offset table code.
 */
public record EntityHeadOffsetData(
    OffsetExpression verticalOffset,
    OffsetExpression faceOffset
) {

    public static final MapCodec<EntityHeadOffsetData> MAP_CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
            OffsetExpression.CODEC.fieldOf("vertical_offset").forGetter(EntityHeadOffsetData::verticalOffset),
            OffsetExpression.CODEC.fieldOf("face_offset").forGetter(EntityHeadOffsetData::faceOffset)
        ).apply(instance, EntityHeadOffsetData::new)
    );

    public static final Codec<EntityHeadOffsetData> CODEC = MAP_CODEC.codec();

    public static volatile Map<EntityType<?>, EntityHeadOffsetData> ENTITY_HEAD_OFFSET_DATA_BY_TYPE = Map.of();

    public static OffsetResult resolve(EntityType<?> hostType, EntityHeadData head, Entity parasite) {
        var data = ENTITY_HEAD_OFFSET_DATA_BY_TYPE.get(hostType);
        if (data == null) {
            return null;
        }
        var ctx = new OffsetExpression.OffsetContext(
            head.size().x,
            head.size().y,
            head.size().z,
            head.pivot().x,
            head.pivot().y,
            head.pivot().z,
            parasite.getBbHeight(),
            parasite.getBbWidth()
        );
        return new OffsetResult(data.verticalOffset.evaluate(ctx), data.faceOffset.evaluate(ctx));
    }

    public record OffsetResult(
        double vertical,
        double face
    ) {}

    /**
     * One datapack file: the offset data plus an optional explicit {@code "entity"} target. Both live at the top level
     * of the same JSON object, so the offset fields are pulled in via {@link #MAP_CODEC} rather than nested.
     */
    public record HeadOffsetFile(
        Optional<Identifier> entity,
        EntityHeadOffsetData data
    ) {

        public static final Codec<HeadOffsetFile> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                Identifier.CODEC.optionalFieldOf("entity").forGetter(HeadOffsetFile::entity),
                MAP_CODEC.forGetter(HeadOffsetFile::data)
            ).apply(instance, HeadOffsetFile::new)
        );
    }

    public static class ReloadListener extends SimpleJsonResourceReloadListener<HeadOffsetFile> {

        public ReloadListener() {
            super(HeadOffsetFile.CODEC, FileToIdConverter.json("ovomorphosis_head_offsets"));
        }

        @Override
        protected void apply(
            @NonNull Map<Identifier, HeadOffsetFile> files,
            @NonNull ResourceManager rm,
            @NonNull ProfilerFiller profiler
        ) {
            Map<EntityType<?>, EntityHeadOffsetData> map = new HashMap<>();
            for (var entry : files.entrySet()) {
                var file = entry.getKey();
                var parsed = entry.getValue();

                // Same fallback as before: no "entity" field means the file name is a vanilla entity id.
                var entityId = parsed.entity()
                    .orElseGet(() -> Identifier.withDefaultNamespace(file.getPath()));

                var type = BuiltInRegistries.ENTITY_TYPE.getOptional(entityId);
                if (type.isEmpty()) {
                    CommonMod.LOGGER.error("Failed to load head offset {}: unknown entity type {}", file, entityId);
                    continue;
                }

                map.put(type.get(), parsed.data());
            }
            ENTITY_HEAD_OFFSET_DATA_BY_TYPE = Map.copyOf(map);
            CommonMod.LOGGER.info("Loaded {} entity head offset entries", map.size());
        }
    }
}
