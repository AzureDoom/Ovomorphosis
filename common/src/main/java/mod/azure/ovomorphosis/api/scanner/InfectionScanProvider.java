package mod.azure.ovomorphosis.api.scanner;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

@FunctionalInterface
public interface InfectionScanProvider {

    @Nullable
    ScanReading scan(LivingEntity target, Player scanner, boolean detailed);
}
