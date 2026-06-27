package mod.azure.ovomorphosis.network;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public final class FabricNetworkDispatcher implements NetworkDispatcher {

    @Override
    public void sendEggmorphProgress(int entityId, float progress, Entity entity) {
        var packet = new EggmorphProgressPacket(entityId, progress);
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        packet.encode(buf);

        var vanillaPacket = ServerPlayNetworking.createS2CPacket(EggmorphProgressPacket.ID, buf);

        for (var player : PlayerLookup.tracking(entity)) {
            player.connection.send(vanillaPacket);
        }
        if (entity instanceof ServerPlayer self) {
            self.connection.send(vanillaPacket);
        }
    }
}
