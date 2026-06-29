package mod.azure.ovomorphosis;

import com.mojang.serialization.MapCodec;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

public final class NeoForgeLootModifiers {

    public static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> GLOBAL_LOOT_MODIFIERS =
        DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, CommonMod.MOD_ID);

    public static final Supplier<MapCodec<SurvivorNoteLootModifier>> SURVIVOR_NOTE =
        GLOBAL_LOOT_MODIFIERS.register("survivor_note", () -> SurvivorNoteLootModifier.CODEC);

    private NeoForgeLootModifiers() {}
}
