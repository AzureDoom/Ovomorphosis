package mod.azure.ovomorphosis.network;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

public final class ForgeNetworkDispatcher implements NetworkDispatcher {

    @Override
    public void sendEggmorphProgress(int entityId, float progress, Entity entity) {
        ForgeNetworkHandler.CHANNEL.send(
            PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> entity),
            new EggmorphProgressPacket(entityId, progress)
        );
    }

    public static void sendHeadData(@Nullable ServerPlayer player) {
        var packet = HeadDataSyncPacket.fromCurrent();
        if (player != null) {
            ForgeNetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
        } else {
            ForgeNetworkHandler.CHANNEL.send(PacketDistributor.ALL.noArg(), packet);
        }
    }
}
