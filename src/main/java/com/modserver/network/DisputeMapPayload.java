package com.modserver.network;

import com.modserver.military.TroopCounts;
import com.modserver.ModServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** The local country map and all ongoing territorial disputes in its dimension. */
public record DisputeMapPayload(BorderedMapPayload territory, ResourceLocation dimension, List<Battle> battles)
        implements CustomPacketPayload {
    public record Battle(int x, int z, String challenger, String defender, int dx, int dz,
                         TroopCounts attacking, TroopCounts defending, long nextDuel) {}

    public static final Type<DisputeMapPayload> TYPE = new Type<>(ModServer.id("dispute_map"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DisputeMapPayload> STREAM_CODEC = StreamCodec.of(
            (buf, snapshot) -> {
                BorderedMapPayload.STREAM_CODEC.encode(buf, snapshot.territory);
                buf.writeResourceLocation(snapshot.dimension);
                buf.writeCollection(snapshot.battles, (b, battle) -> {
                    b.writeVarInt(battle.x); b.writeVarInt(battle.z);
                    b.writeUtf(battle.challenger, 64); b.writeUtf(battle.defender, 64);
                    b.writeByte(battle.dx); b.writeByte(battle.dz);
                    battle.attacking.write(b); battle.defending.write(b); b.writeVarLong(battle.nextDuel);
                });
            },
            buf -> new DisputeMapPayload(BorderedMapPayload.STREAM_CODEC.decode(buf), buf.readResourceLocation(),
                    buf.readList(b -> new Battle(b.readVarInt(), b.readVarInt(), b.readUtf(64), b.readUtf(64),
                            b.readByte(), b.readByte(), TroopCounts.read(b), TroopCounts.read(b), b.readVarLong()))));

    @Override
    public Type<DisputeMapPayload> type() { return TYPE; }
}
