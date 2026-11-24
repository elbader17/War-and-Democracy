package com.modserver.economy;

import com.modserver.military.TroopCounts;
/**
 * Economy of a nation: treasury, reserves and investments.
 *
 * <p>Amounts are stored in <b>cents</b> of currency (100 cents = 1 coin)
 * so that percentages (income bonuses and interest) are not truncated.</p>
 */
public final class NationEconomy {

    /** Cents per coin. */
    public static final long CENTS = 100L;

    /** Treasury balance (may be negative if money is owed). */
    private long balance;
    /** Troops available to deploy on the tactical map. */
    private TroopCounts reserveForces = TroopCounts.EMPTY;
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

    public int garrisonPoints() {
        return reserveForces.total();
    }

    public void setGarrisonPoints(int garrisonPoints) {
        reserveForces = new TroopCounts(garrisonPoints, 0, 0);
    }

    public TroopCounts reserveForces() { return reserveForces; }
    public void setReserveForces(TroopCounts value) { reserveForces = value; }

    public void replenishGarrison() {
        int income = Math.min(Integer.MAX_VALUE - garrisonPoints(), garrisonIncome());
        reserveForces = reserveForces.plus(new TroopCounts(income, 0, 0));
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

    /** Daily ground troops supplied by the garrison investment. */
    public int garrisonIncome() {
        return garrisonLevel;
    }

    /** Daily interest of the treasury (percentage over the positive balance). */
    public long interestPercent() {
        return treasuryLevel;
    }
}
