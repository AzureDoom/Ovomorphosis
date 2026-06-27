package mod.azure.ovomorphosis.network;

import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.PacketDistributor;

public final class ForgeNetworkDispatcher implements NetworkDispatcher {

    @Override
    public void sendEggmorphProgress(int entityId, float progress, Entity entity) {
        ForgeNetworkHandler.CHANNEL.send(
            PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> entity),
            new EggmorphProgressPacket(entityId, progress)
        );
    }
}
