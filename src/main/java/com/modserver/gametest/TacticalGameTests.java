package com.modserver.gametest;

import com.modserver.NationService;
import com.modserver.claim.Claim;
import com.modserver.military.ArmyCombat;
import com.modserver.military.BattleMotion;
import com.modserver.military.Tactics;
import com.modserver.military.TroopTransfer;
import com.modserver.military.TroopBranch;
import com.modserver.military.TroopCounts;
import com.modserver.military.TroopFormation;
import com.modserver.nation.Nation;
import com.modserver.nation.NationRelation;
import com.modserver.network.MissileFlight;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.util.UUID;
import java.util.function.IntSupplier;

@GameTestHolder("modserver")
@PrefixGameTestTemplate(false)
public class TacticalGameTests {
    private static final String WORLD="minecraft:overworld";
    private final NationService service=new NationService();
    private final Nation attacker=country("Amber"), defender=country("Teal");
    private Nation country(String name) {return service.nations().create(UUID.randomUUID(),name,UUID.randomUUID(),0,0);}
    private Claim claim(int x,int z,Nation nation,int armies) {
        var c=new Claim(WORLD,x,z,nation.id(),0);
        c.setArmies(armies);
        service.claims().claim(c);
        return c;
    }
    private ArmyCombat.Result order(ArmyCombat.Order action,int x,int z,int tx,int tz,int n) {
        return ArmyCombat.execute(service,attacker,WORLD,action,x,z,tx,tz,n,10);
    }
    private static IntSupplier dice(int... rolls) {
        var it=java.util.Arrays.stream(rolls).iterator();
        return it::nextInt;
    }
    @GameTest(template="empty")
    public void formationsRepresentEveryTroopAcrossCasualties(GameTestHelper h) {
        for (int troops : new int[]{0, 1, 9, 10, 99, 100, 999, 1000, 14_999, 15_000, 15_037, Integer.MAX_VALUE}) {
            long represented = 0;
            for (var tier : TroopFormation.LARGEST_FIRST) represented += (long)tier.pieces(troops) * tier.value;
            h.assertTrue(represented == troops, "No troops lost to grouping or rounding at " + troops);
        }
        h.assertTrue(TroopFormation.largest(15_000) == TroopFormation.BATTALION
                && TroopFormation.BATTALION.pieces(15_000) == 15, "15,000 displays as fifteen battalions");
        h.assertTrue(TroopFormation.SQUAD.pieces(15_037) == 3 && TroopFormation.SOLDIER.pieces(15_037) == 7,
                "Smaller pieces account for every remaining troop");
        var home = claim(0, 0, attacker, 15_000);
        claim(1, 0, defender, 1);
        h.assertTrue(order(ArmyCombat.Order.ROLL, 0, 0, 1, 0, 15_000).success(), "A full grouped force can attack");
        var battle = service.disputes().openAt(WORLD, 1, 0);
        ArmyCombat.advance(service, battle, 110, dice(3, 3));
        h.assertTrue(home.armies() == 0 && battle.assault().attackers() == 14_999
                && TroopFormation.BATTALION.pieces(battle.assault().attackers()) == 14,
                "A lost duel costs exactly one base troop; the fifteenth battalion breaks into smaller pieces");
        h.succeed();
    }

    @GameTest(template="empty")
    public void moveTransfersOnlyDeployedArmies(GameTestHelper h) {
        var from = claim(-1, 0, attacker, 7);
        var to = claim(-1, 1, attacker, 3);
        from.setMissiles(2);
        attacker.economy().setGarrisonPoints(8);
        long balance = attacker.economy().balance();
        var result = order(ArmyCombat.Order.MOVE, -1, 0, -1, 1, 6);
        h.assertTrue(result.success() && from.armies() == 1 && to.armies() == 9, "Six deployed armies move across a shared edge");
        h.assertTrue(from.missiles() == 2 && to.missiles() == 0, "Missiles remain at their launch site");
        h.assertTrue(attacker.economy().garrisonPoints() == 8 && attacker.economy().balance() == balance, "Moving uses neither reserve nor treasury");
        h.assertTrue(service.disputes().openAt(WORLD, -1, 1) == null, "A friendly transfer does not start combat");
        h.succeed();
    }

    @GameTest(template="empty")
    public void rejectedMovesPreserveEveryGarrison(GameTestHelper h) {
        var home = claim(0, 0, attacker, 5);
        var own = claim(1, 0, attacker, 3);
        var rival = claim(-1, 0, defender, 4);
        var diagonal = claim(1, 1, attacker, 2);
        var full = claim(0, -1, attacker, Integer.MAX_VALUE);
        var move = ArmyCombat.Order.MOVE;
        h.assertFalse(order(move, 0, 0, 1, 0, 5).success(), "One army must stay behind");
        h.assertFalse(order(move, 0, 0, 1, 0, 0).success(), "Quantity must be positive");
        h.assertFalse(order(move, 0, 0, 0, 0, 1).success(), "Endpoints must differ");
        h.assertFalse(order(move, 0, 0, 1, 1, 1).success(), "No diagonal moves");
        h.assertFalse(order(move, 0, 0, 0, 1, 1).success(), "Cannot move onto unclaimed land");
        h.assertFalse(order(move, 0, 0, -1, 0, 1).success(), "Cannot reinforce a rival");
        h.assertFalse(order(move, -1, 0, 0, 0, 1).success(), "Cannot move a rival's armies");
        h.assertFalse(order(move, 0, 0, 0, -1, 1).success(), "Cannot overflow the destination");
        h.assertTrue(home.armies() == 5 && own.armies() == 3 && rival.armies() == 4
                && diagonal.armies() == 2 && full.armies() == Integer.MAX_VALUE, "Rejected transfers are atomic");
        h.succeed();
    }

    @GameTest(template="empty")
    public void fullAssaultDuelsEveryFiveSecondsUntilCapture(GameTestHelper h) {
        var home = claim(0, 0, attacker, 20);
        var rival = claim(1, 0, defender, 20);
        var start = order(ArmyCombat.Order.ROLL, 0, 0, 1, 0, 20);
        var battle = service.disputes().openAt(WORLD, 1, 0);
        h.assertTrue(start.success() && home.armies() == 0 && rival.armies() == 20, "All 20 leave, without an immediate roll");
        h.assertFalse(order(ArmyCombat.Order.ROLL, 0, 0, 1, 0, 20).success(), "Cannot spend the same troops twice");
        h.assertFalse(ArmyCombat.advance(service, battle, 109, dice()), "First duel waits 100 ticks");
        for (int n = 1; n <= 20; n++) {
            long now = 10 + n * 100L;
            h.assertTrue(ArmyCombat.advance(service, battle, now, dice(6, 1)), "One duel per interval");
            h.assertFalse(ArmyCombat.advance(service, battle, now, dice()), "No duplicate duel");
            if (n < 20) h.assertTrue(rival.nationId().equals(defender.id()) && rival.armies() == 20 - n, "One defender falls");
        }
        h.assertTrue(rival.nationId().equals(attacker.id()) && rival.armies() == 20 && home.armies() == 0, "Survivors occupy the capture");
        h.assertTrue(service.disputes().openAt(WORLD, 1, 0) == null, "The map clears the resolved dispute");
        h.succeed();
    }

    @GameTest(template="empty")
    public void savedAssaultResumesExactlyOnceAndTiesDefend(GameTestHelper h) throws Exception {
        claim(0, 0, attacker, 4);
        claim(1, 0, defender, 2);
        var spare = claim(1, 1, attacker, 5);
        order(ArmyCombat.Order.ROLL, 0, 0, 1, 0, 4);
        var battle = service.disputes().openAt(WORLD, 1, 0);
        h.assertFalse(order(ArmyCombat.Order.ROLL, 1, 1, 1, 0, 5).success(), "Second army cannot overwrite an engagement");
        h.assertTrue(spare.armies() == 5 && service.expireDisputes(99999).isEmpty(), "Old deadlines cannot end a live battle");
        ArmyCombat.advance(service, battle, 110, dice(4, 4));
        var dir = Files.createTempDirectory("field-force");
        try {
            var store = new com.modserver.data.ModServerStore(dir);
            store.save(service.nations(), service.claims(), service.disputes());
            // Simulate a crash between writing a zero garrison and writing the matching field force.
            var pending = new com.google.gson.JsonObject();
            for (String file : java.util.List.of("nations.json", "claims.json", "disputes.json"))
                pending.add(file, com.google.gson.JsonParser.parseString(Files.readString(dir.resolve(file))));
            Files.writeString(dir.resolve("pending-save.json"), pending.toString());
            Files.writeString(dir.resolve("disputes.json"), "{}");
            var copy = new NationService();
            store.load(copy.nations(), copy.claims(), copy.disputes());
            h.assertFalse(Files.exists(dir.resolve("pending-save.json")), "Interrupted checkpoint recovered");
            var resumed = copy.disputes().openAt(WORLD, 1, 0);
            h.assertTrue(copy.claims().claimAt(WORLD, 0, 0).armies() == 0 && resumed.assault().attackers() == 3, "Empty origins and field forces persist");
            h.assertFalse(ArmyCombat.advance(copy, resumed, 209, dice()), "Next duel survives reload");
            for (long t = 210; t <= 410; t += 100) ArmyCombat.advance(copy, resumed, t, dice(4, 4));
            h.assertTrue(copy.disputes().openAt(WORLD, 1, 0) == null && copy.claims().claimAt(WORLD,1,0).armies() == 2, "Defender wins ties and keeps the chunk");
        } finally {
            try (var files = Files.list(dir)) { for (var file : files.toList()) Files.delete(file); }
            Files.delete(dir);
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public void recallReturnsSurvivorsAndEmptyTerritoryCanBeTaken(GameTestHelper h) {
        var home = claim(0, 0, attacker, 10);
        var target = claim(0, 1, defender, 1);
        order(ArmyCombat.Order.ROLL, 0, 0, 0, 1, 8);
        var battle = service.disputes().openAt(WORLD, 0, 1);
        ArmyCombat.advance(service, battle, 110, dice(2, 3));
        service.disputeService().cancelOpenAt(WORLD, 0, 1, 111, "release");
        h.assertTrue(home.armies() == 9 && target.armies() == 1, "Recall returns seven survivors once");
        target.setArmies(0);
        order(ArmyCombat.Order.ROLL, 0, 0, 0, 1, 9);
        battle = service.disputes().openAt(WORLD, 0, 1);
        ArmyCombat.advance(service, battle, 110, dice());
        h.assertTrue(target.nationId().equals(attacker.id()) && target.armies() == 9, "March occupies an empty chunk after the first interval");
        h.succeed();
    }

    @GameTest(template="empty")
    public void purchasesDeploymentAndMissilesSurviveReload(GameTestHelper h) throws Exception {
        var home=claim(0,0,attacker,1);
        var far=claim(0,3,defender,3);
        attacker.economy().setBalance(6*ArmyCombat.price());
        h.assertFalse(order(ArmyCombat.Order.BUY,0,0,0,0,7).success(),"No overdraft");
        h.assertTrue(order(ArmyCombat.Order.BUY,0,0,0,0,6).success(),"Buy six pieces");
        h.assertTrue(attacker.economy().balance()==0 && attacker.economy().garrisonPoints()==6,"Purchased pieces are in reserve");
        h.assertFalse(order(ArmyCombat.Order.DEPLOY,0,3,0,0,6).success(),"Cannot place on a rival");
        h.assertTrue(order(ArmyCombat.Order.DEPLOY,0,0,0,0,6).success() && home.armies()==7,"Deploy reserve");
        h.assertTrue(order(ArmyCombat.Order.EXCHANGE,0,0,0,0,1).success() && home.armies()==1 && home.missiles()==1,"Convert six and keep one");
        home.setNuclearMissiles(2);
        var dir=Files.createTempDirectory("tactical-save");
        try {
            var store=new com.modserver.data.ModServerStore(dir);
            store.save(service.nations(),service.claims(),service.disputes());
            var copy=new NationService();
            store.load(copy.nations(),copy.claims(),copy.disputes());
            h.assertTrue(copy.claims().claimAt(WORLD,0,0).missiles()==1 && copy.claims().claimAt(WORLD,0,3).armies()==3,"Board survives a reload");
            h.assertTrue(copy.claims().claimAt(WORLD,0,0).nuclearMissiles()==2, "Both nuclear missiles survive a reload");
            var file = dir.resolve("claims.json");
            var legacy = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (var cell : legacy.getAsJsonArray("claims")) cell.getAsJsonObject().remove("nuclearMissiles");
            Files.writeString(file, legacy.toString());
            var old = new NationService();
            store.load(old.nations(), old.claims(), old.disputes());
            h.assertTrue(old.claims().claimAt(WORLD,0,0).nuclearMissiles()==0 && old.claims().claimAt(WORLD,0,0).missiles()==1,
                    "Pre-nuclear saves keep their conventional inventory");
            h.assertTrue(copy.nations().byId(attacker.id()).orElseThrow().economy().garrisonPoints()==0,"Deployed reserve cannot be spent again");
        } finally {
            try(var files=Files.list(dir)) {for(var file:files.toList())Files.delete(file);}
            Files.delete(dir);
        }
        attacker.setRelation(defender.id(),NationRelation.PACT);
        h.assertFalse(order(ArmyCombat.Order.FIRE,0,0,0,3,1).success(),"Pact blocks launch");
        attacker.setRelation(defender.id(),NationRelation.NEUTRAL);
        h.assertTrue(order(ArmyCombat.Order.FIRE,0,0,0,3,1).success() && far.armies()==2 && home.missiles()==0,"Range three removes one army without a roll");
        h.assertFalse(order(ArmyCombat.Order.FIRE,0,0,0,3,1).success(),"A missile is single-use");
        h.succeed();
    }
    @GameTest(template="empty")
    public void nuclearPurchasesAndRejectedLaunchesPreserveStock(GameTestHelper h) {
        var silo = claim(0, 0, attacker, 12);
        silo.setMissiles(2);
        claim(1, 0, defender, 8);
        claim(0, 4, defender, 8);
        long cost = ArmyCombat.nuclearPrice();
        var buy = ArmyCombat.Order.BUY_NUCLEAR;
        var fire = ArmyCombat.Order.NUKE;
        attacker.economy().setBalance(cost - 1);
        h.assertFalse(order(buy, 0, 0, 0, 0, 1).success(), "The full treasury price is required");
        attacker.economy().setBalance(cost);
        h.assertFalse(order(buy, 1, 0, 0, 0, 1).success(), "The silo must belong to the buyer");
        h.assertTrue(order(buy, 0, 0, 0, 0, 1).success() && silo.nuclearMissiles() == 1
                && attacker.economy().balance() == 0, "One purchase debits the full price once");
        attacker.setRelation(defender.id(), NationRelation.PACT);
        h.assertFalse(order(fire, 0, 0, 1, 0, 1).success(), "Treaties protect the epicenter");
        attacker.setRelation(defender.id(), NationRelation.NEUTRAL);
        h.assertFalse(order(fire, 0, 0, 0, 4, 1).success(), "Out-of-range launches are rejected");
        h.assertTrue(silo.missiles() == 2 && silo.nuclearMissiles() == 1 && silo.armies() == 12,
                "Rejected launches preserve every unit and both types of ammunition");
        h.succeed();
    }

    @GameTest(template="empty")
    public void nuclearStrikeClearsNineGarrisonsAndCommittedTroops(GameTestHelper h) {
        var ally = country("Green");
        attacker.setRelation(ally.id(), NationRelation.ALLY);
        var silo = claim(-2, 0, attacker, 8);
        silo.setNuclearMissiles(1);
        var reserve = claim(2, 1, attacker, 5);
        long expectedLosses = 3; // Committed to the corner from outside the blast.
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            var owner = x == -1 ? attacker : (x == 1 && z < 1 ? ally : defender);
            var cell = claim(x, z, owner, 0);
            cell.setForces(z == -1 ? new TroopCounts(Integer.MAX_VALUE, 0, 0) : new TroopCounts(2, 3, 4));
            expectedLosses += cell.armies();
        }
        h.assertTrue(order(ArmyCombat.Order.ROLL, 2, 1, 1, 1, 3).success(), "Corner battle started");
        var hit = order(ArmyCombat.Order.NUKE, -2, 0, 0, 0, 1);
        h.assertTrue(hit.success() && ((Number) hit.values()[2]).longValue() == expectedLosses, "Every casualty is counted once");
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            h.assertTrue(service.claims().claimAt(WORLD, x, z).forces().equals(TroopCounts.EMPTY), "All branches cleared at " + x + "," + z);
        h.assertTrue(service.disputes().openAt(WORLD, 1, 1) == null && reserve.armies() == 2, "Invaders do not return home");
        h.assertTrue(silo.armies() == 8 && silo.nuclearMissiles() == 0 && service.claims().claimAt(WORLD, 0, 0).nationId().equals(defender.id()),
                "Outside troops and territorial ownership survive");
        h.assertFalse(order(ArmyCombat.Order.NUKE, -2, 0, 0, 0, 1).success(), "The missile can only be fired once");
        h.succeed();
    }

    @GameTest(template="empty")
    public void missileFlightReachesTheDisplayedTarget(GameTestHelper h) {
        var origin = new BlockPos(-24, -60, 8);
        var target = new BlockPos(8, -52, -8);
        var flight = new MissileFlight(h.getLevel().dimension().location(), origin, target, 0x379F92, 100, false);
        var start = flight.position(0);
        var peak = flight.position((MissileFlight.LIFTOFF + flight.impactTick()) / 2.0);
        var hit = flight.position(flight.impactTick());
        h.assertTrue(start.equals(flight.position(MissileFlight.LIFTOFF)), "Ignition stays at the launch site");
        var nuclear = new MissileFlight(flight.dimension(), origin, target, flight.color(), 100, true);
        var nuclearPeak = nuclear.position((MissileFlight.LIFTOFF + nuclear.impactTick()) / 2.0);
        h.assertTrue(peak.y > hit.y + 20 && nuclearPeak.y > peak.y + 25, "Both arcs clear the battlefield at distinct heights");
        h.assertTrue(nuclear.position(nuclear.impactTick()).equals(hit) && nuclear.endTick() > flight.endTick(),
                "The nuclear blast lasts longer at the same epicenter");
        h.assertTrue(hit.equals(net.minecraft.world.phys.Vec3.atBottomCenterOf(target).add(0, .65, 0))
                && hit.equals(flight.position(flight.endTick())), "The impact ring stays at the target");
        for (int age = 0; age <= flight.endTick(); age++) {
            var p = flight.position(age);
            double progress = flight.horizontalProgress(age);
            h.assertTrue(Math.abs(p.x - (start.x + (hit.x - start.x) * progress)) < 1e-6
                    && Math.abs(p.z - (start.z + (hit.z - start.z) * progress)) < 1e-6,
                    "The board marker follows the flight");
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public void invalidOrdersPreserveBothArmies(GameTestHelper h) {
        var home=claim(0,0,attacker,3);
        var enemy=claim(1,0,defender,3);
        claim(1,1,defender,6);
        home.setMissiles(1);
        h.assertFalse(order(ArmyCombat.Order.ROLL,0,0,1,0,4).success(),"Cannot risk more than the garrison");
        h.assertFalse(order(ArmyCombat.Order.ROLL,0,0,1,1,2).success(),"Diagonal is not a shared border");
        h.assertFalse(order(ArmyCombat.Order.FIRE,0,0,1,0,1).success(),"Missiles cannot eliminate the last army");
        h.assertTrue(home.armies()==3 && home.missiles()==1 && enemy.armies()==3,"Rejected orders preserve forces");
        enemy.setArmies(4);
        h.assertTrue(order(ArmyCombat.Order.FIRE,0,0,1,0,1).success() && enemy.armies()==1 && enemy.nationId().equals(defender.id()),"Adjacent volley removes three without conquest");
        h.succeed();
    }

    @GameTest(template="empty")
    public void missileTargetPreviewMatchesTheLaunch(GameTestHelper h) {
        var base = claim(0, 4, attacker, 7);
        var target = claim(2, 4, defender, 1);
        h.assertTrue(order(ArmyCombat.Order.EXCHANGE, 0, 4, 0, 0, 1).success(), "Craft a conventional missile");
        var rejected = order(ArmyCombat.Order.FIRE, 0, 4, 2, 4, 1);
        h.assertTrue(rejected.key().equals(ArmyCombat.missileTargetProblem(2, 1))
                && java.util.Arrays.equals(rejected.values(), new Object[]{1, 3})
                && base.missiles() == 1 && target.armies() == 1, "Explain the two-edge threshold and retain the missile");
        target.setArmies(3);
        h.assertTrue(ArmyCombat.missileTargetProblem(2, 3) == null, "Mark an eligible target on the board");
        h.assertTrue(order(ArmyCombat.Order.FIRE, 0, 4, 2, 4, 1).success()
                && target.armies() == 3 - ArmyCombat.missileDamage(2) && base.missiles() == 0, "Apply the preview once");
        h.succeed();
    }
    @GameTest(template="empty")
    public void branchAdvantagesApplyToBothSidesOfTheDuel(GameTestHelper h) {
        int z = 0;
        for (var attackArm : TroopBranch.ALL) for (var defenseArm : TroopBranch.ALL) {
            boolean attackFavored = switch (attackArm) {
                case AIR -> defenseArm == TroopBranch.GROUND;
                case GROUND -> defenseArm == TroopBranch.NAVAL;
                case NAVAL -> defenseArm == TroopBranch.AIR;
            };
            boolean defenseFavored = attackArm != defenseArm && !attackFavored;
            int captures = 0;
            for (int a = 1; a <= 6; a++) for (int d = 1; d <= 6; d++) {
                claim(0, z, attacker, 0).setForces(TroopCounts.of(attackArm, 1));
                var rival = claim(1, z, defender, 0);
                rival.setForces(TroopCounts.of(defenseArm, 1));
                order(ArmyCombat.Order.ROLL, 0, z, 1, z, 1);
                ArmyCombat.advance(service, service.disputes().openAt(WORLD, 1, z), 110, dice(a, d));
                boolean captured = rival.nationId().equals(attacker.id());
                if (captured) captures++;
                boolean shouldCapture = a > d;
                if (attackFavored && a == 1 && d == 1) shouldCapture = true;
                if (defenseFavored && a == 2 && d == 1) shouldCapture = false;
                h.assertTrue(captured == shouldCapture && rival.forces().equals(TroopCounts.of(captured ? attackArm : defenseArm, 1)),
                        "Duel outcome for " + attackArm + " vs " + defenseArm + " at " + a + ":" + d);
                z++;
            }
            int expectedWins = attackFavored ? 16 : defenseFavored ? 14 : 15;
            h.assertTrue(captures == expectedWins, "Branch advantage shifts one of the 36 outcomes");
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public void branchOrdersAndMissilesConserveComposition(GameTestHelper h) {
        var stock = attacker.economy();
        stock.setBalance(ArmyCombat.price() * 12);
        for (var arm : TroopBranch.ALL)
            h.assertTrue(ArmyCombat.execute(service, attacker, WORLD, ArmyCombat.Order.BUY, 0,0,0,0,4,10,arm).success(), "Buy " + arm);
        var home = claim(0,0,attacker,1);
        var other = claim(0,1,attacker,1);
        h.assertFalse(ArmyCombat.execute(service, attacker, WORLD, ArmyCombat.Order.DEPLOY,0,0,0,0,5,10,TroopBranch.AIR).success(), "Cannot borrow from another branch");
        h.assertTrue(stock.balance() == 0 && stock.reserveForces().equals(new TroopCounts(4,4,4)), "Same price and atomic debit");
        ArmyCombat.execute(service, attacker, WORLD, ArmyCombat.Order.DEPLOY,0,0,0,0,4,10,TroopBranch.AIR);
        order(ArmyCombat.Order.DEPLOY,0,0,0,0,6);
        h.assertTrue(stock.reserveForces().equals(new TroopCounts(1,0,1)) && home.forces().equals(new TroopCounts(4,4,3)), "Mixed deploy follows available proportions");
        h.assertFalse(ArmyCombat.execute(service, attacker, WORLD, ArmyCombat.Order.MOVE,0,0,0,1,5,10,TroopBranch.AIR).success(), "Reject missing air without touching ground");
        ArmyCombat.execute(service, attacker, WORLD, ArmyCombat.Order.MOVE,0,0,0,1,4,10,TroopBranch.AIR);
        h.assertTrue(other.forces().equals(new TroopCounts(1,4,0)), "Only the specified branch moves");
        var target = claim(1,0,defender,0);
        target.setForces(new TroopCounts(2,2,2));
        order(ArmyCombat.Order.EXCHANGE,0,0,0,0,1);
        order(ArmyCombat.Order.FIRE,0,0,1,0,1);
        h.assertTrue(home.forces().equals(new TroopCounts(1,0,0)) && target.forces().equals(new TroopCounts(1,1,1)), "Exchange and missile losses preserve surviving branches");
        stock.setGarrisonLevel(2);
        stock.replenishGarrison();
        h.assertTrue(stock.reserveForces().equals(new TroopCounts(3,0,1)), "Income adds ground to the existing reserve");
        h.succeed();
    }

    @GameTest(template="empty")
    public void mixedFieldForceLosesTheDuelingBranchAndRecallsSurvivors(GameTestHelper h) {
        var home = claim(0,0,attacker,0);
        home.setForces(new TroopCounts(5,3,2));
        var target = claim(1,0,defender,0);
        target.setForces(new TroopCounts(4,2,1));
        h.assertFalse(ArmyCombat.execute(service, attacker, WORLD, ArmyCombat.Order.ROLL,0,0,1,0,4,10,TroopBranch.AIR).success(), "Cannot commit unavailable branch");
        order(ArmyCombat.Order.ROLL,0,0,1,0,10);
        var battle = service.disputes().openAt(WORLD,1,0);
        h.assertTrue(home.armies()==0 && battle.assault().forces().equals(new TroopCounts(5,3,2)), "Entire mixed garrison can march");
        ArmyCombat.advance(service,battle,110,dice(1,6), n -> n-1);
        h.assertTrue(battle.assault().forces().equals(new TroopCounts(5,3,1)), "One naval attacker drawn from the last slot falls");
        ArmyCombat.advance(service,battle,210,dice(6,1), n -> n-1);
        h.assertTrue(target.forces().equals(new TroopCounts(4,2,0)), "One naval defender falls");
        service.disputeService().cancelOpenAt(WORLD,1,0,211,"test");
        h.assertTrue(home.forces().equals(new TroopCounts(5,3,1)), "Recall returns the actual survivors");
        h.succeed();
    }

    @GameTest(template="empty")
    public void battlefieldEffectsFollowOnlyTheRealDuel(GameTestHelper h) {
        claim(0,0,attacker,0).setForces(new TroopCounts(1,1,0));
        claim(1,0,defender,0).setForces(new TroopCounts(0,0,1));
        order(ArmyCombat.Order.ROLL,0,0,1,0,2);
        var battle = service.disputes().openAt(WORLD,1,0);
        var hits = new java.util.ArrayList<TroopBranch>();
        ArmyCombat.DuelListener observe = (a, d, won) -> hits.add(won ? d : a);
        long nextDuel = battle.assault().nextDuel();
        var cues = new java.util.EnumMap<TroopBranch, Integer>(TroopBranch.class);
        for (long tick = 10; tick < nextDuel; tick++) {
            var cue = BattleMotion.attackCue(tick, nextDuel);
            double phase = BattleMotion.phase(tick, nextDuel, 0);
            if (cue != null) cues.merge(cue, 1, Integer::sum);
            if (cue == TroopBranch.NAVAL) h.assertTrue(phase == BattleMotion.CANNON_PHASE, "Muzzle flash at shell launch");
            if (cue == TroopBranch.AIR) h.assertTrue(phase == BattleMotion.STRAFE_PHASE, "Air sound at first tracer");
            ArmyCombat.advance(service, battle, tick, dice(), n -> n-1, observe);
        }
        for (var branch : TroopBranch.ALL)
            h.assertTrue(cues.getOrDefault(branch, 0) == 2, "Two maneuvers per duel for " + branch);
        h.assertTrue(hits.isEmpty() && BattleMotion.phase(nextDuel, nextDuel, 0) == 0,
                "Only the roll at contact can produce a casualty");
        ArmyCombat.advance(service, battle, 110, dice(2,5), n -> n-1, observe);
        ArmyCombat.advance(service, battle, 210, dice(6,1), n -> n-1, observe);
        ArmyCombat.advance(service, battle, 310, dice(), n -> n-1, observe);
        h.assertTrue(hits.equals(java.util.List.of(TroopBranch.AIR, TroopBranch.NAVAL)),
                "Show each losing branch once, including the final defender");
        h.succeed();
    }

    @GameTest(template="empty")
    public void mixedAndLegacySavesRetainReserveGarrisonAndAssault(GameTestHelper h) throws Exception {
        var home = claim(0,0,attacker,0);
        home.setForces(new TroopCounts(1,2,3));
        claim(1,0,defender,4);
        attacker.economy().setReserveForces(new TroopCounts(7,8,9));
        ArmyCombat.execute(service,attacker,WORLD,ArmyCombat.Order.ROLL,0,0,1,0,2,10,TroopBranch.AIR);
        var dir = Files.createTempDirectory("troop-branches");
        try {
            var store = new com.modserver.data.ModServerStore(dir);
            store.save(service.nations(), service.claims(), service.disputes());
            for (boolean legacy : new boolean[]{false, true}) {
                if (legacy) {
                    for (String name : java.util.List.of("nations","claims","disputes")) {
                        var path = dir.resolve(name + ".json");
                        var root = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                        for (var entry : root.getAsJsonArray(name)) {
                            var data = entry.getAsJsonObject();
                            if (name.equals("nations")) {
                                data = data.getAsJsonObject("economy");
                                data.addProperty("defenseDebt", 9500);
                                data.addProperty("defended", false);
                            }
                            if (name.equals("disputes")) data = data.getAsJsonObject("assault");
                            data.remove("troops");
                        }
                        Files.writeString(path, root.toString());
                    }
                }
                var copy = new NationService();
                store.load(copy.nations(), copy.claims(), copy.disputes());
                h.assertTrue(copy.claims().claimAt(WORLD,0,0).forces().equals(legacy ? new TroopCounts(4,0,0) : new TroopCounts(1,0,3)), "Garrison migration");
                h.assertTrue(copy.nations().byId(attacker.id()).orElseThrow().economy().reserveForces().equals(
                        legacy ? new TroopCounts(24,0,0) : new TroopCounts(7,8,9)), "Reserve migration");
                var assault = copy.disputes().openAt(WORLD,1,0).assault();
                h.assertTrue(assault.forces().equals(legacy ? new TroopCounts(2,0,0) : new TroopCounts(0,2,0))
                        && assault.nextDuel()==110, "Active duel migration");
                if (legacy) {
                    var restored = copy.nations().byId(defender.id()).orElseThrow();
                    h.assertTrue(restored.economy().balance() == defender.economy().balance(), "Loading keeps the saved balance");
                    restored.economy().setBalance(10000);
                    var day = copy.economy().closeDay(restored, copy.economyRules(), 24000);
                    h.assertTrue(day.balance() == 10000 + day.income(), "Old defense debt cannot debit the treasury");
                    store.save(copy.nations(), copy.claims(), copy.disputes());
                    var saved = Files.readString(dir.resolve("nations.json"));
                    h.assertTrue(!saved.contains("\"defenseDebt\"") && !saved.contains("\"defended\""), "New saves drop old defense accounts");
                }
            }
        } finally {
            try (var files = Files.list(dir)) { for (var file : files.toList()) Files.delete(file); }
            Files.delete(dir);
        }
        h.succeed();
    }
    private void ally() {
        attacker.setRelation(defender.id(), NationRelation.ALLY);
        defender.setRelation(attacker.id(), NationRelation.ALLY);
    }

    @GameTest(template="empty")
    public void alliedShipmentsUseChunkDistanceAndArriveOnce(GameTestHelper h) {
        ally();
        var home = claim(-2,-2,attacker,0);
        home.setForces(new TroopCounts(6,15000,4));
        var near = claim(-1,-2,defender,2);
        var far = claim(70,3,defender,1);
        var send = ArmyCombat.Order.SEND;
        h.assertTrue(ArmyCombat.execute(service,attacker,WORLD,send,-2,-2,-1,-2,15000,10,TroopBranch.AIR).success(), "A grouped branch can travel in full");
        h.assertFalse(ArmyCombat.execute(service,attacker,WORLD,send,-2,-2,-1,-2,1,10,TroopBranch.AIR).success(), "In-transit troops cannot be reused");
        h.assertTrue(order(send,-2,-2,70,3,10).success(), "The remaining mixed force can traverse multiple map windows");
        h.assertTrue(home.armies()==0 && far.armies()==1 && near.armies()==2, "Deduct at departure and credit neither ally instantly");
        var page = Tactics.transitPage(service,defender,0,10);
        long nearArrival = 10 + 12_000;
        long farArrival = 10 + 77 * 12_000L;
        h.assertTrue(page.total()==2 && page.trip().remainingTicks()==12_000 && page.trip().legTicks()==12_000 && page.trip().troops().air()==15000, "Recipient sees the next convoy and exact remaining ticks");
        h.assertTrue(Tactics.transitPage(service,country("Other"),0,10).total()==0, "Third parties cannot inspect shipments");
        TroopTransfer.advance(service,nearArrival-1);
        h.assertTrue(near.armies()==2, "Not delivered even one tick early");
        TroopTransfer.advance(service,nearArrival);
        h.assertTrue(near.forces().equals(new TroopCounts(2,15000,0)) && far.armies()==1, "Short route arrives first");
        h.assertTrue(Tactics.transitPage(service,attacker,0,nearArrival).trip().distance()==77, "Distance counts both axes");
        TroopTransfer.advance(service,farArrival-1);
        h.assertTrue(far.armies()==1, "A distant ally must wait longer");
        TroopTransfer.advance(service,farArrival);
        TroopTransfer.advance(service,farArrival+1);
        h.assertTrue(far.forces().equals(new TroopCounts(7,0,4)) && attacker.transfers().isEmpty(), "All survivors delivered exactly once");
        h.succeed();
    }

    @GameTest(template="empty")
    public void onlyActiveAlliesCanReceiveTroops(GameTestHelper h) {
        var home = claim(0,0,attacker,4);
        claim(2,0,attacker,1);
        var ally = claim(4,0,defender,1);
        var send = ArmyCombat.Order.SEND;
        for (var relation : new NationRelation[]{NationRelation.NEUTRAL,NationRelation.PACT,NationRelation.WAR}) {
            attacker.setRelation(defender.id(),relation);
            defender.setRelation(attacker.id(),relation);
            h.assertFalse(order(send,0,0,4,0,1).success(),"Relation cannot receive aid: " + relation);
        }
        attacker.setRelation(defender.id(),NationRelation.ALLY);
        h.assertFalse(order(send,0,0,4,0,1).success(),"Both sides must be allied");
        ally();
        h.assertFalse(order(send,4,0,0,0,1).success(),"Cannot donate someone else's garrison");
        h.assertFalse(order(send,0,0,2,0,1).success(),"Cannot bypass domestic movement");
        h.assertFalse(order(send,0,0,5,0,1).success(),"Unclaimed land cannot receive aid");
        h.assertFalse(order(send,0,0,4,0,5).success(),"Cannot send more than available");
        ally.setArmies(Integer.MAX_VALUE);
        h.assertFalse(order(send,0,0,4,0,1).success(),"Do not dispatch to a full garrison");
        h.assertTrue(home.armies()==4 && attacker.transfers().isEmpty(),"Rejected orders are atomic");
        h.succeed();
    }

    @GameTest(template="empty")
    public void returningConvoysWaitForRoomAndRecoverActualBranches(GameTestHelper h) {
        ally();
        var home = claim(0,0,attacker,0);
        home.setForces(new TroopCounts(2,5,3));
        var target = claim(3,-1,defender,1);
        order(ArmyCombat.Order.SEND,0,0,3,-1,10);
        var rival = country("Third");
        service.claims().transfer(target,rival.id());
        long turnBack = 10 + 4 * 12_000;
        long backHome = turnBack + 4 * 12_000;
        TroopTransfer.advance(service,turnBack);
        h.assertTrue(target.armies()==1 && home.armies()==0 && attacker.transfers().iterator().next().leg()==TroopTransfer.Leg.RETURNING,"A captured destination starts a timed return");
        attacker.economy().setGarrisonPoints(Integer.MAX_VALUE);
        home.setArmies(Integer.MAX_VALUE-3);
        TroopTransfer.advance(service,backHome-1);
        h.assertTrue(attacker.transfers().iterator().next().leg()==TroopTransfer.Leg.RETURNING,"No instant refund");
        TroopTransfer.advance(service,backHome);
        h.assertTrue(attacker.transfers().iterator().next().leg()==TroopTransfer.Leg.WAITING,"Saved in transit until room is available");
        attacker.economy().setGarrisonPoints(0);
        TroopTransfer.advance(service,backHome+20);
        var shipment = new TroopCounts(2,5,3);
        var atHome = shipment.take(3,null);
        h.assertTrue(home.forces().equals(new TroopCounts(Integer.MAX_VALUE-3,0,0).plus(atHome))
                && attacker.economy().reserveForces().equals(shipment.minus(atHome)) && attacker.transfers().isEmpty(),"Home and reserve account for the entire returning force");
        h.succeed();
    }

    @GameTest(template="empty")
    public void reloadResumesOutboundAndReturnLegsWithOfflineOwners(GameTestHelper h) throws Exception {
        ally();
        var home = claim(3,0,attacker,0);
        home.setForces(new TroopCounts(1,3,6));
        claim(0,0,defender,2);
        order(ArmyCombat.Order.SEND,3,0,0,0,10);
        var dir = Files.createTempDirectory("allied-shipments");
        try {
            var store = new com.modserver.data.ModServerStore(dir);
            store.save(service.nations(),service.claims(),service.disputes());
            var resumed = new NationService();
            store.load(resumed.nations(),resumed.claims(),resumed.disputes());
            var sender = resumed.nations().byId(attacker.id()).orElseThrow();
            long arrival = 10 + 3 * 12_000;
            long returnArrival = arrival + 3 * 12_000;
            h.assertTrue(sender.transfers().iterator().next().dueAt()==arrival && resumed.claims().claimAt(WORLD,3,0).armies()==0,"Reload keeps departure debit and ETA");
            sender.setRelation(defender.id(),NationRelation.NEUTRAL);
            TroopTransfer.advance(resumed,arrival);
            h.assertTrue(resumed.claims().claimAt(WORLD,0,0).armies()==2,"Broken alliance prevents delivery");
            resumed.claims().release(WORLD,3,0);
            store.save(resumed.nations(),resumed.claims(),resumed.disputes());
            var again = new NationService();
            store.load(again.nations(),again.claims(),again.disputes());
            var owner = again.nations().byId(attacker.id()).orElseThrow();
            TroopTransfer.advance(again,returnArrival-1);
            h.assertTrue(owner.economy().garrisonPoints()==0,"Reload preserves remaining return time");
            TroopTransfer.advance(again,returnArrival);
            TroopTransfer.advance(again,returnArrival+1);
            h.assertTrue(owner.economy().reserveForces().equals(new TroopCounts(1,3,6)) && owner.transfers().isEmpty(),"Lost origin refunds to reserve exactly once");
        } finally {
            try (var files = Files.list(dir)) { for (var file : files.toList()) Files.delete(file); }
            Files.delete(dir);
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public void oldConvoyKeepsItsBookedPaceUntilArrival(GameTestHelper h) throws Exception {
        ally();
        claim(0,0,attacker,5);
        claim(3,0,defender,1);
        order(ArmyCombat.Order.SEND,0,0,3,0,5);
        var dir = Files.createTempDirectory("legacy-convoy");
        try {
            var store = new com.modserver.data.ModServerStore(dir);
            store.save(service.nations(),service.claims(),service.disputes());
            var path = dir.resolve("nations.json");
            var json = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            for (var nation : json.getAsJsonArray("nations")) {
                for (var entry : nation.getAsJsonObject().getAsJsonArray("transfers")) {
                    var trip = entry.getAsJsonObject();
                    trip.remove("secondsPerChunk");
                    trip.addProperty("dueAt",310);
                }
            }
            Files.writeString(path,json.toString());
            var copy = new NationService();
            store.load(copy.nations(),copy.claims(),copy.disputes());
            var sender = copy.nations().byId(attacker.id()).orElseThrow();
            var trip = Tactics.transitPage(copy,sender,0,160).trip();
            h.assertTrue(trip.legTicks()==300 && trip.remainingTicks()==150, "Legacy map marker is halfway through its original leg");
            sender.setRelation(defender.id(),NationRelation.NEUTRAL);
            TroopTransfer.advance(copy,309);
            h.assertTrue(sender.transfers().iterator().next().leg()==TroopTransfer.Leg.OUTBOUND,"Legacy convoy keeps its original arrival");
            TroopTransfer.advance(copy,310);
            var returning = Tactics.transitPage(copy,sender,0,310).trip();
            h.assertTrue(returning.leg()==TroopTransfer.Leg.RETURNING && returning.remainingTicks()==36_000
                    && returning.legTicks()==36_000,"A newly started return uses ten minutes per chunk");
        } finally {
            try (var files = Files.list(dir)) { for (var file : files.toList()) Files.delete(file); }
            Files.delete(dir);
        }
        h.succeed();
    }

}
