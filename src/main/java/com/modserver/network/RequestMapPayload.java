package com.modserver.network;

import com.modserver.ModServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Refreshes the current player's territorial overlays around their position. */
public record RequestMapPayload() implements CustomPacketPayload {
    public static final RequestMapPayload INSTANCE = new RequestMapPayload();
    public static final Type<RequestMapPayload> TYPE = new Type<>(ModServer.id("request_territory_map"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestMapPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<RequestMapPayload> type() {
        return TYPE;
    }
}
