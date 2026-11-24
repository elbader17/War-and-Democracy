package com.modserver.network;

import com.google.gson.Gson;
import com.modserver.ModServer;
import com.modserver.council.CouncilPost;
import com.modserver.government.Commodity;
import com.modserver.nation.NationRelation;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.List;
import java.util.UUID;

public record CouncilView(boolean open, String notice, UUID mine, String name, int color, String capital,
        int land, long balance, long income, int farm, int garrison, int treasury, int reserve, long deployed,
        int battles, int convoys, int allies, int pending, List<Integer> stock, boolean chest,
        GovernmentView.Reserves oil, int page, int pages, List<Peer> directory, UUID selected,
        List<Entry> offers, List<Entry> letters) implements CustomPacketPayload {
    public record Entry(UUID id, UUID from, UUID to, CouncilPost.Kind kind, Commodity item, int quantity, long coins, String text) {
        public static Entry of(CouncilPost p) {
            return new Entry(p.id(), p.from(), p.to(), p.kind(), p.item(), p.quantity(), p.coins(), p.text());
        }
    }
    public record Peer(UUID id, String name, int color, NationRelation relation, int incoming, boolean envoy) {}
    public static final int PAGE_SIZE = 5;
    public static final Type<CouncilView> TYPE = new Type<>(ModServer.id("council"));
    private static final Gson JSON = new Gson();
    public static final StreamCodec<RegistryFriendlyByteBuf,CouncilView> CODEC = StreamCodec.of(
            (buf, view) -> buf.writeUtf(JSON.toJson(view), 32767),
            buf -> JSON.fromJson(buf.readUtf(32767), CouncilView.class));
    @Override public Type<CouncilView> type() { return TYPE; }
}
