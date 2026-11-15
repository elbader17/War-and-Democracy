package com.modserver.dispute;

import java.util.UUID;

/**
 * Open dispute over a chunk between two nations.
 *
 * <p>The dispute does not "own" the chunk: the claim stays with the defending nation
 * until the dispute is resolved (in favour of the defender or in favour of the
 * challenger).</p>
 *
 * <p>Times are in <i>game ticks</i> (the server {@code gameTime}), not real time: this
 * way the module behaves the same on a lagging server and is easy to simulate in the
 * GameTests.</p>
 */
public final class Dispute {

    private final long id;
    private final String dimension;
    private final int chunkX;
    private final int chunkZ;
    private final UUID challengerNationId;
    private final UUID defenderNationId;
    private final long openedAt;
    private final long deadline;
    private DisputeStatus status;
    private long resolvedAt = -1L;
    private String resolvedBy = "";

    public Dispute(long id, String dimension, int chunkX, int chunkZ,
                   UUID challengerNationId, UUID defenderNationId,
                   long openedAt, long deadline) {
        this.id = id;
        this.dimension = dimension;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.challengerNationId = challengerNationId;
        this.defenderNationId = defenderNationId;
        this.openedAt = openedAt;
        this.deadline = deadline;
        this.status = DisputeStatus.OPEN;
    }

    public long id() {
        return id;
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

    /** Nation that opens the dispute (the one who wants the chunk). */
    public UUID challengerNationId() {
        return challengerNationId;
    }

    /** Nation that owns the disputed chunk (the one who has to defend it). */
    public UUID defenderNationId() {
        return defenderNationId;
    }

    /** Game tick at which the dispute was opened. */
    public long openedAt() {
        return openedAt;
    }

    /** Game tick at which the defense window expires. */
    public long deadline() {
        return deadline;
    }

    public DisputeStatus status() {
        return status;
    }

    public void setStatus(DisputeStatus status) {
        this.status = status;
    }

    /** Game tick at which it was resolved, or -1 if it is still open. */
    public long resolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(long resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    /** How it was resolved: {@code defense}, {@code expiration}, {@code admin}, {@code garrison} or {@code cancellation}. */
    public String resolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(String resolvedBy) {
        this.resolvedBy = resolvedBy == null ? "" : resolvedBy;
    }

    /** Key of the disputed chunk (same encoding as {@code Claim.chunkKey()}). */
    public long chunkKey() {
        return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }

    /** Seconds left in the defense window (0 if it already expired). */
    public long remainingSeconds(long gameTime) {
        return Math.max(0L, (deadline - gameTime) / 20L);
    }

    @Override
    public String toString() {
        return "Dispute[" + id + " " + dimension + " " + chunkX + "," + chunkZ
                + " " + status + "]";
    }
}
