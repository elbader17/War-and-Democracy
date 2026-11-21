package com.modserver.command;

import com.modserver.NationService;
import com.modserver.ServerLanguage;
import com.modserver.claim.Claim;
import com.modserver.config.ModServerConfig;
import com.modserver.nation.Nation;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.ChunkPos;

import java.util.LinkedHashSet;
import java.util.Set;

/** A local political map that a vanilla client can display in chat. */
final class TerritoryChatMap {
    private TerritoryChatMap() {
    }

    static Component render(NationService service, String dimension, ChunkPos center, ServerLanguage language) {
        int radius = Math.min(3, ModServerConfig.MAP_SYNC_RADIUS_CHUNKS.get());
        MutableComponent map = Component.literal(language.text("modserver.map.heading", center.x, center.z));
        Set<Nation> visible = new LinkedHashSet<>();
        for (int z = center.z - radius; z <= center.z + radius; z++) {
            map.append("\n");
            for (int x = center.x - radius; x <= center.x + radius; x++) {
                Claim claim = service.claims().claimAt(dimension, x, z);
                Nation nation = claim == null ? null : service.nations().byId(claim.nationId()).orElse(null);
                if (nation != null) {
                    visible.add(nation);
                }
                var battle = service.disputes().openAt(dimension, x, z);
                boolean disputed = battle != null;
                boolean here = x == center.x && z == center.z;
                boolean frontier = false;
                if (nation != null) {
                    for (Direction side : Direction.Plane.HORIZONTAL) {
                        Claim neighbor = service.claims().claimAt(dimension, x + side.getStepX(), z + side.getStepZ());
                        if (neighbor != null && !nation.id().equals(neighbor.nationId())) {
                            frontier = true;
                            break;
                        }
                    }
                }
                String tile = disputed ? "▧" : frontier ? "▣" : nation == null ? "□" : "■";
                String label = x + ", " + z + " · " + (nation == null ? language.text("modserver.map.unclaimed") : nation.name())
                        + (disputed ? " · " + language.text("map.modserver.battle", service.nameOf(battle.challengerNationId()), service.nameOf(battle.defenderNationId())) : "");
                int color = nation == null ? 0xAAAAAA : nation.color();
                String command = "/nation map territory " + dimension + " " + x + " " + z;
                map.append(Component.literal(tile).withStyle(style -> style.withColor(color)
                        .withUnderlined(here)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(label)))));
            }
        }
        map.append("\n" + language.text("modserver.map.legend"));
        for (Nation nation : visible) {
            map.append(Component.literal("  ■ " + nation.name()).withColor(nation.color()));
        }
        return map;
    }
}
