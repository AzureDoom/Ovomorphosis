package mod.azure.ovomorphosis.network;

import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import mod.azure.ovomorphosis.CommonMod;

public final class ForgeNetworkHandler {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        CommonMod.modResource("main"),
        () -> PROTOCOL_VERSION,
        PROTOCOL_VERSION::equals,
        PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    public static void registerMessages() {
        CHANNEL.messageBuilder(
            EggmorphProgressPacket.class,
            packetId++,
            NetworkDirection.PLAY_TO_CLIENT
        )
            .encoder(EggmorphProgressPacket::encode)
            .decoder(EggmorphProgressPacket::new)
            .consumerMainThread((packet, ctx) -> packet.handle())
            .add();
    }

    private ForgeNetworkHandler() {}
}
