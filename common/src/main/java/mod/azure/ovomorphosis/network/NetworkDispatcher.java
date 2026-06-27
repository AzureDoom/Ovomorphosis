package mod.azure.ovomorphosis.network;

import net.minecraft.world.entity.Entity;

public interface NetworkDispatcher {

    void sendEggmorphProgress(int entityId, float progress, Entity entity);

    final class Holder {

        public static NetworkDispatcher INSTANCE = null;
    }

    static void send(int entityId, float progress, Entity entity) {
        Holder.INSTANCE.sendEggmorphProgress(entityId, progress, entity);
    }
}
