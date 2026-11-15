package com.modserver.economy;

/**
 * Economy of a nation: treasury, defense debt and investments.
 *
 * <p>Amounts are stored in <b>cents</b> of currency (100 cents = 1 coin)
 * so that percentages (interest, upkeep) are not truncated.</p>
 */
public final class NationEconomy {

    /** Cents per coin. */
    public static final long CENTS = 100L;

    /** Treasury balance (may be negative if money is owed). */
    private long balance;
    /** Pending defense payment in coins. */
    private long defenseDebt;
    /** Whether the nation has its defense paid. */
    private boolean defended = true;
    /** Available garrison points (defend disputes without a player). */
    private int garrisonPoints;
    /** Investment levels (0 = not invested). */
    private int farmLevel;
    private int garrisonLevel;
    private int treasuryLevel;
    /** Tick of the last economic close. */
    private long lastDay;

    public NationEconomy() {
    }

    public NationEconomy(long balance) {
        this.balance = balance;
    }

    public long balance() {
        return balance;
    }

    public void setBalance(long balance) {
        this.balance = balance;
    }

    /** Balance in coins (with decimals). */
    public double balanceInCoins() {
        return balance / (double) CENTS;
    }

    public long defenseDebt() {
        return defenseDebt;
    }

    public void setDefenseDebt(long defenseDebt) {
        this.defenseDebt = defenseDebt;
    }

    /** The nation has its defense paid and can defend its chunks. */
    public boolean isDefended() {
        return defended;
    }

    public void setDefended(boolean defended) {
        this.defended = defended;
    }

    public int garrisonPoints() {
        return garrisonPoints;
    }

    public void setGarrisonPoints(int garrisonPoints) {
        this.garrisonPoints = garrisonPoints;
    }

    public int farmLevel() {
        return farmLevel;
    }

    public void setFarmLevel(int farmLevel) {
        this.farmLevel = farmLevel;
    }

    public int garrisonLevel() {
        return garrisonLevel;
    }

    public void setGarrisonLevel(int garrisonLevel) {
        this.garrisonLevel = garrisonLevel;
    }

    public int treasuryLevel() {
        return treasuryLevel;
    }

    public void setTreasuryLevel(int treasuryLevel) {
        this.treasuryLevel = treasuryLevel;
    }

    public long lastDay() {
        return lastDay;
    }

    public void setLastDay(long lastDay) {
        this.lastDay = lastDay;
    }

    /** Income bonus from the farms (100 = no bonus). */
    public long incomeMultiplierPercent() {
        return 100L + 25L * farmLevel;
    }

    /** Garrison points restored when the defense is paid. */
    public int garrisonIncome() {
        return garrisonLevel;
    }

    /** Daily interest of the treasury (percentage over the positive balance). */
    public long interestPercent() {
        return treasuryLevel;
    }
}
