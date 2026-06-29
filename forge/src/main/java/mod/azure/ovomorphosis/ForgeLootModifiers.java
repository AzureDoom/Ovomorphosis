package mod.azure.ovomorphosis;

import com.mojang.serialization.Codec;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ForgeLootModifiers {

    public static final DeferredRegister<Codec<? extends IGlobalLootModifier>> GLOBAL_LOOT_MODIFIERS =
        DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, CommonMod.MOD_ID);

    public static final RegistryObject<Codec<SurvivorNoteLootModifier>> SURVIVOR_NOTE =
        GLOBAL_LOOT_MODIFIERS.register("survivor_note", () -> SurvivorNoteLootModifier.CODEC);

    private ForgeLootModifiers() {}
}
