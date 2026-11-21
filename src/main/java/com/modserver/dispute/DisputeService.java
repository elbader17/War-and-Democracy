package com.modserver.dispute;

import com.modserver.claim.Claim;
import com.modserver.claim.ClaimIndex;
import com.modserver.nation.Nation;
import com.modserver.nation.NationManager;
import com.modserver.nation.NationRelation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Game rules for disputes between nations.
 *
 * <p>Pure domain class: it does not depend on Minecraft, only on the nation and claim
 * indexes. This way the state machine can be verified entirely in the GameTests
 * without needing a real player.</p>
 *
 * <p>Lifecycle of a dispute:</p>
 * <ol>
 *   <li>A nation opens a dispute over a chunk of another nation (N minutes defense
 *       window).</li>
 *   <li>The defending nation can <b>defend</b>: the chunk stays with it.</li>
 *   <li>If nobody defends before the window expires, the chunk goes to the
 *       challenging nation.</li>
 *   <li>An administrator can resolve the dispute manually at any time.</li>
 * </ol>
 */
public final class DisputeService {

    /** Result of an operation over disputes. */
    public record Outcome(boolean success, String message, Dispute dispute) {

        public static Outcome ok(String message, Dispute dispute) {
            return new Outcome(true, message, dispute);
        }

        public static Outcome fail(String message) {
            return new Outcome(false, message, null);
        }
    }

    /**
     * Dispute parameters (the ones set by the server configuration).
     *
     * @param windowTicks           defense window under normal conditions
     * @param nationCooldownTicks  cooldown per challenging nation
     * @param chunkCooldownTicks   cooldown per chunk
     * @param warWindowTicks       defense window when the two nations are at war
     * @param warIgnoresCooldown   whether at war the challenger does not have to wait the cooldown
     */
    public record Rules(long windowTicks, long nationCooldownTicks, long chunkCooldownTicks,
                        long warWindowTicks, boolean warIgnoresCooldown) {
    }

    private final NationManager nations;
    private final ClaimIndex claims;
    private final DisputeIndex disputes;

    public DisputeService(NationManager nations, ClaimIndex claims, DisputeIndex disputes) {
        this.nations = nations;
        this.claims = claims;
        this.disputes = disputes;
    }

    // ------------------------------------------------------------------- opening

    /**
     * Opens a dispute by {@code challengerNation} over a chunk of another nation.
     *
     * <p>The rules adapt to the relation between the two nations: at war the defense
     * window is shorter and (optionally) there is no per-challenger cooldown.</p>
     */
    public Outcome open(UUID challengerNation, String dimension, int chunkX, int chunkZ,
                        long gameTime, Rules rules) {
        return open(challengerNation, dimension, chunkX, chunkZ, gameTime, rules, false);
    }

    /** A paid attack can declare war as part of successfully opening the dispute. */
    public Outcome open(UUID challengerNation, String dimension, int chunkX, int chunkZ,
                        long gameTime, Rules rules, boolean declareWar) {
        Nation challenger = nations.byId(challengerNation).orElse(null);
        if (challenger == null) {
            return Outcome.fail("Your nation no longer exists");
        }

        Claim claim = claims.claimAt(dimension, chunkX, chunkZ);
        if (claim == null) {
            return Outcome.fail("That chunk is not claimed by anyone");
        }
        if (claim.nationId().equals(challengerNation)) {
            return Outcome.fail("That chunk already belongs to your nation");
        }

        if (disputes.openAt(dimension, chunkX, chunkZ) != null) {
            return Outcome.fail("There is already an open dispute on that chunk");
        }

        Nation defender = nations.byId(claim.nationId()).orElse(null);
        if (defender != null
                && challenger.relationWith(defender.id()).blocksDisputes()) {
            return Outcome.fail("There is a peace agreement with " + defender.name()
                    + ": their chunks cannot be disputed");
        }

        boolean atWar = defender != null
                && (declareWar || challenger.relationWith(defender.id()) == NationRelation.WAR);
        long windowTicks = atWar ? rules.warWindowTicks() : rules.windowTicks();
        long nationWait = nationWaitTicks(challengerNation, gameTime, rules, atWar);
        if (nationWait > 0) {
            return Outcome.fail("Your nation must wait " + (nationWait + 19) / 20
                    + " s before opening another dispute");
        }
        long chunkWait = chunkWaitTicks(dimension, chunkX, chunkZ, gameTime, rules);
        if (chunkWait > 0) {
            return Outcome.fail("That chunk has a recent dispute: wait " + (chunkWait + 19) / 20 + " s");
        }

        long deadline = gameTime + windowTicks;
        Dispute dispute = new Dispute(disputes.nextId(), dimension, chunkX, chunkZ,
                challengerNation, claim.nationId(), gameTime, deadline);
        disputes.add(dispute);
        if (declareWar && defender != null) {
            challenger.setRelation(defender.id(), NationRelation.WAR);
            defender.setRelation(challenger.id(), NationRelation.WAR);
        }
        String extra = atWar ? " (at war: shorter window)" : "";
        return Outcome.ok("Dispute #" + dispute.id() + " opened over chunk "
                + chunkX + ", " + chunkZ + extra, dispute);
    }

    public long nationWaitTicks(UUID nation, long now, Rules rules, boolean atWar) {
        if (rules.nationCooldownTicks() <= 0 || (atWar && rules.warIgnoresCooldown())) return 0;
        return remainingWait(lastDisputeByNation(nation), now, rules.nationCooldownTicks());
    }

    public long chunkWaitTicks(String dimension, int x, int z, long now, Rules rules) {
        if (rules.chunkCooldownTicks() <= 0) return 0;
        return remainingWait(lastDisputeByChunk(dimension, x, z), now, rules.chunkCooldownTicks());
    }

    private static long remainingWait(long last, long now, long cooldown) {
        return last < 0 ? 0 : Math.max(0, cooldown - (now - last));
    }

    // ------------------------------------------------------------------- defense

    /** The defending nation answers in time: the claim is kept. */
    public Outcome defend(UUID defenderNation, String dimension, int chunkX, int chunkZ,
                          long gameTime) {
        Dispute dispute = disputes.openAt(dimension, chunkX, chunkZ);
        if (dispute == null) {
            return Outcome.fail("There is no open dispute on that chunk");
        }
        if (!dispute.defenderNationId().equals(defenderNation)) {
            return Outcome.fail("That chunk does not belong to your nation");
        }
        if (gameTime > dispute.deadline()) {
            return Outcome.fail("The defense window has already expired");
        }

        dispute.setStatus(DisputeStatus.DEFENDED);
        dispute.setResolvedAt(gameTime);
        dispute.setResolvedBy("defense");
        disputes.reindex(dispute);
        return Outcome.ok("Dispute #" + dispute.id() + " defended: the chunk "
                + chunkX + ", " + chunkZ + " is still yours", dispute);
    }

    // ------------------------------------------------------------------ expiry

    /**
     * Resolves the disputes whose defense window has expired.
     *
     * <p>The chunk goes to the challenging nation. If the chunk is no longer claimed
     * (the defending nation released it), the dispute is cancelled.</p>
     *
     * @return the disputes resolved in this pass
     */
    public List<Dispute> expire(long gameTime) {
        List<Dispute> resolved = new ArrayList<>();
        for (Dispute dispute : disputes.open()) {
            if (gameTime <= dispute.deadline()) {
                continue;
            }
            Claim claim = claims.claimAt(dispute.dimension(), dispute.chunkX(), dispute.chunkZ());
            if (claim == null || !claim.nationId().equals(dispute.defenderNationId())) {
                dispute.setStatus(DisputeStatus.CANCELLED);
                dispute.setResolvedAt(gameTime);
                dispute.setResolvedBy("cancellation");
                disputes.reindex(dispute);
                resolved.add(dispute);
                continue;
            }
            claims.transfer(claim, dispute.challengerNationId());
            dispute.setStatus(DisputeStatus.TRANSFERRED);
            dispute.setResolvedAt(gameTime);
            dispute.setResolvedBy("expiration");
            disputes.reindex(dispute);
            resolved.add(dispute);
        }
        return resolved;
    }

    // -------------------------------------------------------------------- admin

    /**
     * Manual resolution of a dispute.
     *
     * <p>The administrator can resolve again a dispute that was already defended (that
     * is their right: a player decision does not bind the server administration). What
     * cannot be done is touching again a dispute whose chunk already changed hands.</p>
     *
     * @param keep {@code true} leaves the chunk with the defender, {@code false} transfers
     *             it to the challenging nation
     */
    public Outcome resolve(long id, boolean keep, long gameTime) {
        Dispute dispute = disputes.get(id);
        if (dispute == null) {
            return Outcome.fail("Dispute #" + id + " does not exist");
        }
        if (dispute.status() == DisputeStatus.TRANSFERRED
                || dispute.status() == DisputeStatus.CANCELLED) {
            return Outcome.fail("Dispute #" + id + " was already resolved ("
                    + dispute.status() + ")");
        }

        if (keep) {
            dispute.setStatus(DisputeStatus.KEPT);
            dispute.setResolvedAt(gameTime);
            dispute.setResolvedBy("admin");
            disputes.reindex(dispute);
            return Outcome.ok("Dispute #" + id + " resolved: the chunk stays with its defender", dispute);
        }

        Claim claim = claims.claimAt(dispute.dimension(), dispute.chunkX(), dispute.chunkZ());
        if (claim == null) {
            dispute.setStatus(DisputeStatus.CANCELLED);
            dispute.setResolvedAt(gameTime);
            dispute.setResolvedBy("admin");
            disputes.reindex(dispute);
            return Outcome.ok("Dispute #" + id + " cancelled: the chunk was no longer claimed", dispute);
        }
        claims.transfer(claim, dispute.challengerNationId());
        dispute.setStatus(DisputeStatus.TRANSFERRED);
        dispute.setResolvedAt(gameTime);
        dispute.setResolvedBy("admin");
        disputes.reindex(dispute);
        return Outcome.ok("Dispute #" + id + " resolved: the chunk goes to the challenging nation", dispute);
    }

    /** Cancels the open dispute of a chunk (for example if the claim is released). */
    public Dispute cancelOpenAt(String dimension, int chunkX, int chunkZ, long gameTime,
                                String reason) {
        Dispute dispute = disputes.openAt(dimension, chunkX, chunkZ);
        if (dispute == null) {
            return null;
        }
        dispute.setStatus(DisputeStatus.CANCELLED);
        dispute.setResolvedAt(gameTime);
        dispute.setResolvedBy(reason);
        disputes.reindex(dispute);
        return dispute;
    }

    // ------------------------------------------------------------------ utility

    /** Tick at which the nation opened its last dispute (-1 if never). */
    public long lastDisputeByNation(UUID nationId) {
        long last = -1L;
        for (Dispute dispute : disputes.all()) {
            if (dispute.challengerNationId().equals(nationId) && dispute.openedAt() > last) {
                last = dispute.openedAt();
            }
        }
        return last;
    }

    /** Tick at which that chunk was last disputed (-1 if never). */
    public long lastDisputeByChunk(String dimension, int chunkX, int chunkZ) {
        long last = -1L;
        for (Dispute dispute : disputes.all()) {
            if (dispute.dimension().equals(dimension)
                    && dispute.chunkX() == chunkX && dispute.chunkZ() == chunkZ
                    && dispute.openedAt() > last) {
                last = dispute.openedAt();
            }
        }
        return last;
    }

    public DisputeIndex index() {
        return disputes;
    }
}
