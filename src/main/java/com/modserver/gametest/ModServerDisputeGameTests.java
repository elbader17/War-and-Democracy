package com.modserver.gametest;

import com.modserver.claim.Claim;
import com.modserver.claim.ClaimIndex;
import com.modserver.dispute.Dispute;
import com.modserver.dispute.DisputeIndex;
import com.modserver.dispute.DisputeService;
import com.modserver.dispute.DisputeStatus;
import com.modserver.nation.Nation;
import com.modserver.nation.NationManager;
import com.modserver.nation.NationRelation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Tests of the state machine of the disputes between nations.
 *
 * <p>The whole GameTest uses the pure domain ({@link DisputeService}): no real
 * player nor server with connections is needed, so the state machine can be
 * verified entirely and quickly.</p>
 */
@GameTestHolder("modserver")
@PrefixGameTestTemplate(false)
public class ModServerDisputeGameTests {

    private static final String WORLD = "minecraft:overworld";
    /** 10 minutes of defense window, as in the default server. */
    private static final long WINDOW = 10L * 1200L;
    private static final long NO_COOLDOWN = 0L;
    /** Normal rules: 10 min window, no cooldowns. */
    private static final DisputeService.Rules RULES =
            new DisputeService.Rules(WINDOW, NO_COOLDOWN, NO_COOLDOWN, WINDOW, false);

    /** Rules with a 30 min cooldown for the challenging nation. */
    private static DisputeService.Rules rules(long window, long nationCooldown, long chunkCooldown) {
        return new DisputeService.Rules(window, nationCooldown, chunkCooldown, window, false);
    }

    /** War rules: 5 min window and no per-challenger cooldown. */
    private static DisputeService.Rules warRules() {
        return new DisputeService.Rules(WINDOW, 30L * 1200L, NO_COOLDOWN, 5L * 1200L, true);
    }

    /** Common scenario: two nations and a chunk of the first one. */
    private record Fixture(NationManager nations, ClaimIndex claims, DisputeIndex disputes,
                           DisputeService service, Nation attacker, Nation defender) {
    }

    private static Fixture fixture() {
        NationManager nations = new NationManager();
        ClaimIndex claims = new ClaimIndex();
        DisputeIndex disputes = new DisputeIndex();

        Nation attacker = nations.create(UUID.randomUUID(), "Atacante", UUID.randomUUID(),
                0xFF5555, 0L);
        Nation defender = nations.create(UUID.randomUUID(), "Defender", UUID.randomUUID(),
                0x55FFFF, 0L);

        claims.claim(new Claim(WORLD, 0, 0, defender.id(), 0L));

        return new Fixture(nations, claims, disputes,
                new DisputeService(nations, claims, disputes), attacker, defender);
    }

    /** Opening a dispute over a chunk of another nation: it is created and stays open. */
    @GameTest(template = "empty")
    public void disputeCanBeOpened(GameTestHelper helper) {
        Fixture f = fixture();

        DisputeService.Outcome outcome = f.service().open(f.attacker().id(), WORLD, 0, 0,100L, rules(WINDOW, NO_COOLDOWN, NO_COOLDOWN));

        helper.assertTrue(outcome.success(), "The dispute should open: " + outcome.message());
        Dispute dispute = outcome.dispute();
        helper.assertTrue(dispute != null, "The dispute should exist");
        helper.assertTrue(dispute.status() == DisputeStatus.OPEN, "The dispute should be open");
        helper.assertTrue(dispute.challengerNationId().equals(f.attacker().id()),
                "The challenger must be the attacking nation");
        helper.assertTrue(dispute.defenderNationId().equals(f.defender().id()),
                "The defender must be the nation that owns the chunk");
        helper.assertTrue(dispute.deadline() == 100L + WINDOW, "The defense window must be the one set");
        helper.assertTrue(f.claims().claimAt(WORLD, 0, 0).nationId().equals(f.defender().id()),
                "While the dispute is open the chunk still belongs to the defender");

        helper.succeed();
    }

    /** You cannot dispute your own chunk nor a free one. */
    @GameTest(template = "empty")
    public void cannotDisputeOwnOrFreeChunk(GameTestHelper helper) {
        Fixture f = fixture();

        DisputeService.Outcome own = f.service().open(f.defender().id(), WORLD, 0, 0,0L, rules(WINDOW, NO_COOLDOWN, NO_COOLDOWN));
        helper.assertTrue(!own.success(), "You cannot dispute your own chunk");

        DisputeService.Outcome free = f.service().open(f.attacker().id(), WORLD, 50, 50,0L, rules(WINDOW, NO_COOLDOWN, NO_COOLDOWN));
        helper.assertTrue(!free.success(), "You cannot dispute an unclaimed chunk");

        helper.succeed();
    }

    /** A pact between the two nations prevents opening disputes. */
    @GameTest(template = "empty")
    public void pactBlocksDisputes(GameTestHelper helper) {
        Fixture f = fixture();
        f.attacker().setRelation(f.defender().id(), NationRelation.PACT);

        DisputeService.Outcome outcome = f.service().open(f.attacker().id(), WORLD, 0, 0,0L, rules(WINDOW, NO_COOLDOWN, NO_COOLDOWN));
        helper.assertTrue(!outcome.success(), "A pact must prevent disputes");

        f.attacker().setRelation(f.defender().id(), NationRelation.WAR);
        DisputeService.Outcome war = f.service().open(f.attacker().id(), WORLD, 0, 0,0L, rules(WINDOW, NO_COOLDOWN, NO_COOLDOWN));
        helper.assertTrue(war.success(), "At war it can be disputed: " + war.message());

        helper.succeed();
    }

    /** There can only be one open dispute per chunk. */
    @GameTest(template = "empty")
    public void onlyOneDisputePerChunk(GameTestHelper helper) {
        Fixture f = fixture();

        helper.assertTrue(f.service().open(f.attacker().id(), WORLD, 0, 0,0L, rules(WINDOW, NO_COOLDOWN, NO_COOLDOWN)).success(), "The first dispute should open");

        DisputeService.Outcome second = f.service().open(f.attacker().id(), WORLD, 0, 0,10L, rules(WINDOW, NO_COOLDOWN, NO_COOLDOWN));
        helper.assertTrue(!second.success(), "There cannot be two open disputes on the same chunk");

        helper.succeed();
    }

    /** Defending in time: the chunk is kept by the defender. */
    @GameTest(template = "empty")
    public void defendingKeepsTheChunk(GameTestHelper helper) {
        Fixture f = fixture();
        f.service().open(f.attacker().id(), WORLD, 0, 0,100L, rules(WINDOW, NO_COOLDOWN, NO_COOLDOWN));

        DisputeService.Outcome outcome = f.service().defend(f.defender().id(), WORLD, 0, 0, 200L);
        helper.assertTrue(outcome.success(), "The defense should be accepted: " + outcome.message());
        helper.assertTrue(outcome.dispute().status() == DisputeStatus.DEFENDED,
                "The dispute must end up DEFENDED");
        helper.assertTrue(f.claims().claimAt(WORLD, 0, 0).nationId().equals(f.defender().id()),
                "The chunk must still belong to the defender");

        helper.succeed();
    }

    /** If the window expires without defense, the chunk goes to the challenger. */
    @GameTest(template = "empty")
    public void disputeExpiresAndTransfersTheChunk(GameTestHelper helper) {
        Fixture f = fixture();
        DisputeService.Outcome opened = f.service().open(f.attacker().id(), WORLD, 0, 0,100L, rules(WINDOW, NO_COOLDOWN, NO_COOLDOWN));

        // It has not expired yet.
        helper.assertTrue(f.service().expire(100L + WINDOW).isEmpty(),
                "The dispute must not expire early");

        // The window passes without defense: the chunk changes owner.
        var resolved = f.service().expire(100L + WINDOW + 1L);
        helper.assertTrue(resolved.size() == 1, "The dispute should expire");
        helper.assertTrue(resolved.get(0).status() == DisputeStatus.TRANSFERRED,
                "The dispute must end up TRANSFERRED");
        helper.assertTrue(f.claims().claimAt(WORLD, 0, 0).nationId().equals(f.attacker().id()),
                "The chunk must go to the challenging nation");

        helper.succeed();
    }

    /** Defending with the window already closed does not work: the chunk is already the challenger's. */
    @GameTest(template = "empty")
    public void defendingAfterDeadlineFails(GameTestHelper helper) {
        Fixture f = fixture();
        f.service().open(f.attacker().id(), WORLD, 0, 0,100L, rules(WINDOW, NO_COOLDOWN, NO_COOLDOWN));
        f.service().expire(100L + WINDOW + 1L);

        DisputeService.Outcome outcome = f.service().defend(f.defender().id(), WORLD, 0, 0,
                100L + WINDOW + 2L);
        helper.assertTrue(!outcome.success(), "You cannot defend a dispute that already expired");
        helper.assertTrue(f.claims().claimAt(WORLD, 0, 0).nationId().equals(f.attacker().id()),
                "The chunk is no longer the defender's");

        helper.succeed();
    }

    /** The per-nation cooldown prevents dispute spam. */
    @GameTest(template = "empty")
    public void cooldownBlocksRepeatedDisputes(GameTestHelper helper) {
        Fixture f = fixture();
        // Another chunk of the defending nation to have somewhere to dispute again.
        f.claims().claim(new Claim(WORLD, 2, 0, f.defender().id(), 0L));

        helper.assertTrue(f.service().open(f.attacker().id(), WORLD, 0, 0,
                0L, rules(WINDOW, 30L * 1200L, NO_COOLDOWN)).success(), "The first dispute should open");

        DisputeService.Outcome second = f.service().open(f.attacker().id(), WORLD, 2, 0,
                60L, rules(WINDOW, 30L * 1200L, NO_COOLDOWN));
        helper.assertTrue(!second.success(), "The per-nation cooldown must block the second dispute");

        // Once the cooldown has passed, it can be disputed again.
        DisputeService.Outcome later = f.service().open(f.attacker().id(), WORLD, 2, 0,
                31L * 1200L, rules(WINDOW, 30L * 1200L, NO_COOLDOWN));
        helper.assertTrue(later.success(), "After the cooldown it can be disputed: " + later.message());

        helper.succeed();
    }

    /** At war the defense window is shorter and the aggressor does not wait for a cooldown. */
    @GameTest(template = "empty")
    public void warShortensTheDefenseWindowAndSkipsTheCooldown(GameTestHelper helper) {
        Fixture f = fixture();
        f.attacker().setRelation(f.defender().id(), NationRelation.WAR);

        // With a normal cooldown, the second dispute of the same aggressor would be rejected.
        f.claims().claim(new Claim(WORLD, 2, 0, f.defender().id(), 0L));
        DisputeService.Outcome first = f.service().open(f.attacker().id(), WORLD, 0, 0,
                0L, rules(WINDOW, 30L * 1200L, NO_COOLDOWN));
        helper.assertTrue(first.success(), "The first dispute should open: " + first.message());
        helper.assertTrue(first.dispute().deadline() == WINDOW,
                "Without war the window must be the full one");

        // At war: the window is the war one and there is no per-challenger cooldown.
        DisputeService.Outcome war = f.service().open(f.attacker().id(), WORLD, 2, 0,
                10L, warRules());
        helper.assertTrue(war.success(),
                "At war the per-challenger cooldown must not apply: " + war.message());
        helper.assertTrue(war.dispute().deadline() == 10L + 5L * 1200L,
                "At war the defense window must be the war one");

        helper.succeed();
    }

    /** The administrator can resolve the dispute by hand at any time. */
    @GameTest(template = "empty")
    public void adminCanResolveDisputes(GameTestHelper helper) {
        Fixture f = fixture();
        DisputeService.Outcome opened = f.service().open(f.attacker().id(), WORLD, 0, 0,0L, rules(WINDOW, NO_COOLDOWN, NO_COOLDOWN));
        long id = opened.dispute().id();

        DisputeService.Outcome keep = f.service().resolve(id, true, 50L);
        helper.assertTrue(keep.success(), "The admin must be able to resolve: " + keep.message());
        helper.assertTrue(keep.dispute().status() == DisputeStatus.KEPT,
                "The dispute must end up KEPT");
        helper.assertTrue(f.claims().claimAt(WORLD, 0, 0).nationId().equals(f.defender().id()),
                "With keep the chunk stays with the defender");

        // The administrator can re-resolve a defended dispute (their decision does not
        // bind the server administration).
        DisputeService.Outcome transferAfterDefence = f.service().resolve(id, false, 55L);
        helper.assertTrue(transferAfterDefence.success(),
                "The admin must be able to transfer an already defended dispute: "
                        + transferAfterDefence.message());
        helper.assertTrue(f.claims().claimAt(WORLD, 0, 0).nationId().equals(f.attacker().id()),
                "With transfer the chunk goes to the challenger");

        // An already transferred dispute cannot be touched again.
        DisputeService.Outcome again = f.service().resolve(id, true, 60L);
        helper.assertTrue(!again.success(), "A transferred dispute cannot be resolved again");

        // And the manual transfer works on a new dispute.
        f.claims().claim(new Claim(WORLD, 3, 3, f.defender().id(), 0L));
        DisputeService.Outcome second = f.service().open(f.attacker().id(), WORLD, 3, 3,0L, rules(WINDOW, NO_COOLDOWN, NO_COOLDOWN));
        DisputeService.Outcome transfer = f.service().resolve(second.dispute().id(), false, 10L);
        helper.assertTrue(transfer.success(), "The admin must be able to transfer: " + transfer.message());
        helper.assertTrue(f.claims().claimAt(WORLD, 3, 3).nationId().equals(f.attacker().id()),
                "With transfer the chunk goes to the challenger");

        helper.succeed();
    }

    /** If the defending nation releases the chunk, the dispute is cancelled. */
    @GameTest(template = "empty")
    public void releasingTheChunkCancelsTheDispute(GameTestHelper helper) {
        Fixture f = fixture();
        f.service().open(f.attacker().id(), WORLD, 0, 0,0L, rules(WINDOW, NO_COOLDOWN, NO_COOLDOWN));

        f.claims().release(WORLD, 0, 0);
        Dispute cancelled = f.service().cancelOpenAt(WORLD, 0, 0, 10L, "release");

        helper.assertTrue(cancelled != null, "The dispute should be cancelled");
        helper.assertTrue(cancelled.status() == DisputeStatus.CANCELLED,
                "The dispute must end up CANCELLED");
        helper.assertTrue(f.service().expire(10L * WINDOW).isEmpty(),
                "A cancelled dispute does not expire");

        helper.succeed();
    }
}
