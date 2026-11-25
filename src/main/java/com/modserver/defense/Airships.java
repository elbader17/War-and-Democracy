package com.modserver.defense;

import com.modserver.ModServer;

import dev.ryanhcode.sable.companion.*;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.*;

import java.util.*;

final class Airships {
    static SubLevelAccess at(ServerLevel level, BlockPos local) {
        return SableCompanion.INSTANCE.getContaining(level, local);
    }

    static boolean belongs(ServerLevel level, BlockPos local, UUID id) {
        var ship = at(level, local);
        return ship != null && ship.getUniqueId().equals(id);
    }

    static UUID register(ServerLevel level, BlockPos local, UUID nation) {
        var ship = at(level, local);
        if (ship == null) return null;
        var world = ship.logicalPose().transformPosition(Vec3.atCenterOf(local));
        if (!nation.equals(
                DefenseRegister.land(DefenseRegister.dimension(level), BlockPos.containing(world))))
            return null;
        var book = DefenseRegister.of(level);
        var previous = book.vehicleFlags.putIfAbsent(ship.getUniqueId(), nation);
        if (previous != null && !previous.equals(nation)) return null;
        book.setDirty();
        return ship.getUniqueId();
    }

    static List<DefenseContact> nearby(ServerLevel level, AABB box) {
        var contacts = new ArrayList<DefenseContact>();
        for (var ship : SableCompanion.INSTANCE.getAllIntersecting(level, new BoundingBox3d(box))) {
            UUID id = ship.getUniqueId();
            UUID owner = DefenseRegister.of(level).vehicleFlags.get(id);
            if (owner == null) {
                for (var pilot : level.players()) {
                    var riding = SableCompanion.INSTANCE.getTrackingOrVehicleSubLevel(pilot);
                    if (riding != null && id.equals(riding.getUniqueId())) {
                        owner =
                                ModServer.nations()
                                        .nationOf(pilot.getUUID())
                                        .map(n -> n.id())
                                        .orElse(null);
                        if (owner != null) break;
                    }
                }
            }
            contacts.add(new DefenseContact(id, owner, ship.boundingBox().toMojang().getCenter()));
        }
        return contacts;
    }

    static void hit(ServerLevel level, UUID id, Vec3 center, BlockPos struck, int strength) {
        var box = new BoundingBox3d(new AABB(center, center).inflate(3));
        for (var ship : SableCompanion.INSTANCE.getAllIntersecting(level, box)) {
            if (!ship.getUniqueId().equals(id)) continue;
            var local =
                    struck != null && belongs(level, struck, id)
                            ? struck
                            : BlockPos.containing(
                                    ship.logicalPose().transformPositionInverse(center));
            int removed = 0;
            for (var p : BlockPos.withinManhattan(local, 6, 6, 6)) {
                if (!level.isLoaded(p)) continue;
                var state = level.getBlockState(p);
                if (state.isAir() || state.getDestroySpeed(level, p) < 0 || at(level, p) != ship)
                    continue;
                if (level.destroyBlock(p, false) && ++removed >= strength) break;
            }
            return;
        }
    }

    private Airships() {}
}
