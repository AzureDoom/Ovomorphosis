package mod.azure.ovomorphosis.util;

import net.minecraft.server.level.ServerPlayer;

import mod.azure.ovomorphosis.CommonMod;

public class AdvancementUtils {

    public static void triggerAdvancement(ServerPlayer serverPlayer, String advancementId) {
        var advancement = serverPlayer.server.getAdvancements()
            .getAdvancement(CommonMod.modResource(advancementId));
        if (
            advancement != null
                && !serverPlayer.getAdvancements().getOrStartProgress(advancement).isDone()
        ) {
            for (
                var s : serverPlayer.getAdvancements()
                    .getOrStartProgress(advancement)
                    .getRemainingCriteria()
            ) {
                serverPlayer.getAdvancements().award(advancement, s);
            }
        }
    }
}
