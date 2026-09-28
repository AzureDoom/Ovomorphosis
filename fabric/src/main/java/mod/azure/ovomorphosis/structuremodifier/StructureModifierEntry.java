package mod.azure.ovomorphosis.structuremodifier;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.StringRepresentable;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.structure.StructureSpawnOverride;

import java.util.List;

/**
 * Fabric-side structure modifier, shaped to match NeoForge's {@code neoforge:add_spawns}:
 *
 * <pre>{@code { "type":
 * "ovomorphosis:add_spawns", "structures": "#ovomorphosis:infestable_structures", "spawners": [ { "type":
 * "ovomorphosis:ovomorph", "weight": 2, "count": 1 } ] } }</pre>
 * <p>
 * {@code structures} and {@code spawners} each accept a single value or a list. Each spawner uses vanilla's own
 * flattened weighted spawner format, so {@code count} is a full int provider ({@code 1}, or
 * {@code {"type": "minecraft:uniform", "min_inclusive": 1, "max_inclusive": 3}}). The mob category comes from each
 * entity type, as it does on NeoForge. The top-level {@code "type"} key is ignored and is there only for readability.
 * {@code bounding_box} is optional ({@code "full"} or {@code "piece"}) and only applies when a structure has no
 * existing override for that category.
 */
public record StructureModifierEntry(
    List<String> structures,
    StructureSpawnOverride.BoundingBoxType boundingBox,
    List<Weighted<MobSpawnSettings.SpawnerData>> spawners
) {

    public static final Codec<StructureModifierEntry> CODEC = RecordCodecBuilder.create(
        instance -> instance.group(
            ExtraCodecs.compactListCodec(Codec.STRING)
                .fieldOf("structures")
                .forGetter(StructureModifierEntry::structures),
            StringRepresentable.fromEnum(StructureSpawnOverride.BoundingBoxType::values)
                .optionalFieldOf("bounding_box", StructureSpawnOverride.BoundingBoxType.STRUCTURE)
                .forGetter(StructureModifierEntry::boundingBox),
            ExtraCodecs.compactListCodec(Weighted.codec(MobSpawnSettings.SpawnerData.CODEC))
                .fieldOf("spawners")
                .forGetter(StructureModifierEntry::spawners)
        ).apply(instance, StructureModifierEntry::new)
    );
}
