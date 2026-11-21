package com.modserver.network;

import com.modserver.ModServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.UUID;

/** An inspection or execution result. An offer pins both participants, the price, and the defense window. */
public record MapTerritoryInfo(ResourceLocation dimension, int x, int z, String owner, String detail,
                              long deadline, Optional<Offer> offer) implements CustomPacketPayload {
    public record Offer(UUID challenger, UUID defender, long price, long window) {
        public String arguments() { return challenger + " " + defender + " " + price + " " + window; }
    }

    public static final Type<MapTerritoryInfo> TYPE = new Type<>(ModServer.id("map_territory_info"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MapTerritoryInfo> STREAM_CODEC = StreamCodec.of(
            (buf, info) -> {
                buf.writeResourceLocation(info.dimension);
                buf.writeVarInt(info.x); buf.writeVarInt(info.z);
                buf.writeUtf(info.owner, 64); buf.writeUtf(info.detail, 1024); buf.writeVarLong(info.deadline);
                buf.writeOptional(info.offer, (b, o) -> {
                    b.writeUUID(o.challenger); b.writeUUID(o.defender); b.writeVarLong(o.price); b.writeVarLong(o.window);
                });
            },
            b -> new MapTerritoryInfo(b.readResourceLocation(), b.readVarInt(), b.readVarInt(), b.readUtf(64),
                    b.readUtf(1024), b.readVarLong(), b.readOptional(buf ->
                    new Offer(buf.readUUID(), buf.readUUID(), buf.readVarLong(), buf.readVarLong()))));
    @Override
    public Type<MapTerritoryInfo> type() { return TYPE; }
}
