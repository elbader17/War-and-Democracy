package com.modserver.military;

import com.modserver.NationService;
import com.modserver.ServerLanguage;
import com.modserver.claim.Claim;
import com.modserver.config.ModServerConfig;
import com.modserver.dispute.Dispute;
import com.modserver.dispute.DisputeStatus;
import com.modserver.economy.NationEconomy;
import com.modserver.nation.Nation;
import com.modserver.nation.NationRelation;

import java.util.function.IntSupplier;

/** All orders are validated and resolved on the server thread. */
public final class ArmyCombat {
    public enum Order { BUY, DEPLOY, MOVE, SEND, EXCHANGE, ROLL, FIRE, BUY_NUCLEAR, NUKE;
        public boolean startsCombat() { return this == ROLL || this == FIRE || this == NUKE; }
    }
    @FunctionalInterface
    public interface DuelListener {
        void onDuel(TroopBranch attacker, TroopBranch defender, boolean attackerWon);
    }

    public record Result(boolean success, String key, Object... values) {
        public String text(ServerLanguage language) { return language.text("tac." + key, values); }
    }
    private static Result fail(String key) { return new Result(false, key); }
    public static long price() { return ModServerConfig.ARMY_PRICE.get() * NationEconomy.CENTS; }
    public static long nuclearPrice() { return ModServerConfig.NUCLEAR_PRICE.get() * NationEconomy.CENTS; }
    public static long distance(Claim a, Claim b) {
        return Math.abs((long)a.chunkX() - b.chunkX()) + Math.abs((long)a.chunkZ() - b.chunkZ());
    }

    public static int missileDamage(long distance) {
        return distance >= 1 && distance <= 3 ? 4 - (int) distance : 0;
    }

    public static String missileTargetProblem(long distance, int defenders) {
        int damage = missileDamage(distance);
        if (damage == 0) return "missileDistance";
        return defenders <= damage ? "occupation" : null;
    }

    /** Both endpoints must already be owned by the acting nation. */
    public static String moveProblem(long distance, int atOrigin, int atDestination, int quantity) {
        if (distance != 1) return "adjacent";
        if (quantity < 1 || quantity > 9999) return "quantity";
        if (quantity >= atOrigin) return "moveLeave";
        if ((long) atDestination + quantity > Integer.MAX_VALUE) return "capacity";
        return null;
    }

    public static Result execute(NationService service, Nation actor, String dimension, Order order, int sx, int sz,
                                 int tx, int tz, int quantity, long now) {
        return execute(service, actor, dimension, order, sx, sz, tx, tz, quantity, now,
                order == Order.BUY ? TroopBranch.GROUND : null);
    }

    public static Result execute(NationService service, Nation actor, String dimension, Order order, int sx, int sz,
                                 int tx, int tz, int quantity, long now, TroopBranch branch) {
        if (actor == null) return fail("owner");
        if (order == Order.SEND && quantity < 1) return fail("convoy.quantity");
        if (quantity < 1 || (order != Order.ROLL && order != Order.SEND && quantity > 9999)) return fail("quantity");
        var eco = actor.economy();
        var from = service.claims().claimAt(dimension, sx, sz);
        if (order == Order.SEND) return TroopTransfer.dispatch(service, actor, from, service.claims().claimAt(dimension, tx, tz),
                quantity, branch, now);
        Result result;
        if (order == Order.BUY) {
            if (branch == null) return fail("chooseBranch");
            long cost = quantity * price();
            if (eco.balance() < cost) return fail("funds");
            if ((long)eco.garrisonPoints() + quantity > Integer.MAX_VALUE) return fail("capacity");
            eco.setBalance(eco.balance() - cost);
            eco.setReserveForces(eco.reserveForces().plus(TroopCounts.of(branch, quantity)));
            result = new Result(true, "bought", quantity);
        } else if (from == null || !from.nationId().equals(actor.id())) {
            return fail("origin");
        } else if (order == Order.DEPLOY) {
            if (eco.reserveForces().count(branch) < quantity) return fail("reserveLow");
            if ((long)from.armies() + quantity > Integer.MAX_VALUE) return fail("capacity");
            var troops = eco.reserveForces().take(quantity, branch);
            eco.setReserveForces(eco.reserveForces().minus(troops));
            from.setForces(from.forces().plus(troops));
            result = new Result(true, "placed", quantity, sx, sz);
        } else if (order == Order.MOVE) {
            var destination = service.claims().claimAt(dimension, tx, tz);
            if (destination == null || !actor.id().equals(destination.nationId())) return fail("moveDestination");
            String problem = moveProblem(distance(from, destination), from.armies(), destination.armies(), quantity);
            if (problem != null) return fail(problem);
            if (quantity > from.forces().count(branch)) return fail("branchLow");
            var troops = from.forces().take(quantity, branch);
            from.setForces(from.forces().minus(troops));
            destination.setForces(destination.forces().plus(troops));
            result = new Result(true, "moved", quantity, from.armies(), destination.armies());
        } else if (order == Order.BUY_NUCLEAR) {
            long cost = nuclearPrice();
            if (eco.balance() < cost) return fail("funds");
            if (from.nuclearMissiles() == Integer.MAX_VALUE) return fail("capacity");
            eco.setBalance(eco.balance() - cost);
            from.setNuclearMissiles(from.nuclearMissiles() + 1);
            result = new Result(true, "nuclearBought", sx, sz);
        } else if (order == Order.EXCHANGE) {
            if (from.armies() < 7 || from.missiles() == Integer.MAX_VALUE) return fail("exchangeLow");
            from.setForces(from.forces().minus(from.forces().take(6, null)));
            from.setMissiles(from.missiles() + 1);
            result = new Result(true, "exchanged", sx, sz);
        } else {
            var target = service.claims().claimAt(dimension, tx, tz);
            if (target == null || actor.id().equals(target.nationId())) return fail("target");
            var defender = service.nations().byId(target.nationId()).orElse(null);
            if (defender == null || actor.relationWith(defender.id()).blocksDisputes()) return fail("pact");
            long distance = distance(from, target);
            if (order == Order.NUKE) {
                if (distance < 1 || distance > 3) return fail("missileDistance");
                if (from.nuclearMissiles() == 0) return fail("noNuclear");
                from.setNuclearMissiles(from.nuclearMissiles() - 1);
                long casualties = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int x = tx + dx, z = tz + dz;
                        var cell = service.claims().claimAt(dimension, x, z);
                        if (cell != null) {
                            casualties += cell.armies();
                            cell.setForces(TroopCounts.EMPTY);
                        }
                        var battle = service.disputes().openAt(dimension, x, z);
                        if (battle != null) {
                            if (battle.assault() != null) casualties += battle.assault().attackers();
                            // Destroy committed troops before closing the dispute: none return to their origin.
                            battle.setAssault(null);
                            service.disputeService().cancelOpenAt(dimension, x, z, now, "nuclear");
                        }
                    }
                }
                result = new Result(true, "nuclearHit", tx, tz, casualties);
            } else if (order == Order.FIRE) {
                if (from.missiles() == 0) return fail("noMissile");
                String problem = missileTargetProblem(distance, target.armies());
                int damage = missileDamage(distance);
                if (problem != null) return new Result(false, problem, target.armies(), damage + 1);
                from.setMissiles(from.missiles() - 1);
                target.setForces(target.forces().minus(target.forces().take(damage, null)));
                result = new Result(true, "hit", tx, tz, damage);
            } else {
                if (distance != 1) return fail("adjacent");
                if (quantity > from.armies()) return fail("commit");
                if (quantity > from.forces().count(branch)) return fail("branchLow");
                var battle = service.disputes().openAt(dimension, tx, tz);
                if (battle != null && (battle.assault() != null || !battle.challengerNationId().equals(actor.id())
                        || !battle.defenderNationId().equals(defender.id()))) return fail("engaged");
                if (battle == null) {
                    battle = new Dispute(service.disputes().nextId(), dimension, tx, tz, actor.id(), defender.id(),
                            now, now + Dispute.DUEL_TICKS);
                    service.disputes().add(battle);
                }
                var troops = from.forces().take(quantity, branch);
                from.setForces(from.forces().minus(troops));
                battle.setAssault(new Dispute.Assault(sx, sz, troops, now + Dispute.DUEL_TICKS));
                result = new Result(true, "march", quantity, target.armies());
            }
            actor.setRelation(defender.id(), NationRelation.WAR);
            defender.setRelation(actor.id(), NationRelation.WAR);
            service.markMapDirty();
        }
        service.markDirty();
        return result;
    }
    /** Advance at most one duel. A delayed tick or a reload never rolls an entire battle at once. */
    public static boolean advance(NationService service, Dispute battle, long now, IntSupplier die) {
        return advance(service, battle, now, die, java.util.concurrent.ThreadLocalRandom.current()::nextInt);
    }

    public static boolean advance(NationService service, Dispute battle, long now, IntSupplier die,
                                  java.util.function.IntUnaryOperator pickIndex) {
        return advance(service, battle, now, die, pickIndex, (a, d, won) -> {});
    }

    public static boolean advance(NationService service, Dispute battle, long now, IntSupplier die,
                                  java.util.function.IntUnaryOperator pickIndex, DuelListener listener) {
        var force = battle.assault();
        if (!battle.status().isOpen() || force == null || now < force.nextDuel()) return false;
        var target = service.claims().claimAt(battle.dimension(), battle.chunkX(), battle.chunkZ());
        var actor = service.nations().byId(battle.challengerNationId()).orElse(null);
        if (target == null || !target.nationId().equals(battle.defenderNationId()) || actor == null
                || actor.relationWith(battle.defenderNationId()).blocksDisputes()) {
            service.disputeService().cancelOpenAt(battle.dimension(), battle.chunkX(), battle.chunkZ(), now, "recall");
        } else {
            var attackers = force.forces();
            if (target.armies() > 0) {
                var attackArm = attackers.branchAt(pickIndex.applyAsInt(attackers.total()));
                var defenseArm = target.forces().branchAt(pickIndex.applyAsInt(target.armies()));
                int attackDie = attackArm.duelRollAgainst(defenseArm, die.getAsInt());
                int defenseDie = defenseArm.duelRollAgainst(attackArm, die.getAsInt());
                listener.onDuel(attackArm, defenseArm, attackDie > defenseDie);
                if (attackDie > defenseDie) target.setForces(target.forces().minus(TroopCounts.of(defenseArm, 1)));
                else attackers = attackers.minus(TroopCounts.of(attackArm, 1));
            }
            if (target.armies() == 0 || attackers.total() == 0) {
                if (target.armies() == 0) {
                    service.claims().transfer(target, actor.id());
                    target.setForces(attackers);
                    battle.setStatus(DisputeStatus.TRANSFERRED);
                } else battle.setStatus(DisputeStatus.KEPT);
                battle.setResolvedBy("dice");
                battle.setResolvedAt(now);
                battle.setAssault(null);
                service.disputes().reindex(battle);
            } else {
                battle.setAssault(new Dispute.Assault(force.originX(), force.originZ(), attackers, now + Dispute.DUEL_TICKS));
            }
        }
        service.markDirty();
        service.markMapDirty();
        return true;
    }

    private ArmyCombat() {}
}
