package com.modserver.network;

import com.modserver.ModServer;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;
import java.util.function.BiFunction;

/** A map snapshot with one byte of authoritative border information per visible chunk. */
public record BorderedMapPayload(NationMapPayload map, byte[] borders) implements CustomPacketPayload {
    public static final Type<BorderedMapPayload> TYPE = new Type<>(ModServer.id("nation_map_borders"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BorderedMapPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                NationMapPayload.STREAM_CODEC.encode(buf, payload.map());
                buf.writeBytes(payload.borders());
            },
            buf -> {
                NationMapPayload map = NationMapPayload.STREAM_CODEC.decode(buf);
                byte[] borders = new byte[map.claims().size()];
                buf.readBytes(borders);
                return new BorderedMapPayload(map, borders);
            });

    /** Low four bits mark boundary faces. High four bits mark a neighboring country. */
    public static BorderedMapPayload from(NationMapPayload map, BiFunction<Integer, Integer, UUID> ownerAt) {
        byte[] borders = new byte[map.claims().size()];
        for (int i = 0; i < borders.length; i++) {
            var claim = map.claims().get(i);
            UUID owner = map.nations().get(claim.nationIndex()).id();
            for (Direction face : Direction.Plane.HORIZONTAL) {
                UUID neighbor = ownerAt.apply(claim.chunkX() + face.getStepX(), claim.chunkZ() + face.getStepZ());
                if (!owner.equals(neighbor)) {
                    int bit = 1 << face.get2DDataValue();
                    borders[i] |= neighbor == null ? bit : bit | (bit << 4);
                }
            }
        }
        return new BorderedMapPayload(map, borders);
    }

    @Override
    public Type<BorderedMapPayload> type() {
        return TYPE;
    }
}
