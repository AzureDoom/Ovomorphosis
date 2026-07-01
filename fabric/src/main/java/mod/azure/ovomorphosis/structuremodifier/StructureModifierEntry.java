package mod.azure.ovomorphosis.structuremodifier;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.levelgen.structure.StructureSpawnOverride;

import java.util.List;

public record StructureModifierEntry(
    List<String> structures,
    MobCategory category,
    StructureSpawnOverride.BoundingBoxType boundingBox,
    List<StructureModifierSpawn> spawns
) {

    private static final Codec<MobCategory> MOB_CATEGORY_CODEC =
        Codec.STRING.flatXmap(
            name -> {
                for (MobCategory cat : MobCategory.values()) {
                    if (cat.getName().equals(name)) {
                        return DataResult.success(cat);
                    }
                }
                return DataResult.error(() -> "Unknown mob category: " + name);
            },
            cat -> DataResult.success(cat.getName())
        );

    public static final Codec<StructureModifierEntry> CODEC = RecordCodecBuilder.create(
        instance -> instance.group(
            Codec.STRING.listOf().fieldOf("structures").forGetter(StructureModifierEntry::structures),
            MOB_CATEGORY_CODEC.fieldOf("category").forGetter(StructureModifierEntry::category),
            StringRepresentable.fromEnum(StructureSpawnOverride.BoundingBoxType::values)
                .optionalFieldOf("bounding_box", StructureSpawnOverride.BoundingBoxType.STRUCTURE)
                .forGetter(StructureModifierEntry::boundingBox),
            StructureModifierSpawn.CODEC.listOf().fieldOf("spawns").forGetter(StructureModifierEntry::spawns)
        ).apply(instance, StructureModifierEntry::new)
    );
}
