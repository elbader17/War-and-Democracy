package com.modserver.economy;

/**
 * Economic rules of the server (read from the configuration).
 *
 * <p>All amounts are in <b>cents</b> of currency.</p>
 *
 * @param startingBalance      starting balance of a newly created nation
 * @param claimIncome          daily income per claimed chunk
 * @param claimUpkeep          daily cost per claimed chunk (maintenance)
 * @param defenseBase          fixed daily cost of the defense
 * @param defensePerChunk      daily cost of defending each chunk
 * @param defenseScale         extra cost per scaled chunk (quadratic term / 100)
 * @param attackCost           cost of opening a dispute (deposit, not refunded)
 * @param warAttackDiscount    discount on the attack cost during war (percentage)
 * @param farmCost             cost of buying each farm level
 * @param garrisonCost         cost of buying each garrison level
 * @param treasuryCost         cost of buying each treasury level
 * @param farmUpkeep           daily maintenance per farm level
 * @param garrisonUpkeep       daily maintenance per garrison level
 * @param treasuryUpkeep       daily maintenance per treasury level
 * @param maxLevel             maximum level of each investment
 */
public record EconomyRules(long startingBalance,
                            long claimIncome,
                            long claimUpkeep,
                            long defenseBase,
                            long defensePerChunk,
                            long defenseScale,
                            long attackCost,
                            long warAttackDiscount,
                            long farmCost,
                            long garrisonCost,
                            long treasuryCost,
                            long farmUpkeep,
                            long garrisonUpkeep,
                            long treasuryUpkeep,
                            int maxLevel) {

    public long dailyIncome(int chunks, NationEconomy economy) {
        return chunks * claimIncome * economy.incomeMultiplierPercent() / 100L;
    }

    public long dailyUpkeep(int chunks, NationEconomy economy) {
        return chunks * claimUpkeep
                + investmentUpkeep(economy.farmLevel(), economy.garrisonLevel(), economy.treasuryLevel());
    }

    /** Daily defense cost of a nation with {@code chunks} chunks. */
    public long defenseCost(int chunks) {
        long quadratic = defenseScale * (long) chunks * chunks / 100L;
        return defenseBase + defensePerChunk * chunks + quadratic;
    }

    /** Cost of opening a dispute (with discount if there is a war). */
    public long attackCost(boolean atWar) {
        if (!atWar) {
            return attackCost;
        }
        return attackCost * (100L - warAttackDiscount) / 100L;
    }

    /** Daily maintenance cost of the investments of a nation. */
    public long investmentUpkeep(int farmLevel, int garrisonLevel, int treasuryLevel) {
        return farmUpkeep * farmLevel + garrisonUpkeep * garrisonLevel
                + treasuryUpkeep * treasuryLevel;
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
