package mod.azure.ovomorphosis;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraftforge.common.world.ModifiableStructureInfo;
import net.minecraftforge.common.world.StructureModifier;
import net.minecraftforge.common.world.StructureSettingsBuilder;

import java.util.List;

public record AddStructureSpawnsModifier(
    HolderSet<Structure> structures,
    List<MobSpawnSettings.SpawnerData> spawners
) implements StructureModifier {

    public static final Codec<AddStructureSpawnsModifier> CODEC = RecordCodecBuilder.create(
        instance -> instance.group(
            RegistryCodecs.homogeneousList(Registries.STRUCTURE)
                .fieldOf("structures")
                .forGetter(AddStructureSpawnsModifier::structures),

            MobSpawnSettings.SpawnerData.CODEC.listOf()
                .fieldOf("spawners")
                .forGetter(AddStructureSpawnsModifier::spawners)
        ).apply(instance, AddStructureSpawnsModifier::new)
    );

    @Override
    public void modify(
        Holder<Structure> structure,
        Phase phase,
        ModifiableStructureInfo.StructureInfo.Builder builder
    ) {
        if (phase != Phase.ADD || !this.structures.contains(structure)) {
            return;
        }

        StructureSettingsBuilder settingsBuilder = builder.getStructureSettings();

        for (MobSpawnSettings.SpawnerData spawner : this.spawners) {
            EntityType<?> entityType = spawner.type;
            settingsBuilder
                .getOrAddSpawnOverrides(entityType.getCategory())
                .addSpawn(spawner);
        }
    }

    @Override
    public Codec<? extends StructureModifier> codec() {
        return ModStructureModifierSerializers.ADD_STRUCTURE_SPAWNS.get();
    }
}
