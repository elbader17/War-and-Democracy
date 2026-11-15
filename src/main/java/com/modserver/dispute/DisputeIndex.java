package com.modserver.dispute;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory dispute index.
 *
 * <p>Besides the full history (to be able to query and resolve by id), it keeps a fast
 * index of the <b>open</b> dispute of every chunk: there cannot be two open disputes
 * over the same chunk.</p>
 */
public final class DisputeIndex {

    private final Map<Long, Dispute> byId = new LinkedHashMap<>();
    /** key dimension+chunk -> id of the open dispute. */
    private final Map<String, Long> openByChunk = new HashMap<>();
    private long nextId = 1L;

    /** Registers a dispute and returns it. */
    public Dispute add(Dispute dispute) {
        byId.put(dispute.id(), dispute);
        if (dispute.status().isOpen()) {
            openByChunk.put(chunkKey(dispute.dimension(), dispute.chunkX(), dispute.chunkZ()),
                    dispute.id());
        }
        if (dispute.id() >= nextId) {
            nextId = dispute.id() + 1L;
        }
        return dispute;
    }

    /** Id that will be used for the next dispute. */
    public long nextId() {
        return nextId;
    }

    public Dispute get(long id) {
        return byId.get(id);
    }

    /** Open dispute over that chunk, or null. */
    public Dispute openAt(String dimension, int chunkX, int chunkZ) {
        Long id = openByChunk.get(chunkKey(dimension, chunkX, chunkZ));
        return id == null ? null : byId.get(id);
    }

    public Collection<Dispute> all() {
        return byId.values();
    }

    /** Disputes that are still open. */
    public List<Dispute> open() {
        List<Dispute> result = new ArrayList<>();
        for (Dispute dispute : byId.values()) {
            if (dispute.status().isOpen()) {
                result.add(dispute);
            }
        }
        return result;
    }

    /** Disputes of a nation (as challenger or as defender). */
    public List<Dispute> ofNation(java.util.UUID nationId) {
        List<Dispute> result = new ArrayList<>();
        for (Dispute dispute : byId.values()) {
            if (dispute.challengerNationId().equals(nationId)
                    || dispute.defenderNationId().equals(nationId)) {
                result.add(dispute);
            }
        }
        return result;
    }

    public int count() {
        return byId.size();
    }

    /** Replaces the full content (load from disk). */
    public void replaceAll(Collection<Dispute> disputes) {
        byId.clear();
        openByChunk.clear();
        nextId = 1L;
        for (Dispute dispute : disputes) {
            add(dispute);
        }
    }

    /** Reindexes the open disputes (after a status change). */
    public void reindex(Dispute dispute) {
        String key = chunkKey(dispute.dimension(), dispute.chunkX(), dispute.chunkZ());
        if (dispute.status().isOpen()) {
            openByChunk.put(key, dispute.id());
        } else {
            openByChunk.remove(key);
        }
    }

    /** Removes from the open index a dispute that is no longer open. */
    public void removeOpen(String dimension, int chunkX, int chunkZ) {
        openByChunk.remove(chunkKey(dimension, chunkX, chunkZ));
    }

    private static String chunkKey(String dimension, int chunkX, int chunkZ) {
        return dimension + ":" + chunkX + ":" + chunkZ;
    }
}
