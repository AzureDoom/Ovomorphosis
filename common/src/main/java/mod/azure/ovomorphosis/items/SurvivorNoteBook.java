package mod.azure.ovomorphosis.items;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.List;

public final class SurvivorNoteBook {

    private SurvivorNoteBook() {}

    public static ItemStack create() {
        var stack = new ItemStack(Items.WRITTEN_BOOK);

        stack.set(
            DataComponents.WRITTEN_BOOK_CONTENT,
            new WrittenBookContent(
                Filterable.passThrough("Field Note"),
                "Unknown Survivor",
                0,
                List.of(
                    page("""
                        The resin keeps spreading.

                        The tracker only screams when they move. If it starts ticking, something is already close.
                        """),
                    page("""
                        The scanner says the animals are carrying something.

                        Check yourself. Check anything that was grabbed. Do not wait for the pain.
                        """),
                    page("""
                        The sprayer keeps them back, but not for long.

                        Fire buys time. It does not make the hive forget you.
                        """),
                    page("""
                        Do not let the eggs open.
                        Do not sleep near the hive.
                        Do not bring anything alive back with you.
                        """)
                ),
                true
            )
        );

        return stack;
    }

    private static Filterable<Component> page(String text) {
        return Filterable.passThrough(Component.literal(text.stripIndent()));
    }
}
