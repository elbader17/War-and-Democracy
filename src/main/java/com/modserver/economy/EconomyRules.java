package com.modserver.economy;

/**
 * Economic rules of the server (read from the configuration).
 *
 * <p>All amounts are in <b>cents</b> of currency.</p>
 *
 * @param startingBalance      starting balance of a newly created nation
 * @param claimIncome          daily income per claimed chunk
 * @param attackCost           cost of opening a dispute (deposit, not refunded)
 * @param warAttackDiscount    discount on the attack cost during war (percentage)
 * @param farmCost             cost of buying each farm level
 * @param garrisonCost         cost of buying each garrison level
 * @param treasuryCost         cost of buying each treasury level
 * @param maxLevel             maximum level of each investment
 */
public record EconomyRules(long startingBalance,
                            long claimIncome,
                            long attackCost,
                            long warAttackDiscount,
                            long farmCost,
                            long garrisonCost,
                            long treasuryCost,
                            int maxLevel) {

    public long dailyIncome(int chunks, NationEconomy economy) {
        return chunks * claimIncome * economy.incomeMultiplierPercent() / 100L;
    }

    /** Cost of opening a dispute (with discount if there is a war). */
    public long attackCost(boolean atWar) {
        if (!atWar) {
            return attackCost;
        }
        return attackCost * (100L - warAttackDiscount) / 100L;
    }

    /** Cost of upgrading an investment to the next level (levels 1..maxLevel). */
    public long purchaseCost(Investment investment, int currentLevel) {
        return switch (investment) {
            case FARM -> farmCost;
            case GARRISON -> garrisonCost;
            case TREASURY -> treasuryCost;
        };
    }
}
