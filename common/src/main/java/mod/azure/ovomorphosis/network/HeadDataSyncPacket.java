package mod.azure.ovomorphosis.network;

import mod.azure.azurelib.network.AbstractPacket;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.HashMap;
import java.util.Map;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.client.facehugger.EntityHeadData;

public class HeadDataSyncPacket extends AbstractPacket {

    public static final ResourceLocation ID = CommonMod.modResource("head_data_sync");

    private final Map<EntityType<?>, EntityHeadData> heads;

    public HeadDataSyncPacket(Map<EntityType<?>, EntityHeadData> heads) {
        this.heads = heads;
    }

    public HeadDataSyncPacket(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        Map<EntityType<?>, EntityHeadData> map = new HashMap<>(count);
        for (int i = 0; i < count; i++) {
            var id = buf.readResourceLocation();
            var data = EntityHeadData.read(buf);
            BuiltInRegistries.ENTITY_TYPE.getOptional(id).ifPresent(type -> map.put(type, data));
        }
        this.heads = map;
    }

    public static HeadDataSyncPacket fromCurrent() {
        return new HeadDataSyncPacket(EntityHeadData.ENTITY_HEAD_DATA_BY_TYPE);
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(heads.size());
        heads.forEach((type, data) -> {
            buf.writeResourceLocation(BuiltInRegistries.ENTITY_TYPE.getKey(type));
            data.write(buf);
        });
    }

    @Override
    public void handle() {
        EntityHeadData.ENTITY_HEAD_DATA_BY_TYPE = Map.copyOf(heads);
    }

    @Override
    public ResourceLocation getPacketID() {
        return ID;
    }
}
