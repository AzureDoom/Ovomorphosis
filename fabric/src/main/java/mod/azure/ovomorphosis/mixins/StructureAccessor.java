package mod.azure.ovomorphosis.mixins;

import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Structure.class)
public interface StructureAccessor {

    @Accessor("settings")
    Structure.StructureSettings ovomorphosis$getSettings();

    @Mutable
    @Accessor("settings")
    void ovomorphosis$setSettings(Structure.StructureSettings settings);
}
