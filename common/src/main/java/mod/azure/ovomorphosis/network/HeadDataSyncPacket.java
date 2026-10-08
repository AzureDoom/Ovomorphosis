package mod.azure.ovomorphosis.network;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

import mod.azure.ovomorphosis.CommonMod;
import mod.azure.ovomorphosis.client.facehugger.EntityHeadData;

public record HeadDataSyncPacket(
    Map<EntityType<?>, EntityHeadData> heads
) implements CustomPacketPayload {

    public static final Type<HeadDataSyncPacket> TYPE = new Type<>(CommonMod.modResource("head_data_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HeadDataSyncPacket> CODEC = ByteBufCodecs
        .<RegistryFriendlyByteBuf, EntityType<?>, EntityHeadData, Map<EntityType<?>, EntityHeadData>>map(
            HashMap::new,
            ByteBufCodecs.registry(Registries.ENTITY_TYPE),
            EntityHeadData.STREAM_CODEC
        )
        .map(HeadDataSyncPacket::new, HeadDataSyncPacket::heads);

    public static HeadDataSyncPacket fromCurrent() {
        return new HeadDataSyncPacket(EntityHeadData.ENTITY_HEAD_DATA_BY_TYPE);
    }

    public void handle() {
        EntityHeadData.ENTITY_HEAD_DATA_BY_TYPE = Map.copyOf(heads);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
