package mod.azure.ovomorphosis;

import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.SetComponentsFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;

import mod.azure.ovomorphosis.items.SurvivorNoteBook;
import mod.azure.ovomorphosis.loot.OvomorphosisLootTables;

public final class FabricLootInjects {

    private FabricLootInjects() {}

    public static void init() {
        LootTableEvents.MODIFY.register((key, tableBuilder, source, registries) -> {
            if (!source.isBuiltin()) {
                return;
            }

            var id = key.location();

            if (!OvomorphosisLootTables.shouldInjectSurvivorNote(id)) {
                return;
            }

            var bookContent = SurvivorNoteBook.create()
                .get(DataComponents.WRITTEN_BOOK_CONTENT);

            if (bookContent == null) {
                return;
            }

            tableBuilder.withPool(
                LootPool.lootPool()
                    .when(LootItemRandomChanceCondition.randomChance(0.5F))
                    .add(
                        LootItem.lootTableItem(Items.WRITTEN_BOOK)
                            .apply(
                                SetComponentsFunction.setComponent(
                                    DataComponents.WRITTEN_BOOK_CONTENT,
                                    bookContent
                                )
                            )
                    )
            );
        });
    }
}
