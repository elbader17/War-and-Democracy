package com.modserver.economy;

import com.modserver.claim.ClaimIndex;
import com.modserver.nation.Nation;

/**
 * Economic rules for national income and investments.
 *
 * <p>Pure domain class: it does not depend on Minecraft, only on the nation and the
 * claim index. The full cycle (closing the economic day) can be verified in the
 * GameTests without a real server.</p>
 *
 * <p><b>The game loop:</b></p>
 * <ol>
 *   <li>Each claimed chunk earns income, including any farm bonus.</li>
 *   <li>The garrison adds one ground troop to the reserve per level.</li>
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
    public record DailyReport(long income, long interest, long balance, String message) {
    }

    private final ClaimIndex claims;

    public EconomyService(ClaimIndex claims) {
        this.claims = claims;
    }

    // ------------------------------------------------------------------ daily cycle

    /** Credits income and interest and delivers the day's reinforcements. */
    public DailyReport closeDay(Nation nation, EconomyRules rules, long gameTime) {
        NationEconomy economy = nation.economy();
        long income = rules.dailyIncome(claims.chunkCount(nation.id()), economy);
        economy.setBalance(economy.balance() + income);

        economy.replenishGarrison();

        // Treasury interest (only over a positive balance).
        long interest = 0L;
        if (economy.balance() > 0 && economy.interestPercent() > 0) {
            interest = economy.balance() * economy.interestPercent() / 100L;
            economy.setBalance(economy.balance() + interest);
        }

        economy.setLastDay(gameTime);

        String message = "Economic day of " + nation.name() + ": +" + coins(income) + " in income";
        return new DailyReport(income, interest, economy.balance(), message);
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
