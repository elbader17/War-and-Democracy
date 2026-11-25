package com.modserver.defense;

import com.happysg.radar.api.tracking.RadarSource;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.*;

final class RadarFeed {
    static boolean source(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockEntity(pos) instanceof RadarSource;
    }

    static RadarSource linked(ServerLevel level, DefenseRegister.Station station) {
        var pos = station.link;
        if (pos == null
                || !Objects.equals(
                        station.owner, DefenseRegister.land(station.site.dimension(), pos))
                || !level.isLoaded(pos)
                || !(level.getBlockEntity(pos) instanceof RadarSource radar)
                || !radar.isRunning()) return null;
        return radar;
    }

    static Set<UUID> contacts(ServerLevel level, DefenseRegister.Station station) {
        var radar = linked(level, station);
        if (radar == null) return Set.of();
        var ids = new HashSet<UUID>();
        for (var contact : radar.getContacts()) {
            String id = contact.getId();
            if (id == null || id.length() < 36) continue;
            try {
                ids.add(UUID.fromString(id.substring(id.length() - 36)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return ids;
    }

    private RadarFeed() {}
}
