package mod.azure.ovomorphosis.client.facehugger;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.Util;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mod.azure.ovomorphosis.CommonMod;

// @formatter:off
/**
 * Datapack-driven head table: geometry and attach offsets for a host entity, defined together.
 * <p>
 * Datapack location: {@code data/<namespace>/ovomorphosis_head_data/<name>.json}
 *
 * <pre>{@code
 * {
 *   "entities": ["minecraft:cow", "minecraft:mooshroom"],
 *   "size":     [8, 8, 6],
 *   "position": [-4, 16, -14],
 *   "pivot":    [0, 20, -8],
 *   "vertical_offset": "-size_y / 2",
 *   "face_offset":     "size_z"
 * }
 * }</pre>
 * <p>
 * {@code size}, {@code position} and {@code pivot} are model pixels (Blockbench units) and are converted to blocks
 * (1/16) on load, so the {@code size_*} / {@code pivot_*} expression variables are in blocks. See
 * {@link OffsetExpression} for the expression grammar.
 * <p>
 * Target resolution, in order: {@code "entities"} (list), {@code "entity"} (single id), otherwise
 * {@code minecraft:<file path>}. Unknown entity ids are skipped with a warning so compat files for mods that aren't
 * installed don't break the rest of the file.
 * <p>
 * Same JSON format on every branch (1.20.1, 1.21.1, 26.x), so one set of data files works everywhere.
 * <p>
 * Credit to Boston for the original head data and offset tables.
 */
// @formatter:on
public record EntityHeadData(
    Vec3 size,
    Vec3 position,
    Vec3 pivot,
    OffsetExpression verticalOffset,
    OffsetExpression faceOffset
) {

    private static final double PIXEL = 1 / 16.0;

    private static final Codec<Vec3> VEC3_CODEC = Codec.DOUBLE.listOf()
        .comapFlatMap(
            list -> Util.fixedSize(list, 3).map(l -> new Vec3(l.get(0), l.get(1), l.get(2))),
            vec -> List.of(vec.x, vec.y, vec.z)
        );

    public static final Codec<EntityHeadData> CODEC = RecordCodecBuilder.<EntityHeadData>create(
        instance -> instance.group(
            VEC3_CODEC.fieldOf("size").forGetter(EntityHeadData::size),
            VEC3_CODEC.fieldOf("position").forGetter(EntityHeadData::position),
            VEC3_CODEC.fieldOf("pivot").forGetter(EntityHeadData::pivot),
            OffsetExpression.CODEC.fieldOf("vertical_offset").forGetter(EntityHeadData::verticalOffset),
            OffsetExpression.CODEC.fieldOf("face_offset").forGetter(EntityHeadData::faceOffset)
        ).apply(instance, EntityHeadData::new)
    ).xmap(raw -> raw.scaleGeometry(PIXEL), data -> data.scaleGeometry(16.0));

    public static volatile Map<EntityType<?>, EntityHeadData> ENTITY_HEAD_DATA_BY_TYPE = Map.of();

    @Nullable
    public static EntityHeadData get(EntityType<?> type) {
        return ENTITY_HEAD_DATA_BY_TYPE.get(type);
    }

    @Nullable
    public static OffsetResult resolve(EntityType<?> hostType, Entity parasite) {
        var data = get(hostType);
        return data == null ? null : data.resolveOffsets(parasite);
    }

    public OffsetResult resolveOffsets(Entity parasite) {
        var ctx = new OffsetExpression.OffsetContext(
            size.x,
            size.y,
            size.z,
            pivot.x,
            pivot.y,
            pivot.z,
            parasite.getBbHeight(),
            parasite.getBbWidth()
        );
        return new OffsetResult(verticalOffset.evaluate(ctx), faceOffset.evaluate(ctx));
    }

    public void write(FriendlyByteBuf buf) {
        writeVec3(buf, size);
        writeVec3(buf, position);
        writeVec3(buf, pivot);
        buf.writeUtf(verticalOffset.source());
        buf.writeUtf(faceOffset.source());
    }

    public static EntityHeadData read(FriendlyByteBuf buf) {
        return new EntityHeadData(
            readVec3(buf),
            readVec3(buf),
            readVec3(buf),
            OffsetExpression.parse(buf.readUtf()),
            OffsetExpression.parse(buf.readUtf())
        );
    }

    private static void writeVec3(FriendlyByteBuf buf, Vec3 vec) {
        buf.writeDouble(vec.x);
        buf.writeDouble(vec.y);
        buf.writeDouble(vec.z);
    }

    private static Vec3 readVec3(FriendlyByteBuf buf) {
        return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    private EntityHeadData scaleGeometry(double factor) {
        return new EntityHeadData(
            size.scale(factor),
            position.scale(factor),
            pivot.scale(factor),
            verticalOffset,
            faceOffset
        );
    }

    public record OffsetResult(
        double vertical,
        double face
    ) {}

    public static class ReloadListener extends SimpleJsonResourceReloadListener {

        public static final String DIRECTORY = "ovomorphosis_head_data";

        public ReloadListener() {
            super(new GsonBuilder().create(), DIRECTORY);
        }

        @Override
        protected void apply(
            Map<ResourceLocation, JsonElement> jsons,
            @NotNull ResourceManager rm,
            @NotNull ProfilerFiller profiler
        ) {
            Map<EntityType<?>, EntityHeadData> map = new HashMap<>();
            for (var entry : jsons.entrySet()) {
                var file = entry.getKey();
                try {
                    var obj = GsonHelper.convertToJsonObject(entry.getValue(), "head_data");
                    var data = CODEC.parse(JsonOps.INSTANCE, obj).getOrThrow(false, error -> {});

                    for (var entityId : readTargets(file, obj)) {
                        var type = BuiltInRegistries.ENTITY_TYPE.getOptional(entityId);
                        if (type.isEmpty()) {
                            CommonMod.LOGGER.warn("Head data {}: unknown entity type {}, skipping", file, entityId);
                            continue;
                        }
                        if (map.put(type.get(), data) != null) {
                            CommonMod.LOGGER.warn(
                                "Head data {}: {} defined more than once, overriding",
                                file,
                                entityId
                            );
                        }
                    }
                } catch (Exception e) {
                    CommonMod.LOGGER.error("Failed to load head data {}: {}", file, e.getMessage());
                }
            }
            ENTITY_HEAD_DATA_BY_TYPE = Map.copyOf(map);
            CommonMod.LOGGER.info("Loaded {} entity head data entries", map.size());
        }

        private static List<ResourceLocation> readTargets(ResourceLocation file, JsonObject obj) {
            if (obj.has("entities")) {
                List<ResourceLocation> ids = new ArrayList<>();
                for (var element : GsonHelper.getAsJsonArray(obj, "entities")) {
                    ids.add(new ResourceLocation(GsonHelper.convertToString(element, "entities[]")));
                }
                return ids;
            }
            if (obj.has("entity")) {
                return List.of(new ResourceLocation(GsonHelper.getAsString(obj, "entity")));
            }
            return List.of(new ResourceLocation("minecraft", file.getPath()));
        }
    }
}
