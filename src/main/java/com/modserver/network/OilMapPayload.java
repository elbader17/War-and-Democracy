package com.modserver.network;

import com.modserver.ModServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/** A single dimension-scoped snapshot, including concessions when both peers support them. */
public record OilMapPayload(DisputeMapPayload map, List<Long> fields) implements CustomPacketPayload {
    public static final Type<OilMapPayload> TYPE = new Type<>(ModServer.id("oil_map"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OilMapPayload> CODEC = StreamCodec.of(
            (buf, value) -> {
                DisputeMapPayload.STREAM_CODEC.encode(buf, value.map);
                buf.writeCollection(value.fields, (out, chunk) -> out.writeLong(chunk));
            }, buf -> new OilMapPayload(DisputeMapPayload.STREAM_CODEC.decode(buf), buf.readList(in -> in.readLong())));

    @Override public Type<OilMapPayload> type() { return TYPE; }
}
