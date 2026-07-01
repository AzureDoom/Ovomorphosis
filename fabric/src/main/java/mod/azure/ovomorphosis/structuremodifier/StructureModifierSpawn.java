package mod.azure.ovomorphosis.structuremodifier;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

public record StructureModifierSpawn(
    ResourceLocation entity,
    int weight,
    int minCount,
    int maxCount
) {

    public static final Codec<StructureModifierSpawn> CODEC = RecordCodecBuilder.create(
        instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("entity").forGetter(StructureModifierSpawn::entity),
            Codec.INT.fieldOf("weight").forGetter(StructureModifierSpawn::weight),
            Codec.INT.fieldOf("min_count").forGetter(StructureModifierSpawn::minCount),
            Codec.INT.fieldOf("max_count").forGetter(StructureModifierSpawn::maxCount)
        ).apply(instance, StructureModifierSpawn::new)
    );
}
