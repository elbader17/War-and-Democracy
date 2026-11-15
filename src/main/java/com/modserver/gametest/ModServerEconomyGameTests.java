package com.modserver.gametest;

import com.modserver.claim.Claim;
import com.modserver.claim.ClaimIndex;
import com.modserver.economy.EconomyRules;
import com.modserver.economy.EconomyService;
import com.modserver.economy.Investment;
import com.modserver.nation.Nation;
import com.modserver.nation.NationManager;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Tests of the economy of the nations: income, upkeep, defense,
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
        return new EconomyRules(10000L, 200L, 100L, 2000L, 200L, 500L,
                5000L, 50L, 10000L, 15000L, 20000L, 1000L, 1500L, 2000L, 3);
    }

    private record Fixture(NationManager nations, ClaimIndex claims, EconomyService economy,
                           Nation nation) {
    }

    private static Fixture fixture(int chunks, long balance) {
        NationManager nations = new NationManager();
        ClaimIndex claims = new ClaimIndex();
        Nation nation = nations.create(UUID.randomUUID(), "Nacion", UUID.randomUUID(),
                0x55FFFF, 0L);
        nation.economy().setBalance(balance);
        for (int i = 0; i < chunks; i++) {
            claims.claim(new Claim(WORLD, i, 0, nation.id(), 0L));
        }
        nation.setCapital(WORLD, 0, 0);
        return new Fixture(nations, claims, new EconomyService(claims), nation);
    }

    /** A chunk gives income and the upkeep is charged: the balance changes. */
    @GameTest(template = "empty")
    public void incomeAndUpkeepAreApplied(GameTestHelper helper) {
        Fixture f = fixture(1, 10000L);

        EconomyService.DailyReport report = f.economy().closeDay(f.nation(), rules(), 0L);

        helper.assertTrue(report.income() == 200L, "The income must be the one of a chunk");
        helper.assertTrue(report.upkeep() == 100L, "The upkeep must be the one of a chunk");
        // defense = base (2000) + per chunk (200) + quadratic (500 * 1 / 100 = 5)
        helper.assertTrue(report.defenseCost() == 2205L,
                "The defense must be base + per chunk + quadratic");
        helper.assertTrue(report.defended(), "With balance the defense is paid");
        helper.assertTrue(f.nation().economy().balance() == 10000L - 100L + 200L - 2205L,
                "The balance must be the initial one minus upkeep plus income minus defense");

        helper.succeed();
    }

    /** Defending a big territory costs much more than a small one. */
    @GameTest(template = "empty")
    public void biggerTerritoryIsMoreExpensiveToDefend(GameTestHelper helper) {
        EconomyRules rules = rules();
        long small = rules.defenseCost(1);
        long medium = rules.defenseCost(10);
        long large = rules.defenseCost(50);

        helper.assertTrue(small < medium, "Defending 10 chunks must cost more than 1");
        helper.assertTrue(medium < large, "Defending 50 chunks must cost more than 10");
        // The quadratic term makes the growth more than linear.
        helper.assertTrue(large - medium > medium - small,
                "The defense cost must grow more than linearly with the size");

        helper.succeed();
    }

    /** If the defense cannot be paid, the nation ends up UNPROTECTED and in debt. */
    @GameTest(template = "empty")
    public void unpaidDefenseLeavesTheNationUnprotected(GameTestHelper helper) {
        Fixture f = fixture(1, 0L);

        EconomyService.DailyReport report = f.economy().closeDay(f.nation(), rules(), 0L);

        helper.assertTrue(!report.defended(), "Without money the defense is not paid");
        helper.assertTrue(!f.economy().canDefend(f.nation()), "The nation must end up unprotected");
        helper.assertTrue(f.nation().economy().defenseDebt() > 0L, "Defense debt must remain");

        helper.succeed();
    }

    /** Paying for the defense restores the protection and refills the garrison. */
    @GameTest(template = "empty")
    public void payingDefenseRestoresProtection(GameTestHelper helper) {
        Fixture f = fixture(1, 0L);
        f.economy().closeDay(f.nation(), rules(), 0L);
        helper.assertTrue(!f.economy().canDefend(f.nation()), "It starts unprotected");

        // The player receives money (for example, from server rewards) and pays.
        f.nation().economy().setBalance(50000L);
        EconomyService.Outcome outcome = f.economy().payDefense(f.nation(), rules());

        helper.assertTrue(outcome.success(), "The payment should be accepted: " + outcome.message());
        helper.assertTrue(f.economy().canDefend(f.nation()), "After paying it is protected again");
        helper.assertTrue(f.nation().economy().defenseDebt() == 0L, "The debt must end up at zero");

        helper.succeed();
    }

    /** If the upkeep is not paid, the farthest chunks are lost. */
    @GameTest(template = "empty")
    public void unpaidUpkeepLosesTheFarthestChunks(GameTestHelper helper) {
        Fixture f = fixture(3, 250L);

        EconomyService.DailyReport report = f.economy().closeDay(f.nation(), rules(), 0L);

        helper.assertTrue(report.chunksLost() == 1,
                "With 250 cents and 300 of upkeep 1 chunk is lost");
        helper.assertTrue(f.claims().chunkCount(f.nation().id()) == 2,
                "The nation must keep 2 chunks");
        helper.assertTrue(f.claims().claimAt(WORLD, 2, 0) == null,
                "The chunk farthest from the capital must be lost");

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
        helper.assertTrue(report.income() == 2L * 200L * 125L / 100L,
                "The income must include the farm bonus");
        helper.assertTrue(report.interest() > 0L, "The treasury must give interest");
        // Balance = initial - upkeep + income - defense + interest
        long expected = before - report.upkeep() + report.income() - report.defenseCost()
                + report.interest();
        helper.assertTrue(f.nation().economy().balance() == expected,
                "The balance must match the charges and the interest");

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

    /** The garrison defends a dispute without any player being connected. */
    @GameTest(template = "empty")
    public void garrisonDefendsWithoutAnyPlayer(GameTestHelper helper) {
        Fixture f = fixture(1, 50000L);
        f.nation().economy().setGarrisonLevel(1);

        // Paying for the defense refills one garrison point.
        f.economy().payDefense(f.nation(), rules());
        helper.assertTrue(f.nation().economy().garrisonPoints() == 1,
                "The garrison must refill one point when paying for the defense");

        helper.assertTrue(f.economy().tryGarrisonDefense(f.nation()),
                "The garrison must be able to defend");
        helper.assertTrue(f.nation().economy().garrisonPoints() == 0,
                "The garrison consumes the point");
        helper.assertTrue(!f.economy().tryGarrisonDefense(f.nation()),
                "Without points it cannot defend again");

        helper.succeed();
    }

    /** An unprotected nation cannot use its garrison. */
    @GameTest(template = "empty")
    public void unprotectedNationHasNoGarrison(GameTestHelper helper) {
        Fixture f = fixture(1, 0L);
        f.nation().economy().setGarrisonLevel(1);
        f.nation().economy().setGarrisonPoints(3);
        f.nation().economy().setDefended(false);

        helper.assertTrue(!f.economy().tryGarrisonDefense(f.nation()),
                "An unprotected nation cannot defend even with a garrison");

        helper.succeed();
    }
}
