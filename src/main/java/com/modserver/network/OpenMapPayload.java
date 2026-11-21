package com.modserver.network;

import com.modserver.ModServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The server asks the client to open the nations map.
 *
 * <p>It is used instead of a client command to avoid clashing with the server
 * dispatcher: {@code /nation map} is run by the server and the client receives the
 * order.</p>
 */
public record OpenMapPayload() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OpenMapPayload> TYPE =
            new CustomPacketPayload.Type<>(ModServer.id("open_nation_map"));

    @Override
    public CustomPacketPayload.Type<OpenMapPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenMapPayload> STREAM_CODEC =
            StreamCodec.of((buf, payload) -> {
            }, buf -> new OpenMapPayload());

    public static OpenMapPayload instance() {
        return new OpenMapPayload();
    }
}
