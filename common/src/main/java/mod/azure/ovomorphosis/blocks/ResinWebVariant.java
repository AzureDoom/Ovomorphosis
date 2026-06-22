package mod.azure.ovomorphosis.blocks;

import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.NotNull;

public enum ResinWebVariant implements StringRepresentable {

    ONE("one"),
    TWO("two"),
    THREE("three"),
    FOUR("four"),
    FIVE("five"),
    SIX("six");

    public final String dirName;

    ResinWebVariant(String dirName) {
        this.dirName = dirName;
    }

    @Override
    public @NotNull String getSerializedName() {
        return dirName;
    }
}
