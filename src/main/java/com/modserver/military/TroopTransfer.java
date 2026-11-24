package com.modserver.military;

import com.modserver.NationService;
import com.modserver.claim.Claim;
import com.modserver.nation.Nation;
import com.modserver.nation.NationRelation;

import java.util.StringJoiner;
import java.util.UUID;

/** A gift traveling from its sender's garrison. All times use the overworld game clock. */
public record TroopTransfer(UUID recipient, String dimension, int fromX, int fromZ, int toX, int toZ,
                            TroopCounts troops, long dueAt, Leg leg, int secondsPerChunk) {
    public static final int MINUTES_PER_CHUNK = 10;
    private static final int SECONDS_PER_CHUNK = MINUTES_PER_CHUNK * 60;
    public enum Leg { OUTBOUND, RETURNING, WAITING }

    public TroopTransfer {
        if (troops.total() == 0 || (fromX == toX && fromZ == toZ)) throw new IllegalArgumentException("Empty shipment or route");
        if (secondsPerChunk <= 0) throw new IllegalArgumentException("Invalid travel time per chunk");
    }

    public long distance() { return Math.abs((long)fromX - toX) + Math.abs((long)fromZ - toZ); }
    public static long travelSeconds(long distance) { return distance * SECONDS_PER_CHUNK; }
    public long legTicks() { return distance() * secondsPerChunk * 20; }

    /** Compact units shared by the tactical map and the server's chat confirmations. */
    public static String formatDuration(long seconds) {
        var parts = new StringJoiner(" ");
        if (seconds >= 3600) parts.add(seconds / 3600 + " h");
        long minutes = seconds / 60 % 60;
        if (minutes > 0) parts.add(minutes + " min");
        if (seconds % 60 != 0 || seconds == 0) parts.add(seconds % 60 + " s");
        return parts.toString();
    }

    public static boolean allied(Nation sender, Nation recipient) {
        return sender != null && recipient != null && !sender.id().equals(recipient.id())
                && sender.relationWith(recipient.id()) == NationRelation.ALLY && recipient.relationWith(sender.id()) == NationRelation.ALLY;
    }

    static ArmyCombat.Result dispatch(NationService service, Nation sender, Claim from, Claim to,
                                            int quantity, TroopBranch branch, long now) {
        if (from == null || !from.nationId().equals(sender.id())) return new ArmyCombat.Result(false, "origin");
        var recipient = to == null ? null : service.nations().byId(to.nationId()).orElse(null);
        if (!allied(sender, recipient) || !from.dimension().equals(to.dimension())) return new ArmyCombat.Result(false, "allyTarget");
        if (quantity <= 0 || quantity > from.forces().count(branch)) return new ArmyCombat.Result(false, "branchLow");
        if ((long)to.armies() + quantity > Integer.MAX_VALUE) return new ArmyCombat.Result(false, "capacity");
        long distance = ArmyCombat.distance(from, to);
        long seconds = travelSeconds(distance);
        var troops = from.forces().take(quantity, branch);
        var transfer = new TroopTransfer(recipient.id(), from.dimension(), from.chunkX(), from.chunkZ(), to.chunkX(), to.chunkZ(),
                troops, now + seconds * 20, Leg.OUTBOUND, SECONDS_PER_CHUNK);
        from.setForces(from.forces().minus(troops));
        sender.addTransfer(transfer);
        service.markDirty();
        return new ArmyCombat.Result(true, "sent", quantity, recipient.name(), distance, formatDuration(seconds));
    }

    /** Resolve due legs without loading chunks, including when both owners are offline. */
    public static void advance(NationService service, long now) {
        for (var sender : service.allNations()) {
            TroopTransfer trip;
            while ((trip = sender.pollDueTransfer(now)) != null) {
                var recipient = service.nations().byId(trip.recipient).orElse(null);
                var claims = service.claims();
                var to = claims.claimAt(trip.dimension, trip.toX, trip.toZ);
                var from = claims.claimAt(trip.dimension, trip.fromX, trip.fromZ);
                String report;
                if (trip.leg == Leg.OUTBOUND) {
                    if (allied(sender, recipient) && to != null && to.nationId().equals(trip.recipient)
                            && (long)to.armies() + trip.troops.total() <= Integer.MAX_VALUE) {
                        to.setForces(to.forces().plus(trip.troops));
                        report = "arrived";
                    } else {
                        sender.addTransfer(trip.schedule(Leg.RETURNING, now + travelSeconds(trip.distance()) * 20));
                        report = "turnedBack";
                    }
                } else if (trip.unloadAtHome(sender, from)) {
                    report = "returned";
                } else {
                    sender.addTransfer(trip.schedule(Leg.WAITING, now + 20));
                    report = trip.leg == Leg.WAITING ? null : "waitForRoom";
                }
                if (report != null) service.reportTransfer(sender, recipient, trip, report);
                service.markDirty();
            }
        }
    }

    private TroopTransfer schedule(Leg next, long at) {
        return new TroopTransfer(recipient, dimension, fromX, fromZ, toX, toZ, troops, at, next, SECONDS_PER_CHUNK);
    }

    private boolean unloadAtHome(Nation sender, Claim from) {
        int room = from != null && from.nationId().equals(sender.id()) ? Integer.MAX_VALUE - from.armies() : 0;
        var reserve = sender.economy().reserveForces();
        if ((long)room + Integer.MAX_VALUE - reserve.total() < troops.total()) return false;
        var housed = troops.take(Math.min(room, troops.total()), null);
        if (housed.total() > 0) from.setForces(from.forces().plus(housed));
        sender.economy().setReserveForces(reserve.plus(troops.minus(housed)));
        return true;
    }
}
