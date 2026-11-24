package com.modserver.network;

import com.google.gson.Gson;
import com.modserver.ModServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.List;

public record GovernmentView(String action, String name, String preamble, String doctrines, int color, int land,
        String capital, String localChunk, String localOwner, long balance, long income,
        int guards, int guardLevel, int maxLevel, long guardCost, long attack, long warAttack,
        String chest, String chestStatus, List<Row> rows, String notice, String token, String offer, Oil oil)
        implements CustomPacketPayload {
    public record Reserves(long finiteMillibuckets, int fields, boolean bottomless) {}
    public record Oil(String status, int reading, int remaining, String grade, int millibuckets, List<Long> prices,
                      Reserves reserves) {}
    public record Row(String id, int count, long price) {
        public long value() { return count * price; }
    }
    public long stockValue() { return rows.stream().mapToLong(Row::value).sum(); }
    public static final Type<GovernmentView> TYPE = new Type<>(ModServer.id("government"));
    private static final Gson JSON = new Gson();
    public static final StreamCodec<RegistryFriendlyByteBuf, GovernmentView> CODEC = StreamCodec.of(
            (buf, view) -> buf.writeUtf(JSON.toJson(view), 8192),
            buf -> JSON.fromJson(buf.readUtf(8192), GovernmentView.class));
    @Override public Type<GovernmentView> type() { return TYPE; }
}
