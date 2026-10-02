package mod.azure.ovomorphosis.api.scanner;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InfectionScanners {

    private static final List<InfectionScanProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    private InfectionScanners() {}

    public static void register(InfectionScanProvider provider) {
        PROVIDERS.add(provider);
    }

    public static List<ScanReading> collect(LivingEntity target, Player scanner, boolean detailed) {
        var readings = new ArrayList<ScanReading>();
        for (var provider : PROVIDERS) {
            var reading = provider.scan(target, scanner, detailed);
            if (reading != null) {
                readings.add(reading);
            }
        }
        return readings;
    }
}
