package com.modserver.network;

import com.modserver.ModServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Panel controls use the same server command tree as vanilla, without generating chat traffic. */
public record GovernmentRequest(String arguments) implements CustomPacketPayload {
    public static final Type<GovernmentRequest> TYPE = new Type<>(ModServer.id("government_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GovernmentRequest> CODEC = StreamCodec.of(
            (buffer, request) -> buffer.writeUtf(request.arguments, 220),
            buffer -> new GovernmentRequest(buffer.readUtf(220)));
    @Override public Type<GovernmentRequest> type() { return TYPE; }
}
