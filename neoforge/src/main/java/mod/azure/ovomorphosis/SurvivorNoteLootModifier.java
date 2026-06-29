package mod.azure.ovomorphosis;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import org.jetbrains.annotations.NotNull;

import mod.azure.ovomorphosis.items.SurvivorNoteBook;
import mod.azure.ovomorphosis.loot.OvomorphosisLootTables;

public class SurvivorNoteLootModifier extends LootModifier {

    public static final MapCodec<SurvivorNoteLootModifier> CODEC =
        RecordCodecBuilder.mapCodec(
            instance -> codecStart(instance).apply(instance, SurvivorNoteLootModifier::new)
        );

    protected SurvivorNoteLootModifier(LootItemCondition[] conditions) {
        super(conditions);
    }

    @Override
    protected @NotNull ObjectArrayList<ItemStack> doApply(
        @NotNull ObjectArrayList<ItemStack> generatedLoot,
        LootContext context
    ) {
        var tableId = context.getQueriedLootTableId();

        if (!OvomorphosisLootTables.shouldInjectSurvivorNote(tableId)) {
            return generatedLoot;
        }

        if (context.getRandom().nextFloat() < 0.5F) {
            generatedLoot.add(SurvivorNoteBook.create());
        }

        return generatedLoot;
    }

    @Override
    public @NotNull MapCodec<? extends IGlobalLootModifier> codec() {
        return NeoForgeLootModifiers.SURVIVOR_NOTE.get();
    }
}
