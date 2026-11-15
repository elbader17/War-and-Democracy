package com.modserver.network;

import com.modserver.ModServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Nations map data that the server sends to the client.
 *
 * <p>Compact format: the claims reference the nation by index, not by UUID.</p>
 */
public record NationMapPayload(List<NationView> nations, List<ClaimView> claims, int playerY)
        implements CustomPacketPayload {

    /** View of a nation for the client. */
    public record NationView(UUID id, String name, int color, int chunkCount, int capitalX, int capitalZ) {
    }

    /** View of a claimed chunk: nation index + chunk coordinates. */
    public record ClaimView(int nationIndex, int chunkX, int chunkZ) {
    }

    public static final CustomPacketPayload.Type<NationMapPayload> TYPE =
            new CustomPacketPayload.Type<>(ModServer.id("nation_map"));

    @Override
    public CustomPacketPayload.Type<NationMapPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, NationMapPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.nations().size());
                for (NationView nation : payload.nations()) {
                    buf.writeUUID(nation.id());
                    buf.writeUtf(nation.name(), 64);
                    buf.writeVarInt(nation.color());
                    buf.writeVarInt(nation.chunkCount());
                    buf.writeVarInt(nation.capitalX());
                    buf.writeVarInt(nation.capitalZ());
                }
                buf.writeVarInt(payload.claims().size());
                for (ClaimView claim : payload.claims()) {
                    buf.writeVarInt(claim.nationIndex());
                    buf.writeVarInt(claim.chunkX());
                    buf.writeVarInt(claim.chunkZ());
                }
                buf.writeVarInt(payload.playerY());
            },
            buf -> {
                int nationCount = buf.readVarInt();
                List<NationView> nations = new ArrayList<>(Math.min(nationCount, 4096));
                for (int i = 0; i < nationCount; i++) {
                    nations.add(new NationView(
                            buf.readUUID(),
                            buf.readUtf(64),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt()));
                }
                int claimCount = buf.readVarInt();
                List<ClaimView> claims = new ArrayList<>(Math.min(claimCount, 65536));
                for (int i = 0; i < claimCount; i++) {
                    claims.add(new ClaimView(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
                }
                return new NationMapPayload(nations, claims, buf.readVarInt());
            });

    /** Claims of a specific nation (by index). */
    public List<ClaimView> claimsOf(int nationIndex) {
        List<ClaimView> result = new ArrayList<>();
        for (ClaimView claim : claims) {
            if (claim.nationIndex() == nationIndex) {
                result.add(claim);
            }
        }
        return result;
    }
}
