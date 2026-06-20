package mod.azure.xenogenesis.client.layer;

import java.util.HashMap;
import java.util.Map;

/**
 * Client-side store for eggmorph progress values keyed by entity ID. Written by
 * {@link mod.azure.xenogenesis.network.EggmorphProgressPacket#handle()} and read by {@link EggmorphResinLayer}. Both
 * accesses happen on the client main/render thread so no synchronisation is needed.
 */
public final class EggmorphRenderState {

    private static final Map<Integer, Float> PROGRESS = new HashMap<>();

    private EggmorphRenderState() {}

    public static void set(int entityId, float progress) {
        PROGRESS.put(entityId, progress);
    }

    public static float get(int entityId) {
        return PROGRESS.getOrDefault(entityId, 0f);
    }

    public static boolean isEggmorphing(int entityId) {
        return PROGRESS.containsKey(entityId);
    }

    public static void clear(int entityId) {
        PROGRESS.remove(entityId);
    }
}
