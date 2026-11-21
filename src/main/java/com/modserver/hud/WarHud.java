package com.modserver.hud;

import com.modserver.NationService;
import com.modserver.ServerLanguage;
import com.modserver.dispute.Dispute;
import com.modserver.nation.Nation;
import com.modserver.nation.NationRelation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** One vanilla boss bar per participant. The first deadline takes priority. */
public final class WarHud {
    private final Map<ServerPlayer, ServerBossEvent> bars = new HashMap<>();

    public void update(MinecraftServer server, NationService service) {
        var players = server.getPlayerList();
        bars.entrySet().removeIf(e -> {
            if (players.getPlayer(e.getKey().getUUID()) == e.getKey()) return false;
            e.getValue().removeAllPlayers();
            return true;
        });
        Map<UUID, List<Dispute>> battles = new HashMap<>();
        for (var d : service.disputes().open()) {
            battles.computeIfAbsent(d.challengerNationId(), k -> new ArrayList<>()).add(d);
            battles.computeIfAbsent(d.defenderNationId(), k -> new ArrayList<>()).add(d);
        }
        long now = server.overworld().getGameTime();
        for (var player : players.getPlayers()) {
            Nation country = service.actingNation(player.getUUID()).orElse(null);
            Status status = country == null ? null : status(country, battles.get(country.id()), service, now, ServerLanguage.of(player));
            if (status == null) {
                var old = bars.remove(player);
                if (old != null) old.removeAllPlayers();
                continue;
            }
            ServerBossEvent bar = bars.computeIfAbsent(player, p -> new ServerBossEvent(Component.empty(),
                    BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS));
            bar.setName(Component.literal(status.text()));
            bar.setColor(status.color());
            bar.setProgress(status.progress());
            bar.addPlayer(player);
        }
    }

    private record Status(String text, BossEvent.BossBarColor color, float progress) {}

    private static Status status(Nation country, List<Dispute> battles, NationService service, long now, ServerLanguage language) {
        if (battles != null && !battles.isEmpty()) {
            Dispute d = battles.stream().min(Comparator.comparingLong(Dispute::deadline)
                    .thenComparingLong(Dispute::id)).orElseThrow();
            boolean attacking = country.id().equals(d.challengerNationId());
            UUID opponent = attacking ? d.defenderNationId() : d.challengerNationId();
            String name = service.nations().byId(opponent).map(Nation::name).orElse("?");
            String title = language.text(country.relationWith(opponent) == NationRelation.WAR ? "modserver.war.war" : "modserver.war.dispute");
            long ticks = Math.max(0L, d.deadline() - now);
            long seconds = (ticks + 19) / 20;
            String timer = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
            String extra = battles.size() > 1 ? " (+" + (battles.size() - 1) + ")" : "";
            float remaining = Math.min(1f, (float) ticks / Math.max(1L, d.deadline() - d.openedAt()));
            return new Status(title + " · " + language.text(attacking ? "modserver.war.attacking" : "modserver.war.defending", name) + " · "
                    + d.chunkX() + ", " + d.chunkZ() + " · " + timer + extra,
                    attacking ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.YELLOW, remaining);
        }
        List<String> enemies = country.relations().entrySet().stream().filter(e -> e.getValue() == NationRelation.WAR)
                .flatMap(e -> service.nations().byId(e.getKey()).stream()).map(Nation::name).sorted().toList();
        if (enemies.isEmpty()) return null;
        String extra = enemies.size() > 1 ? " (+" + (enemies.size() - 1) + ")" : "";
        return new Status(language.text("modserver.war.idle", enemies.getFirst() + extra),
                BossEvent.BossBarColor.PURPLE, 1f);
    }

    public void clear() {
        bars.values().forEach(ServerBossEvent::removeAllPlayers);
        bars.clear();
    }
}
