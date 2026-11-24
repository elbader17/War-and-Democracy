package com.modserver.petroleum;

import com.modserver.config.ModServerConfig;
import com.modserver.claim.Claim;
import com.modserver.claim.ClaimIndex;
import com.modserver.network.GovernmentView;
import com.modserver.network.NationMapPayload;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.UUID;

/** Concessions follow the land, including its used quota after a capture or a release and reclaim. */
public final class OilFields extends SavedData {
    private record Field(int reading, long day, int exported) {}
    private final Map<Long, Field> fields = new HashMap<>();

    public static OilFields of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(OilFields::new, OilFields::load), "modserver_oilfields");
    }

    public boolean discovered(long chunk) { return fields.containsKey(chunk); }

    /** The same nearby claims as the map, plus the player's concessions beyond its sync radius. */
    public List<Long> visible(NationMapPayload map, ClaimIndex claims,
                              String dimension, UUID owner) {
        var visible = new LinkedHashSet<Long>();
        for (var claim : map.claims()) {
            long key = Claim.pack(claim.chunkX(), claim.chunkZ());
            if (discovered(key)) visible.add(key);
        }
        if (owner != null) {
            for (long key : fields.keySet()) {
                var claim = claims.claimAt(dimension, Claim.unpackX(key), Claim.unpackZ(key));
                if (claim != null && owner.equals(claim.nationId())) visible.add(key);
            }
        }
        return List.copyOf(visible);
    }

    public int reading(long chunk) {
        var field = fields.get(chunk);
        return field == null ? -1 : field.reading();
    }

    /** Sum the latest surveys of land the nation currently holds, without loading any chunks. */
    public static GovernmentView.Reserves nationalReserves(MinecraftServer server, ClaimIndex claims, UUID nation) {
        long finiteMillibuckets = 0;
        int count = 0;
        boolean bottomless = false;
        for (ServerLevel level : server.getAllLevels()) {
            String dimension = level.dimension().location().toString();
            for (var entry : of(level).fields.entrySet()) {
                long key = entry.getKey();
                var claim = claims.claimAt(dimension, Claim.unpackX(key), Claim.unpackZ(key));
                if (claim == null || !nation.equals(claim.nationId())) continue;
                count++;
                int reading = entry.getValue().reading();
                if (reading == Integer.MAX_VALUE) bottomless = true;
                else finiteMillibuckets += reading;
            }
        }
        return new GovernmentView.Reserves(finiteMillibuckets, count, bottomless);
    }

    public void survey(long chunk, int amount) {
        var previous = fields.get(chunk);
        if ((amount == 0 && previous == null) || amount == reading(chunk)) return;
        fields.put(chunk, new Field(amount, previous == null ? -1 : previous.day(), previous == null ? 0 : previous.exported()));
        setDirty();
    }

    public int remaining(long chunk, long day) {
        var field = fields.get(chunk);
        if (field == null) return 0;
        int used = field.day() == day ? field.exported() : 0;
        return Math.max(0, ModServerConfig.OIL_EXPORT_QUOTA.get() - used);
    }

    void recordExport(long chunk, long day, int buckets) {
        var field = fields.get(chunk);
        fields.put(chunk, new Field(field.reading(), day, (field.day() == day ? field.exported() : 0) + buckets));
        setDirty();
    }

    public static OilFields load(CompoundTag data, HolderLookup.Provider registries) {
        var result = new OilFields();
        for (Tag entry : data.getList("Fields", Tag.TAG_COMPOUND)) {
            var tag = (CompoundTag)entry;
            result.fields.put(tag.getLong("Chunk"), new Field(Math.max(0, tag.getInt("Reading")),
                    tag.getLong("Day"), Math.max(0, tag.getInt("Exported"))));
        }
        return result;
    }

    @Override public CompoundTag save(CompoundTag data, HolderLookup.Provider registries) {
        var list = new ListTag();
        fields.forEach((chunk, field) -> {
            var tag = new CompoundTag();
            tag.putLong("Chunk", chunk);
            tag.putInt("Reading", field.reading());
            tag.putLong("Day", field.day());
            tag.putInt("Exported", field.exported());
            list.add(tag);
        });
        data.put("Fields", list);
        return data;
    }
}
