package com.modserver.hud;

import com.modserver.NationService;
import com.modserver.ServerLanguage;
import com.modserver.config.ModServerConfig;
import com.modserver.economy.EconomyRules;
import com.modserver.economy.NationEconomy;
import com.modserver.nation.Nation;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** A private vanilla sidebar for each connected country's owner. */
public final class NationSidebar {
    private final String objectiveName = "wd_" + UUID.randomUUID().toString().substring(0, 12);
    private final Scoreboard privateBoard = new Scoreboard();
    private Map<ServerGamePacketListenerImpl, View> visible = new HashMap<>();

    public void update(MinecraftServer server, NationService service) {
        var online = server.getPlayerList();
        if (online.getPlayerCount() == 0) {
            clear();
            return;
        }
        Map<ServerGamePacketListenerImpl, View> next = new HashMap<>();
        // Let the world's own sidebar take precedence while it occupies the slot.
        if (server.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR) == null) {
            EconomyRules rules = service.economyRules();
            boolean dailyCycle = ModServerConfig.ECONOMY_ENABLED.get();
            for (Nation nation : service.allNations()) {
                var player = online.getPlayer(nation.owner());
                if (player == null) continue;
                int chunks = service.claims().chunkCount(nation.id());
                var language = ServerLanguage.of(player);
                View view = View.of(nation, chunks, rules, dailyCycle, language);
                var connection = player.connection;
                View previous = visible.get(connection);
                if (!view.equals(previous)) {
                    publish(connection, view, previous);
                }
                next.put(connection, view);
            }
        }
        for (var connection : visible.keySet()) {
            if (!next.containsKey(connection) && connection.isAcceptingMessages()) {
                connection.send(new ClientboundSetObjectivePacket(objective(Component.empty()),
                        ClientboundSetObjectivePacket.METHOD_REMOVE));
            }
        }
        visible = next;
    }

    public void clear() {
        visible.clear();
    }

    private Objective objective(Component title) {
        return new Objective(privateBoard, objectiveName, ObjectiveCriteria.DUMMY, title,
                ObjectiveCriteria.RenderType.INTEGER, false, null);
    }

    private void publish(ServerGamePacketListenerImpl connection, View view, View previous) {
        Component title = Component.literal("⚑ ").withColor(view.color())
                .append(Component.literal(view.name()).withColor(0xF4CB75).withStyle(ChatFormatting.BOLD));
        Objective objective = objective(title);
        if (previous == null || previous.color() != view.color() || !previous.name().equals(view.name())) {
            connection.send(new ClientboundSetObjectivePacket(objective, previous == null
                    ? ClientboundSetObjectivePacket.METHOD_ADD : ClientboundSetObjectivePacket.METHOD_CHANGE));
        }
        Map<Field, String> oldRows = previous == null ? Map.of() : previous.rows();
        Map<Field, String> rows = view.rows();
        for (var field : Field.values()) {
            String value = rows.get(field);
            String rowId = "wd." + field.name();
            if (value == null) {
                if (oldRows.containsKey(field)) {
                    connection.send(new ClientboundResetScorePacket(rowId, objectiveName));
                }
                continue;
            }
            if (previous != null && previous.language() == view.language() && value.equals(oldRows.get(field))) {
                continue;
            }
            String label = view.language().text(field.key);
            Component display = Component.literal(" " + label).withColor(0xF1E2C1);
            var number = new FixedFormat(Component.literal(value + " ").withColor(field.color));
            connection.send(new ClientboundSetScorePacket(rowId, objectiveName, Field.values().length - field.ordinal(),
                    Optional.of(display), Optional.of(number)));
        }
        if (previous == null) {
            connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective));
        }
    }

    private enum Field {
        BALANCE("modserver.sidebar.balance", 0xFFE089),
        INCOME("modserver.sidebar.income", 0xA2EDB0),
        CYCLE("modserver.sidebar.cycle", 0xFFE089),
        LAND("modserver.sidebar.land", 0xFFFFFF),
        CAPITAL("modserver.sidebar.capital", 0xFFFFFF);

        final String key;
        final int color;

        Field(String key, int color) {
            this.key = key;
            this.color = color;
        }
    }

    private record View(String name, int color, int chunks, String capital, long balance, long income,
                        boolean dailyCycle, ServerLanguage language) {
        static View of(Nation nation, int chunks, EconomyRules rules, boolean dailyCycle, ServerLanguage language) {
            NationEconomy eco = nation.economy();
            String capital = nation.hasCapital() ? nation.capitalX() + ", " + nation.capitalZ() : "—";
            return new View(nation.name(), nation.color(), chunks, capital, eco.balance(),
                    rules.dailyIncome(chunks, eco), dailyCycle, language);
        }

        Map<Field, String> rows() {
            Map<Field, String> rows = new EnumMap<>(Field.class);
            rows.put(Field.BALANCE, language.text("modserver.sidebar.coins", language.coins(balance)));
            if (dailyCycle) {
                rows.put(Field.INCOME, "+" + language.coins(income));
            } else {
                rows.put(Field.CYCLE, language.text("modserver.sidebar.paused"));
            }
            rows.put(Field.LAND, chunks + (chunks == 1 ? " chunk" : " chunks"));
            rows.put(Field.CAPITAL, capital);
            return rows;
        }
    }
}
