package com.modserver.defense;

import com.modserver.ModServer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.*;
import net.neoforged.fml.ModList;

import java.util.*;

record DefenseContact(UUID id, UUID nation, Vec3 point) {
    static List<DefenseContact> nearby(
            ServerLevel level, DefenseTier tier, Vec3 origin, int range) {
        var box = new AABB(origin, origin).inflate(range);
        if (tier == DefenseTier.BASTION || tier == DefenseTier.SKYGUARD)
            return ModList.get().isLoaded("sable") ? Airships.nearby(level, box) : List.of();
        var result = new ArrayList<DefenseContact>();
        if (tier == DefenseTier.AEGIS) {
            for (var round :
                    level.getEntitiesOfClass(
                            DefenseRound.class, box, r -> r.kind() == DefenseRound.Kind.SKYROCKET))
                result.add(new DefenseContact(round.getUUID(), round.nation, round.position()));
        } else {
            if (!level.getServer().isPvpAllowed()) return result;
            for (var player : level.players()) {
                if (player.isCreative()
                        || player.isSpectator()
                        || !player.isAlive()
                        || !box.contains(player.position())) continue;
                result.add(
                        new DefenseContact(
                                player.getUUID(),
                                ModServer.nations()
                                        .nationOf(player.getUUID())
                                        .map(n -> n.id())
                                        .orElse(null),
                                player.getBoundingBox().getCenter()));
            }
        }
        return result;
    }
}
