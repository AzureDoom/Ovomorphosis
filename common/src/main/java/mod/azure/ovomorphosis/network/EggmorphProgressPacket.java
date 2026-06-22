package mod.azure.ovomorphosis.network;

import mod.azure.azurelib.common.network.AbstractPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.client.layer.EggmorphRenderState;

/**
 * Server → client packet sent every 10 ticks per eggmorphing entity. Payload: int entityId, float progress [0.0 – 1.0].
 * A progress of 0 signals the client to clear the entry (escape or completion).
 */
public class EggmorphProgressPacket implements AbstractPacket {

    public static final ResourceLocation ID = CommonMod.modResource(
        "eggmorph_progress"
    );

    public static final CustomPacketPayload.Type<EggmorphProgressPacket> TYPE =
        new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, EggmorphProgressPacket> CODEC =
        StreamCodec.of(
            (buf, pkt) -> {
                buf.writeInt(pkt.entityId);
                buf.writeFloat(pkt.progress);
            },
            buf -> new EggmorphProgressPacket(buf.readInt(), buf.readFloat())
        );

    private final int entityId;

    private final float progress;

    public EggmorphProgressPacket(int entityId, float progress) {
        this.entityId = entityId;
        this.progress = progress;
    }

    @Override
    public void handle() {
        var mc = Minecraft.getInstance();
        if (mc.level == null)
            return;
        if (progress <= 0f) {
            EggmorphRenderState.clear(entityId);
        } else {
            EggmorphRenderState.set(entityId, progress);
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
