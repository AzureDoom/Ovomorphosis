package mod.azure.ovomorphosis.api.scanner;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public record ScanReading(
    Component detail,
    Severity severity
) {

    public enum Severity {

        NOTICE(0, 1.0F, ChatFormatting.YELLOW),
        WARNING(1, 0.7F, ChatFormatting.GOLD),
        CRITICAL(2, 0.5F, ChatFormatting.RED);

        public final int model;

        public final float pitch;

        public final ChatFormatting color;

        Severity(int model, float pitch, ChatFormatting color) {
            this.model = model;
            this.pitch = pitch;
            this.color = color;
        }
    }
}
