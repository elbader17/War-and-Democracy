package com.modserver.claim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory claim index: dimension -> (chunk key -> claim).
 *
 * <p>It is the hot source of truth; it is serialized to JSON for persistence.</p>
 */
public final class ClaimIndex {

    private final Map<String, Map<Long, Claim>> byDimension = new HashMap<>();
    private final Map<UUID, Integer> countsByNation = new HashMap<>();

    /** Returns the claim of that chunk, or null if it is free. */
    public Claim claimAt(String dimension, int chunkX, int chunkZ) {
        Map<Long, Claim> dimensionClaims = byDimension.get(dimension);
        if (dimensionClaims == null) {
            return null;
        }
        return dimensionClaims.get(Claim.pack(chunkX, chunkZ));
    }

    public boolean isFree(String dimension, int chunkX, int chunkZ) {
        return claimAt(dimension, chunkX, chunkZ) == null;
    }

    /** Claims a chunk. Returns false if it was already claimed. */
    public boolean claim(Claim claim) {
        Map<Long, Claim> dimensionClaims =
                byDimension.computeIfAbsent(claim.dimension(), key -> new HashMap<>());
        if (dimensionClaims.containsKey(claim.chunkKey())) {
            return false;
        }
        dimensionClaims.put(claim.chunkKey(), claim);
        countsByNation.merge(claim.nationId(), 1, Integer::sum);
        return true;
    }

    /** Releases a chunk. Returns the released claim or null. */
    public Claim release(String dimension, int chunkX, int chunkZ) {
        Map<Long, Claim> dimensionClaims = byDimension.get(dimension);
        if (dimensionClaims == null) {
            return null;
        }
        Claim released = dimensionClaims.remove(Claim.pack(chunkX, chunkZ));
        if (released != null) {
            decrementCount(released.nationId());
        }
        return released;
    }

    /** Changes the owner of an indexed claim and its economic territory counts together. */
    public void transfer(Claim claim, UUID newOwner) {
        if (claimAt(claim.dimension(), claim.chunkX(), claim.chunkZ()) != claim) {
            throw new IllegalArgumentException("The claim is not indexed");
        }
        decrementCount(claim.nationId());
        claim.setNationId(newOwner);
        countsByNation.merge(newOwner, 1, Integer::sum);
    }

    private void decrementCount(UUID nation) {
        countsByNation.computeIfPresent(nation, (id, count) -> count == 1 ? null : count - 1);
    }

    /** Number of chunks owned by a nation (across all dimensions). */
    public int chunkCount(UUID nationId) {
        return countsByNation.getOrDefault(nationId, 0);
    }

    /** Number of chunks owned by a nation in one dimension. */
    public int chunkCount(UUID nationId, String dimension) {
        Map<Long, Claim> dimensionClaims = byDimension.get(dimension);
        if (dimensionClaims == null) {
            return 0;
        }
        int total = 0;
        for (Claim claim : dimensionClaims.values()) {
            if (claim.nationId().equals(nationId)) {
                total++;
            }
        }
        return total;
    }

    /**
     * Checks whether a chunk touches the territory of a nation.
     *
     * <p>The territory is contiguous: a chunk can only be claimed when it is
     * next to a chunk the nation already owns (the eight surrounding chunks
     * count, so the corners of a block of land also touch).</p>
     *
     * @param nationId  nation that would own the chunk
     * @param dimension dimension of the chunk
     * @param chunkX    X of the chunk to check
     * @param chunkZ    Z of the chunk to check
     * @return true if the chunk is next to the territory of the nation
     */
    public boolean isAdjacentTo(UUID nationId, String dimension, int chunkX, int chunkZ) {
        Map<Long, Claim> dimensionClaims = byDimension.get(dimension);
        if (dimensionClaims == null) {
            return false;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                Claim neighbour = dimensionClaims.get(Claim.pack(chunkX + dx, chunkZ + dz));
                if (neighbour != null && neighbour.nationId().equals(nationId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Claims of a nation. */
    public List<Claim> claimsOf(UUID nationId) {
        List<Claim> result = new ArrayList<>();
        for (Map<Long, Claim> dimensionClaims : byDimension.values()) {
            for (Claim claim : dimensionClaims.values()) {
                if (claim.nationId().equals(nationId)) {
                    result.add(claim);
                }
            }
        }
        return result;
    }

    /** All the claims of a dimension. */
    public Collection<Claim> claimsIn(String dimension) {
        Map<Long, Claim> dimensionClaims = byDimension.get(dimension);
        return dimensionClaims == null ? List.of() : dimensionClaims.values();
    }

    public int total() {
        int total = 0;
        for (Map<Long, Claim> dimensionClaims : byDimension.values()) {
            total += dimensionClaims.size();
        }
        return total;
    }

    /** Claims within a radius (in chunks) around a point, in a dimension. */
    public List<Claim> claimsNear(String dimension, int centerX, int centerZ, int radiusChunks) {
        List<Claim> result = new ArrayList<>();
        for (Claim claim : claimsIn(dimension)) {
            if (Math.abs(claim.chunkX() - centerX) <= radiusChunks
                    && Math.abs(claim.chunkZ() - centerZ) <= radiusChunks) {
                result.add(claim);
            }
        }
        return result;
    }

    /** Replaces the full content (load from disk). */
    public void replaceAll(Collection<Claim> claims) {
        byDimension.clear();
        countsByNation.clear();
        for (Claim claim : claims) {
            claim(claim);
        }
    }

    public Collection<Claim> all() {
        List<Claim> all = new ArrayList<>();
        for (Map<Long, Claim> dimensionClaims : byDimension.values()) {
            all.addAll(dimensionClaims.values());
        }
        return all;
    }
}
