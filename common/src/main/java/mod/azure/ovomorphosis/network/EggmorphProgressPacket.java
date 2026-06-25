package mod.azure.ovomorphosis.network;

import mod.azure.azurelib.network.AbstractPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.client.layer.EggmorphRenderState;

public class EggmorphProgressPacket extends AbstractPacket {

    public static final ResourceLocation ID = CommonMod.modResource("eggmorph_progress");

    private final int entityId;

    private final float progress;

    public EggmorphProgressPacket(int entityId, float progress) {
        this.entityId = entityId;
        this.progress = progress;
    }

    public EggmorphProgressPacket(FriendlyByteBuf buf) {
        this.entityId = buf.readInt();
        this.progress = buf.readFloat();
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(this.entityId);
        buf.writeFloat(this.progress);
    }

    @Override
    public void handle() {
        var mc = Minecraft.getInstance();

        if (mc.level == null) {
            return;
        }

        if (this.progress <= 0f) {
            EggmorphRenderState.clear(this.entityId);
        } else {
            EggmorphRenderState.set(this.entityId, this.progress);
        }
    }

    @Override
    public ResourceLocation getPacketID() {
        return ID;
    }
}
