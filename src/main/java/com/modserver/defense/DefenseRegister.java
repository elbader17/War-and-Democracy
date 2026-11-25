package com.modserver.defense;

import com.modserver.ModServer;
import com.modserver.nation.Nation;
import com.modserver.nation.NationRelation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

public final class DefenseRegister extends SavedData {
    public record Site(String dimension, BlockPos pos) {}

    public static final class Station {
        public final UUID id;
        public final Site site;
        public final DefenseTier tier;
        public UUID owner;
        public boolean armed, radar;
        public BlockPos link;
        public final Set<UUID> protect = new LinkedHashSet<>(), engage = new LinkedHashSet<>();

        Station(UUID id, Site site, DefenseTier tier) {
            this.id = id;
            this.site = site;
            this.tier = tier;
        }

        boolean covers(net.minecraft.world.phys.Vec3 point) {
            return owner != null
                    && (tier == DefenseTier.AEGIS
                            || owner.equals(land(site.dimension, BlockPos.containing(point))));
        }

        public boolean hostile(Nation nation, UUID target, UUID targetNation) {
            if (!armed || !nation.id().equals(owner) || protect.contains(target)) return false;
            if (targetNation == null) return engage.contains(target);
            var relation = nation.relationWith(targetNation);
            return !nation.id().equals(targetNation)
                    && !relation.blocksDisputes()
                    && !protect.contains(targetNation)
                    && (relation == NationRelation.WAR
                            || engage.contains(target)
                            || engage.contains(targetNation));
        }
    }

    final Map<Site, Station> sites = new LinkedHashMap<>();
    final Map<UUID, UUID> vehicleFlags = new HashMap<>();

    public static DefenseRegister of(ServerLevel level) {
        return level.getServer()
                .overworld()
                .getDataStorage()
                .computeIfAbsent(
                        new Factory<>(DefenseRegister::new, DefenseRegister::load),
                        "modserver_territorial_defense");
    }

    static String dimension(ServerLevel level) {
        return level.dimension().location().toString();
    }

    public static UUID land(String dim, BlockPos pos) {
        var claim = ModServer.nations().claims().claimAt(dim, pos.getX() >> 4, pos.getZ() >> 4);
        return claim == null ? null : claim.nationId();
    }

    public Station station(ServerLevel level, BlockPos pos, DefenseTier tier) {
        var site = new Site(dimension(level), pos.immutable());
        var station = sites.get(site);
        if (station == null || station.tier != tier) {
            station = new Station(UUID.randomUUID(), site, tier);
            sites.put(site, station);
            setDirty();
        }
        reconcile(station);
        return station;
    }

    public void landChanged(com.modserver.claim.Claim claim) {
        for (Station station : sites.values()) {
            if (station.site.dimension.equals(claim.dimension())
                    && station.site.pos.getX() >> 4 == claim.chunkX()
                    && station.site.pos.getZ() >> 4 == claim.chunkZ()) reconcile(station);
        }
    }

    void reconcile(Station station) {
        var owner = land(station.site.dimension, station.site.pos);
        if (Objects.equals(owner, station.owner)) return;
        station.owner = owner;
        station.armed = false;
        station.radar = false;
        station.link = null;
        station.protect.clear();
        station.engage.clear();
        setDirty();
    }

    void remove(ServerLevel level, BlockPos pos) {
        if (sites.remove(new Site(dimension(level), pos)) != null) setDirty();
    }

    private static void readIds(CompoundTag tag, String key, Set<UUID> result) {
        for (Tag entry : tag.getList(key, Tag.TAG_INT_ARRAY)) result.add(NbtUtils.loadUUID(entry));
    }

    private static ListTag ids(Set<UUID> values) {
        var list = new ListTag();
        values.forEach(id -> list.add(NbtUtils.createUUID(id)));
        return list;
    }

    static DefenseRegister load(CompoundTag data, HolderLookup.Provider registries) {
        var book = new DefenseRegister();
        for (Tag raw : data.getList("Stations", Tag.TAG_COMPOUND)) {
            var tag = (CompoundTag) raw;
            var site = new Site(tag.getString("Dimension"), BlockPos.of(tag.getLong("Position")));
            var s =
                    new Station(
                            tag.getUUID("ID"), site, DefenseTier.valueOf(tag.getString("Tier")));
            if (tag.hasUUID("Owner")) s.owner = tag.getUUID("Owner");
            s.armed = tag.getBoolean("Armed");
            s.radar = tag.getBoolean("Radar");
            if (tag.contains("Link")) s.link = BlockPos.of(tag.getLong("Link"));
            readIds(tag, "Protect", s.protect);
            readIds(tag, "Engage", s.engage);
            book.sites.put(site, s);
        }
        for (Tag raw : data.getList("Flags", Tag.TAG_COMPOUND)) {
            var flag = (CompoundTag) raw;
            book.vehicleFlags.put(flag.getUUID("Ship"), flag.getUUID("Nation"));
        }
        return book;
    }

    @Override
    public CompoundTag save(CompoundTag data, HolderLookup.Provider registries) {
        var stations = new ListTag();
        for (var s : sites.values()) {
            var tag = new CompoundTag();
            tag.putUUID("ID", s.id);
            tag.putString("Dimension", s.site.dimension);
            tag.putLong("Position", s.site.pos.asLong());
            tag.putString("Tier", s.tier.name());
            if (s.owner != null) tag.putUUID("Owner", s.owner);
            tag.putBoolean("Armed", s.armed);
            tag.putBoolean("Radar", s.radar);
            if (s.link != null) tag.putLong("Link", s.link.asLong());
            tag.put("Protect", ids(s.protect));
            tag.put("Engage", ids(s.engage));
            stations.add(tag);
        }
        var flags = new ListTag();
        vehicleFlags.forEach(
                (ship, nation) -> {
                    var tag = new CompoundTag();
                    tag.putUUID("Ship", ship);
                    tag.putUUID("Nation", nation);
                    flags.add(tag);
                });
        data.put("Stations", stations);
        data.put("Flags", flags);
        return data;
    }
}
