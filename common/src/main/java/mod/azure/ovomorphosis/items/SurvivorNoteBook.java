package mod.azure.ovomorphosis.items;

import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class SurvivorNoteBook {

    private SurvivorNoteBook() {}

    public static ItemStack create() {
        var stack = new ItemStack(Items.WRITTEN_BOOK);
        var tag = stack.getOrCreateTag();

        tag.putString("title", "Field Note");
        tag.putString("author", "Unknown Survivor");
        tag.putInt("generation", 0);

        var pages = new ListTag();

        pages.add(page("""
            The resin keeps spreading.

            The tracker only screams when they move. If it starts ticking, something is already close.
            """));

        pages.add(page("""
            The scanner says the animals are carrying something.

            Check yourself. Check anything that was grabbed. Do not wait for the pain.
            """));

        pages.add(page("""
            The sprayer keeps them back, but not for long.

            Fire buys time. It does not make the hive forget you.
            """));

        pages.add(page("""
            Do not let the eggs open.
            Do not sleep near the hive.
            Do not bring anything alive back with you.
            """));

        tag.put("pages", pages);
        return stack;
    }

    private static StringTag page(String text) {
        return StringTag.valueOf(Component.Serializer.toJson(Component.literal(text)));
    }
}
