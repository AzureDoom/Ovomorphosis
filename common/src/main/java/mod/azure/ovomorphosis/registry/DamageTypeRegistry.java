package mod.azure.ovomorphosis.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.level.Level;

import mod.azure.ovomorphosis.CommonMod;

public final class DamageTypeRegistry {

    private DamageTypeRegistry() {}

    public static final ResourceKey<DamageType> XENOMORPH_INFECTION = ResourceKey.create(
        Registries.DAMAGE_TYPE,
        CommonMod.modResource("xenomorph_infection")
    );

    public static final ResourceKey<DamageType> EGGMORPH = ResourceKey.create(
        Registries.DAMAGE_TYPE,
        CommonMod.modResource("eggmorph")
    );

    public static DamageSource of(Level level, ResourceKey<DamageType> source) {
        return new DamageSource(
            level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(source)
        );
    }
}
