package com.modserver.claim;

import com.modserver.military.TroopCounts;
import java.util.UUID;

/**
 * Claim of a chunk for a nation.
 *
 * <p>Granularity: 1 chunk (16x16). Overworld only in version 1, but the key
 * includes the dimension so it can be extended later without migrating the data.</p>
 */
public final class Claim {

    private final String dimension;
    private final int chunkX;
    private final int chunkZ;
    private UUID nationId;
    private final long createdAt;
    private TroopCounts forces = new TroopCounts(1, 0, 0);
    private int missiles;
    private int nuclearMissiles;
    public int nuclearMissiles() { return nuclearMissiles; }
    public void setNuclearMissiles(int count) {
        if (count < 0) throw new IllegalArgumentException("Negative nuclear missile count");
        nuclearMissiles = count;
    }

    public int armies() { return forces.total(); }
    public TroopCounts forces() { return forces; }
    public void setForces(TroopCounts value) { forces = value; }
    public int missiles() { return missiles; }
    public void setArmies(int count) {
        if (count < 0) throw new IllegalArgumentException("Negative army count");
        forces = new TroopCounts(count, 0, 0);
    }
    public void setMissiles(int count) {
        if (count < 0) throw new IllegalArgumentException("Negative missile count");
        missiles = count;
    }

    public Claim(String dimension, int chunkX, int chunkZ, UUID nationId, long createdAt) {
        this.dimension = dimension;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.nationId = nationId;
        this.createdAt = createdAt;
    }

    public String dimension() {
        return dimension;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public UUID nationId() {
        return nationId;
    }

    void setNationId(UUID nationId) {
        this.nationId = nationId;
    }

    public long createdAt() {
        return createdAt;
    }

    /** Compact key of the chunk within a dimension (X and Z packed together). */
    public long chunkKey() {
        return pack(chunkX, chunkZ);
    }

    public static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }

    public static int unpackX(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }

    public static int unpackZ(long key) {
        return (int) (key >>> 32);
    }

    @Override
    public String toString() {
        return "Claim[" + dimension + " " + chunkX + "," + chunkZ + " -> " + nationId + "]";
    }
}
