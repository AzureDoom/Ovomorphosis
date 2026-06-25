package mod.azure.ovomorphosis;

import com.mojang.serialization.Codec;
import net.minecraftforge.common.world.StructureModifier;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModStructureModifierSerializers {

    public static final DeferredRegister<Codec<? extends StructureModifier>> STRUCTURE_MODIFIER_SERIALIZERS =
        DeferredRegister.create(ForgeRegistries.Keys.STRUCTURE_MODIFIER_SERIALIZERS, CommonMod.MOD_ID);

    public static final RegistryObject<Codec<? extends StructureModifier>> ADD_STRUCTURE_SPAWNS =
        STRUCTURE_MODIFIER_SERIALIZERS.register(
            "add_structure_spawns",
            () -> AddStructureSpawnsModifier.CODEC
        );

    private ModStructureModifierSerializers() {}
}
