package com.modserver.economy;

import com.modserver.claim.Claim;
import com.modserver.claim.ClaimIndex;
import com.modserver.nation.Nation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Economic rules of the nations: income, maintenance, defense and investments.
 *
 * <p>Pure domain class: it does not depend on Minecraft, only on the nation and the
 * claim index. The full cycle (closing the economic day) can be verified in the
 * GameTests without a real server.</p>
 *
 * <p><b>The game loop:</b></p>
 * <ol>
 *   <li>Maintenance is charged for the chunks and the investments. If the treasury
 *       cannot cover it, the nation loses the chunks farthest from its capital until it can.</li>
 *   <li>Income comes in: each chunk generates, plus the BONUSES of the farms.</li>
 *   <li>The defense is charged, and it **grows with the size of the territory** (base + per
 *       chunk + a quadratic term). If it cannot be paid, the nation is left
 *       <b>unprotected</b>: it accumulates debt and cannot defend its disputes.</li>
 *   <li>The treasury pays interest on the balance.</li>
 * </ol>
 */
public final class EconomyService {

    /** Result of an economic operation. */
    public record Outcome(boolean success, String message) {

        public static Outcome ok(String message) {
            return new Outcome(true, message);
        }

        public static Outcome fail(String message) {
            return new Outcome(false, message);
        }
    }

    /** Summary of the close of an economic day. */
    public record DailyReport(long income, long upkeep, long defenseCost, long interest,
                              int chunksLost, boolean defended, long balance, String message) {
    }

    private final ClaimIndex claims;

    public EconomyService(ClaimIndex claims) {
        this.claims = claims;
    }

    // ------------------------------------------------------------------ daily cycle

    /** Closes the economic day of a nation (charges, income and defense). */
    public DailyReport closeDay(Nation nation, EconomyRules rules, long gameTime) {
        NationEconomy economy = nation.economy();
        int chunkCount = claims.chunkCount(nation.id());

        // 1. Maintenance: charged per chunk and for the investments.
        long upkeep = chunkCount * rules.claimUpkeep()
                + rules.investmentUpkeep(economy.farmLevel(), economy.garrisonLevel(),
                        economy.treasuryLevel());
        List<Claim> lost = new ArrayList<>();
        long charged = chargeUpkeep(nation, rules, upkeep, lost);
        economy.setBalance(economy.balance() - charged);
        int chunksLost = lost.size();
        chunkCount -= chunksLost;

        // 2. Income: every chunk produces, and the farms multiply it.
        long income = chunkCount * rules.claimIncome() * economy.incomeMultiplierPercent() / 100L;
        economy.setBalance(economy.balance() + income);

        // 3. Defense: expensive if the territory is large. If unpaid, the nation is unprotected.
        long defenseCost = rules.defenseCost(chunkCount);
        long paid = 0L;
        boolean defended;
        if (economy.balance() >= economy.defenseDebt() + defenseCost) {
            paid = economy.defenseDebt() + defenseCost;
            economy.setDefenseDebt(0L);
            economy.setBalance(economy.balance() - paid);
            economy.setDefended(true);
            // The garrison is restored when the defense is paid.
            economy.setGarrisonPoints(economy.garrisonPoints() + economy.garrisonIncome());
            defended = true;
        } else {
            economy.setDefenseDebt(economy.defenseDebt() + defenseCost);
            economy.setDefended(false);
            defended = false;
        }

        // 4. Treasury interest (only over a positive balance).
        long interest = 0L;
        if (economy.balance() > 0 && economy.interestPercent() > 0) {
            interest = economy.balance() * economy.interestPercent() / 100L;
            economy.setBalance(economy.balance() + interest);
        }

        economy.setLastDay(gameTime);

        String message = "Economic day of " + nation.name() + ": +" + coins(income)
                + " in income, -" + coins(charged) + " in maintenance, -"
                + coins(defenseCost) + " in defense"
                + (chunksLost > 0 ? ", " + chunksLost + " chunks lost" : "")
                + (defended ? ", defense paid" : ", UNPROTECTED (debt " + coins(economy.defenseDebt()) + ")");
        return new DailyReport(income, charged, defenseCost, interest, chunksLost, defended,
                economy.balance(), message);
    }

    /**
     * Charges the maintenance: if the balance is not enough, the chunks farthest
     * from the capital are lost until it can be paid.
     *
     * <p>Losing a chunk reduces the upkeep by what it cost to maintain it, so the
     * nation stabilizes at a size it can afford. If the balance still ends up
     * negative (for example, because of the investments upkeep), the debt
     * prevents paying the defense and the nation is left unprotected.</p>
     *
     * @return the upkeep finally charged
     */
    private long chargeUpkeep(Nation nation, EconomyRules rules, long upkeep, List<Claim> lost) {
        NationEconomy economy = nation.economy();
        if (economy.balance() >= upkeep) {
            return upkeep;
        }

        List<Claim> owned = new ArrayList<>(claims.claimsOf(nation.id()));
        owned.sort(Comparator.comparingLong((Claim claim) -> distanceToCapital(nation, claim)).reversed());

        for (Claim claim : owned) {
            if (economy.balance() >= upkeep) {
                break;
            }
            claims.release(claim.dimension(), claim.chunkX(), claim.chunkZ());
            lost.add(claim);
            upkeep -= rules.claimUpkeep();
        }
        return Math.max(0L, upkeep);
    }

    /** Distance (Chebyshev) from the claim to the center of the nation (capital or origin). */
    private long distanceToCapital(Nation nation, Claim claim) {
        int centerX = nation.hasCapital() ? nation.capitalX() : 0;
        int centerZ = nation.hasCapital() ? nation.capitalZ() : 0;
        return Math.max(Math.abs(claim.chunkX() - centerX), Math.abs(claim.chunkZ() - centerZ));
    }

    // ------------------------------------------------------------------ investments

    /** Buys (or levels up) an investment. */
    public Outcome purchase(Nation nation, Investment investment, EconomyRules rules) {
        NationEconomy economy = nation.economy();
        int current = levelOf(economy, investment);
        if (current >= rules.maxLevel()) {
            return Outcome.fail("The investment " + investment.label() + " is already at the maximum ("
                    + rules.maxLevel() + ")");
        }
        long cost = rules.purchaseCost(investment, current);
        if (economy.balance() < cost) {
            return Outcome.fail("You do not have enough money: " + investment.label() + " costs "
                    + coins(cost) + " and you have " + coins(economy.balance()));
        }
        economy.setBalance(economy.balance() - cost);
        setLevel(economy, investment, current + 1);
        return Outcome.ok("You have invested " + coins(cost) + " in " + investment.label()
                + " (level " + (current + 1) + ")");
    }

    private static int levelOf(NationEconomy economy, Investment investment) {
        return switch (investment) {
            case FARM -> economy.farmLevel();
            case GARRISON -> economy.garrisonLevel();
            case TREASURY -> economy.treasuryLevel();
        };
    }

    private static void setLevel(NationEconomy economy, Investment investment, int level) {
        switch (investment) {
            case FARM -> economy.setFarmLevel(level);
            case GARRISON -> economy.setGarrisonLevel(level);
            case TREASURY -> economy.setTreasuryLevel(level);
        }
    }

    // -------------------------------------------------------------------- defense

    /** Pays the pending defense (and today's one) to be protected again. */
    public Outcome payDefense(Nation nation, EconomyRules rules) {
        NationEconomy economy = nation.economy();
        int chunkCount = claims.chunkCount(nation.id());
        long due = economy.defenseDebt() + rules.defenseCost(chunkCount);
        if (economy.balance() < due) {
            return Outcome.fail("Today's defense costs " + coins(due) + " and you only have "
                    + coins(economy.balance()));
        }
        economy.setBalance(economy.balance() - due);
        economy.setDefenseDebt(0L);
        economy.setDefended(true);
        economy.setGarrisonPoints(economy.garrisonPoints() + economy.garrisonIncome());
        return Outcome.ok("You have paid the defense (" + coins(due) + "). Your nation is protected");
    }

    /** How much it costs to defend the nation today (including debt). */
    public long pendingDefenseCost(Nation nation, EconomyRules rules) {
        return nation.economy().defenseDebt() + rules.defenseCost(claims.chunkCount(nation.id()));
    }

    /** An unprotected nation cannot defend its disputes. */
    public boolean canDefend(Nation nation) {
        return nation.economy().isDefended();
    }

    /**
     * The garrison defends a dispute without any player being connected.
     *
     * @return true if the garrison covered the defense
     */
    public boolean tryGarrisonDefense(Nation nation) {
        NationEconomy economy = nation.economy();
        if (!economy.isDefended() || economy.garrisonPoints() <= 0) {
            return false;
        }
        economy.setGarrisonPoints(economy.garrisonPoints() - 1);
        return true;
    }

    // --------------------------------------------------------------------- attack

    /** Cost of opening a dispute (with discount if there is a war). */
    public long attackCost(Nation nation, EconomyRules rules, boolean atWar) {
        return rules.attackCost(atWar);
    }

    /** A nation without money cannot attack. */
    public Outcome canAttack(Nation nation, EconomyRules rules, boolean atWar) {
        long cost = rules.attackCost(atWar);
        if (nation.economy().balance() < cost) {
            return Outcome.fail("Attacking costs " + coins(cost) + " and your nation only has "
                    + coins(nation.economy().balance()));
        }
        return Outcome.ok("The attack costs " + coins(cost));
    }

    /** Spends the attack cost (deposit: not refunded). */
    public void spend(Nation nation, long amount) {
        nation.economy().setBalance(nation.economy().balance() - amount);
    }

    // --------------------------------------------------------------------- utils

    /** Converts cents into a readable coin string. */
    public static String coins(long cents) {
        long absolute = Math.abs(cents);
        String text = (absolute / NationEconomy.CENTS) + "." + String.format("%02d", absolute % NationEconomy.CENTS);
        return (cents < 0 ? "-" : "") + text;
    }
}
