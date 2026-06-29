package mod.azure.ovomorphosis;

import net.fabricmc.fabric.api.loot.v2.LootTableEvents;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.SetNbtFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;

import mod.azure.ovomorphosis.items.SurvivorNoteBook;
import mod.azure.ovomorphosis.loot.OvomorphosisLootTables;

@SuppressWarnings("deprecation")
public final class FabricLootInjects {

    private FabricLootInjects() {}

    public static void init() {
        LootTableEvents.MODIFY.register((resourceManager, lootManager, id, tableBuilder, source) -> {
            if (!source.isBuiltin()) {
                return;
            }

            if (!OvomorphosisLootTables.shouldInjectSurvivorNote(id)) {
                return;
            }

            var survivorNoteTag = SurvivorNoteBook.create().getOrCreateTag().copy();

            tableBuilder.withPool(
                LootPool.lootPool()
                    .when(LootItemRandomChanceCondition.randomChance(0.5F))
                    .add(
                        LootItem.lootTableItem(Items.WRITTEN_BOOK)
                            .apply(SetNbtFunction.setTag(survivorNoteTag))
                    )
            );
        });
    }
}
