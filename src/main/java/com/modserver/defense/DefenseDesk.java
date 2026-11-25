package com.modserver.defense;

import com.modserver.ModServer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

public final class DefenseDesk {
    public enum Order {
        VIEW,
        ARM,
        HOLD,
        LOCAL,
        RADAR,
        PROTECT,
        ENGAGE,
        CLEAR
    }

    public record Request(Order order, boolean all, List<UUID> selected, String subject, int page)
            implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ModServer.id("defense_order"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC =
                StreamCodec.of(
                        (b, r) -> {
                            b.writeEnum(r.order);
                            b.writeBoolean(r.all);
                            b.writeCollection(r.selected, (buf, id) -> buf.writeUUID(id));
                            b.writeUtf(r.subject, 80);
                            b.writeVarInt(r.page);
                        },
                        b ->
                                new Request(
                                        b.readEnum(Order.class),
                                        b.readBoolean(),
                                        b.readCollection(
                                                net.minecraft.network.FriendlyByteBuf.limitValue(
                                                        ArrayList::new, 128),
                                                buf -> buf.readUUID()),
                                        b.readUtf(80),
                                        b.readVarInt()));

        @Override
        public Type<Request> type() {
            return TYPE;
        }
    }

    public record Row(
            UUID id,
            DefenseTier tier,
            String dim,
            BlockPos pos,
            String status,
            boolean radar,
            String protect,
            String engage) {}

    public record View(List<Row> rows, int page, int pages, int total, String notice)
            implements CustomPacketPayload {
        public static final Type<View> TYPE = new Type<>(ModServer.id("defense_register"));
        public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC =
                StreamCodec.of(
                        (b, v) -> {
                            b.writeCollection(
                                    v.rows,
                                    (buf, r) -> {
                                        buf.writeUUID(r.id);
                                        buf.writeEnum(r.tier);
                                        buf.writeUtf(r.dim);
                                        buf.writeBlockPos(r.pos);
                                        buf.writeUtf(r.status);
                                        buf.writeBoolean(r.radar);
                                        buf.writeUtf(r.protect);
                                        buf.writeUtf(r.engage);
                                    });
                            b.writeVarInt(v.page);
                            b.writeVarInt(v.pages);
                            b.writeVarInt(v.total);
                            b.writeUtf(v.notice);
                        },
                        b ->
                                new View(
                                        b.readList(
                                                buf ->
                                                        new Row(
                                                                buf.readUUID(),
                                                                buf.readEnum(DefenseTier.class),
                                                                buf.readUtf(),
                                                                buf.readBlockPos(),
                                                                buf.readUtf(),
                                                                buf.readBoolean(),
                                                                buf.readUtf(),
                                                                buf.readUtf())),
                                        b.readVarInt(),
                                        b.readVarInt(),
                                        b.readVarInt(),
                                        b.readUtf()));

        @Override
        public Type<View> type() {
            return TYPE;
        }
    }

    public static void handle(ServerPlayer player, Request request) {
        var nation = ModServer.nations().nations().ownedBy(player.getUUID()).orElse(null);
        if (nation == null) {
            PacketDistributor.sendToPlayer(
                    player,
                    new View(List.of(), 0, 1, 0, "Only a nation owner can issue defense orders."));
            return;
        }
        var book = DefenseRegister.of(player.serverLevel());
        var owned =
                book.sites.values().stream()
                        .peek(book::reconcile)
                        .filter(s -> nation.id().equals(s.owner))
                        .toList();
        String notice = "";
        if (request.order != Order.VIEW) {
            var ids = Set.copyOf(request.selected);
            var targets = owned.stream().filter(s -> request.all || ids.contains(s.id)).toList();
            UUID subject = null;
            boolean listing =
                    request.order == Order.PROTECT
                            || request.order == Order.ENGAGE
                            || request.order == Order.CLEAR;
            if (listing) subject = resolve(player, request.subject.strip());
            if (targets.isEmpty()) notice = "Select an installation first.";
            else if (listing && subject == null)
                notice = "Enter a country name, online player name or UUID.";
            else {
                int count = 0;
                for (var s : targets) {
                    switch (request.order) {
                        case ARM -> s.armed = true;
                        case HOLD -> s.armed = false;
                        case LOCAL -> s.radar = false;
                        case RADAR -> {
                            if (s.link == null) continue;
                            s.radar = true;
                        }
                        case PROTECT, ENGAGE, CLEAR -> {
                            var list = request.order == Order.PROTECT ? s.protect : s.engage;
                            if (request.order != Order.CLEAR
                                    && list.size() >= 64
                                    && !list.contains(subject)) continue;
                            s.protect.remove(subject);
                            s.engage.remove(subject);
                            if (request.order != Order.CLEAR) list.add(subject);
                        }
                        default -> {}
                    }
                    count++;
                }
                book.setDirty();
                notice = count + " / " + targets.size() + " installations updated.";
            }
        }
        int size = 6,
                pages = Math.max(1, (owned.size() + size - 1) / size),
                page = Math.clamp(request.page, 0, pages - 1);
        var rows = new ArrayList<Row>();
        for (var s : owned.subList(page * size, Math.min(owned.size(), (page + 1) * size))) {
            var dim =
                    ResourceKey.create(
                            Registries.DIMENSION, ResourceLocation.parse(s.site.dimension()));
            var level = player.server.getLevel(dim);
            String status = s.armed ? "Armed • chunk asleep" : "Hold fire";
            if (level != null
                    && level.isLoaded(s.site.pos())
                    && level.getBlockEntity(s.site.pos()) instanceof TurretEntity turret)
                status = turret.status(level, s);
            rows.add(
                    new Row(
                            s.id,
                            s.tier,
                            s.site.dimension(),
                            s.site.pos(),
                            status,
                            s.radar,
                            names(s.protect),
                            names(s.engage)));
        }
        PacketDistributor.sendToPlayer(player, new View(rows, page, pages, owned.size(), notice));
    }

    private static UUID resolve(ServerPlayer player, String name) {
        var country = ModServer.nations().nations().byName(name);
        if (country.isPresent()) return country.get().id();
        var online = player.server.getPlayerList().getPlayerByName(name);
        if (online != null) return online.getUUID();
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String names(Set<UUID> ids) {
        return String.join(
                ", ",
                ids.stream()
                        .map(
                                id ->
                                        ModServer.nations()
                                                .nations()
                                                .byId(id)
                                                .map(n -> n.name())
                                                .orElse(id.toString()))
                        .toList());
    }

    private DefenseDesk() {}
}
