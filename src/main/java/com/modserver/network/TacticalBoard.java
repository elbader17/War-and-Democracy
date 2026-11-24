package com.modserver.network;

import com.modserver.military.TroopBranch;
import com.modserver.military.TroopTransfer;
import net.minecraft.network.FriendlyByteBuf;
import com.modserver.military.TroopCounts;
import com.modserver.ModServer;
import com.modserver.claim.Claim;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.Map;
import java.util.UUID;

/** A bounded window of the military board, negotiated independently from minimap data. */
public record TacticalBoard(UUID token, ResourceLocation dimension, int x, int z, boolean open, UUID closeToken, UUID mine,
                            long balance, TroopCounts reserveForces, long price, long nuclearPrice, Map<UUID, Country> countries,
                            Map<Long, Cell> cells, TransitPage transit, String notice) implements CustomPacketPayload {
    public static final int RADIUS = 32;
    public static final UUID OBSERVER = new UUID(0, 0);
    public record Country(String name, int color, boolean protectedByTreaty, boolean allied) {}
    public record Transit(String sender, String recipient, String dimension, long from, long to,
                          TroopCounts troops, TroopTransfer.Leg leg, long remainingTicks, long legTicks) {
        public String originName() { return leg == TroopTransfer.Leg.OUTBOUND ? sender : recipient; }
        public String destinationName() { return leg == TroopTransfer.Leg.OUTBOUND ? recipient : sender; }
        public long distance() {
            return Math.abs((long)Claim.unpackX(from) - Claim.unpackX(to)) + Math.abs((long)Claim.unpackZ(from) - Claim.unpackZ(to));
        }
    }
    public record TransitPage(int total, int index, Transit trip) {
        public static final TransitPage EMPTY = new TransitPage(0, 0, null);
        private void write(FriendlyByteBuf b) {
            b.writeVarInt(total); b.writeVarInt(index);
            b.writeNullable(trip, (out, t) -> {
                out.writeUtf(t.sender,64); out.writeUtf(t.recipient,64); out.writeUtf(t.dimension);
                out.writeLong(t.from); out.writeLong(t.to); t.troops.write(out); out.writeEnum(t.leg); out.writeVarLong(t.remainingTicks);
                out.writeVarLong(t.legTicks);
            });
        }
        private static TransitPage read(FriendlyByteBuf b) {
            return new TransitPage(b.readVarInt(), b.readVarInt(), b.readNullable(in -> new Transit(
                    in.readUtf(64), in.readUtf(64), in.readUtf(), in.readLong(), in.readLong(), TroopCounts.read(in),
                    in.readEnum(TroopTransfer.Leg.class), in.readVarLong(), in.readVarLong())));
        }
    }

    /** Include both selected endpoints even when a long route spans separate map windows. */
    public record ViewContext(Long origin, Long target, int page) {
        public static final ViewContext DEFAULT = new ViewContext(null, null, 0);
        private void write(FriendlyByteBuf b) {
            b.writeNullable(origin, (out,k) -> out.writeLong(k));
            b.writeNullable(target, (out,k) -> out.writeLong(k));
            b.writeVarInt(page);
        }
        private static ViewContext read(FriendlyByteBuf b) {
            return new ViewContext(b.readNullable(in -> in.readLong()), b.readNullable(in -> in.readLong()), b.readVarInt());
        }
    }
    public int reserve() { return reserveForces.total(); }
    public record Cell(UUID owner, TroopCounts forces, int missiles, int nuclearMissiles, boolean disputed, TroopCounts invading, boolean oilField) {
        public int armies() { return forces.total(); }
        public int attackers() { return invading.total(); }
    }
    public Cell at(int x, int z) { return cells.get(Claim.pack(x, z)); }
    public static final Type<TacticalBoard> TYPE = new Type<>(ModServer.id("tactical_board"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TacticalBoard> CODEC = StreamCodec.of((b, v) -> {
        b.writeUUID(v.token); b.writeResourceLocation(v.dimension); b.writeInt(v.x); b.writeInt(v.z); b.writeBoolean(v.open);
        b.writeNullable(v.closeToken, (o, id) -> o.writeUUID(id));
        b.writeUUID(v.mine); b.writeLong(v.balance); v.reserveForces.write(b); b.writeVarLong(v.price); b.writeVarLong(v.nuclearPrice);
        b.writeMap(v.countries, (o,id)->o.writeUUID(id), (o,c)-> {
            o.writeUtf(c.name,64); o.writeInt(c.color); o.writeBoolean(c.protectedByTreaty); o.writeBoolean(c.allied);
        });
        b.writeMap(v.cells, (o,k)->o.writeLong(k), (o,c)-> {
            o.writeUUID(c.owner); c.forces.write(o); o.writeVarInt(c.missiles); o.writeVarInt(c.nuclearMissiles); o.writeBoolean(c.disputed); c.invading.write(o); o.writeBoolean(c.oilField);
        });
        v.transit.write(b);
        b.writeUtf(v.notice,1024);
    }, b -> new TacticalBoard(b.readUUID(),b.readResourceLocation(),b.readInt(),b.readInt(),b.readBoolean(),b.readNullable(i -> i.readUUID()),
            b.readUUID(),b.readLong(),TroopCounts.read(b),b.readVarLong(),b.readVarLong(),
            b.readMap(i->i.readUUID(),i->new Country(i.readUtf(64),i.readInt(),i.readBoolean(),i.readBoolean())),
            b.readMap(i->i.readLong(),i->new Cell(i.readUUID(),TroopCounts.read(i),i.readVarInt(),i.readVarInt(),i.readBoolean(),TroopCounts.read(i),i.readBoolean())),TransitPage.read(b),b.readUtf(1024)));
    @Override public Type<TacticalBoard> type() { return TYPE; }

    public record Request(UUID token, String action, int x, int z, int tx, int tz, int quantity, TroopBranch branch, ViewContext view) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ModServer.id("tactical_order"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of((b,r)-> {
            b.writeUUID(r.token); b.writeUtf(r.action,12); b.writeInt(r.x); b.writeInt(r.z);
            b.writeInt(r.tx); b.writeInt(r.tz); b.writeVarInt(r.quantity); b.writeNullable(r.branch, (out, arm) -> out.writeEnum(arm)); r.view.write(b);
        }, b->new Request(b.readUUID(),b.readUtf(12),b.readInt(),b.readInt(),b.readInt(),b.readInt(),b.readVarInt(),b.readNullable(in -> in.readEnum(TroopBranch.class)),ViewContext.read(b)));
        @Override public Type<Request> type() { return TYPE; }
    }
}
