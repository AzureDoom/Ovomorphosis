package mod.azure.xenogenesis.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.level.Level;

public final class DamageTypeRegistry {

    private DamageTypeRegistry() {}

    public static final ResourceKey<DamageType> XENOMORPH_INFECTION = ResourceKey.create(
        Registries.DAMAGE_TYPE,
        ResourceLocation.fromNamespaceAndPath("xenogenesis", "xenomorph_infection")
    );

    public static DamageSource of(Level level) {
        return new DamageSource(
            level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(XENOMORPH_INFECTION)
        );
    }
}
