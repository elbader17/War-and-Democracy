package com.modserver.gametest;

import com.modserver.claim.Claim;
import com.modserver.claim.ClaimIndex;
import com.modserver.economy.EconomyRules;
import com.modserver.economy.EconomyService;
import com.modserver.economy.Investment;
import com.modserver.military.TroopCounts;
import com.modserver.nation.Nation;
import com.modserver.nation.NationManager;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Tests of the economy of the nations: income, reinforcements,
 * investments and the cost of attacking.
 *
 * <p>Everything is pure domain: {@link EconomyService} does not depend on Minecraft,
 * so the whole economic cycle (one game day) can be simulated in milliseconds.</p>
 */
@GameTestHolder("modserver")
@PrefixGameTestTemplate(false)
public class ModServerEconomyGameTests {

    private static final String WORLD = "minecraft:overworld";

    /** Default rules (same as the server): everything in currency cents. */
    private static EconomyRules rules() {
        return new EconomyRules(10000L, 1200L, 5000L, 50L, 10000L, 15000L, 20000L, 3);
    }

    private record Fixture(NationManager nations, ClaimIndex claims, EconomyService economy,
                           Nation nation) {
    }

    private static Fixture fixture(int chunks, long balance) {
        NationManager nations = new NationManager();
        ClaimIndex claims = new ClaimIndex();
        Nation nation = nations.create(UUID.randomUUID(), "Nation", UUID.randomUUID(),
                0x55FFFF, 0L);
        nation.economy().setBalance(balance);
        for (int i = 0; i < chunks; i++) {
            claims.claim(new Claim(WORLD, i, 0, nation.id(), 0L));
        }
        nation.setCapital(WORLD, 0, 0);
        return new Fixture(nations, claims, new EconomyService(claims), nation);
    }

    @GameTest(template = "empty")
    public void treasuryReceivesFullChunkIncome(GameTestHelper helper) {
        Fixture f = fixture(1, 10000L);

        EconomyService.DailyReport report = f.economy().closeDay(f.nation(), rules(), 0L);

        helper.assertTrue(report.income() == 1200L, "The income must be the one of a chunk");
        helper.assertTrue(report.balance() == 11200L, "The treasury receives the full income");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public void largeNationKeepsEveryChunkWithAnEmptyTreasury(GameTestHelper helper) {
        Fixture f = fixture(50, 0L);
        var report = f.economy().closeDay(f.nation(), rules(), 24000L);
        helper.assertTrue(f.claims().chunkCount(f.nation().id()) == 50 && report.balance() == 60000L,
                "All fifty chunks stay claimed and each generates its full income");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public void negativeLegacyBalanceKeepsOutlyingGarrisons(GameTestHelper helper) {
        Fixture f = fixture(3, -5000L);
        var border = f.claims().claimAt(WORLD, 2, 0);
        var troops = new TroopCounts(2, 3, 4);
        border.setForces(troops);
        var report = f.economy().closeDay(f.nation(), rules(), 24000L);
        helper.assertTrue(report.balance() == -1400L, "All three chunks earn income against the existing balance");
        helper.assertTrue(f.claims().claimAt(WORLD, 2, 0) == border && border.forces().equals(troops),
                "An overdrawn nation keeps its border and the troops deployed there");
        helper.succeed();
    }

    /** Attacking costs coins: they are charged and, without balance, attacking is impossible. */
    @GameTest(template = "empty")
    public void attackingCostsMoney(GameTestHelper helper) {
        Fixture f = fixture(1, 10000L);
        EconomyRules rules = rules();

        EconomyService.Outcome can = f.economy().canAttack(f.nation(), rules, false);
        helper.assertTrue(can.success(), "With balance it is possible to attack");

        f.economy().spend(f.nation(), rules.attackCost(false));
        helper.assertTrue(f.nation().economy().balance() == 10000L - 5000L,
                "The attack must cost 50 coins");

        f.nation().economy().setBalance(100L);
        EconomyService.Outcome poor = f.economy().canAttack(f.nation(), rules, false);
        helper.assertTrue(!poor.success(), "Without balance it is not possible to attack");

        helper.succeed();
    }

    /** At war, the attack is cheaper. */
    @GameTest(template = "empty")
    public void warDiscountsTheAttackCost(GameTestHelper helper) {
        EconomyRules rules = rules();
        helper.assertTrue(rules.attackCost(true) < rules.attackCost(false),
                "At war the attack must be cheaper");
        helper.assertTrue(rules.attackCost(true) == 2500L,
                "With the 50% discount it is 25 coins");

        helper.succeed();
    }

    /** Farms increase the income; the treasury gives interest. */
    @GameTest(template = "empty")
    public void investmentsImproveTheEconomy(GameTestHelper helper) {
        Fixture f = fixture(2, 100000L);

        EconomyService.Outcome farm = f.economy().purchase(f.nation(), Investment.FARM, rules());
        helper.assertTrue(farm.success(), "The farm must be buyable: " + farm.message());
        helper.assertTrue(f.nation().economy().farmLevel() == 1, "The farm goes up one level");
        helper.assertTrue(f.nation().economy().incomeMultiplierPercent() == 125L,
                "The farm must give 25% more income");

        EconomyService.Outcome treasury = f.economy().purchase(f.nation(), Investment.TREASURY, rules());
        helper.assertTrue(treasury.success(), "The treasury must be buyable: " + treasury.message());
        helper.assertTrue(f.nation().economy().interestPercent() == 1L,
                "The treasury must give 1% daily");

        long before = f.nation().economy().balance();
        EconomyService.DailyReport report = f.economy().closeDay(f.nation(), rules(), 0L);
        helper.assertTrue(report.income() == 2L * 1200L * 125L / 100L,
                "The income must include the farm bonus");
        helper.assertTrue(report.interest() > 0L, "The treasury must give interest");
        long expected = before + report.income() + report.interest();
        helper.assertTrue(f.nation().economy().balance() == expected,
                "Farm income and treasury interest are credited in full");

        helper.succeed();
    }

    /** Without money it is not possible to invest. */
    @GameTest(template = "empty")
    public void investmentsNeedMoney(GameTestHelper helper) {
        Fixture f = fixture(1, 100L);

        EconomyService.Outcome outcome = f.economy().purchase(f.nation(), Investment.FARM, rules());
        helper.assertTrue(!outcome.success(), "Without balance it is not possible to invest");
        helper.assertTrue(f.nation().economy().farmLevel() == 0, "It must not go up a level");

        helper.succeed();
    }

    @GameTest(template = "empty")
    public void garrisonAddsGroundReinforcementsEvenWithAnEmptyTreasury(GameTestHelper helper) {
        Fixture f = fixture(0, 2 * rules().garrisonCost());
        var economy = f.nation().economy();
        economy.setReserveForces(new TroopCounts(1, 2, 3));
        f.economy().purchase(f.nation(), Investment.GARRISON, rules());
        f.economy().purchase(f.nation(), Investment.GARRISON, rules());
        helper.assertTrue(economy.garrisonLevel() == 2 && economy.balance() == 0, "Each upgrade charges its purchase price once");
        f.economy().closeDay(f.nation(), rules(), 24000L);
        f.economy().closeDay(f.nation(), rules(), 48000L);
        helper.assertTrue(economy.reserveForces().equals(new TroopCounts(5, 2, 3)),
                "Each level adds one ground troop per day, preserving existing branches");
        helper.assertTrue(economy.balance() == 0, "Daily reinforcements are free");
        helper.succeed();
    }
}
