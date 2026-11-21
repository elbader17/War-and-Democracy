package com.modserver;

import com.modserver.claim.Claim;
import com.modserver.claim.ClaimIndex;
import com.modserver.command.ModServerCommands;
import com.modserver.config.ModServerConfig;
import com.modserver.data.ModServerStore;
import com.modserver.dispute.Dispute;
import com.modserver.dispute.DisputeIndex;
import com.modserver.dispute.DisputeService;
import com.modserver.dispute.DisputeStatus;
import com.modserver.economy.EconomyRules;
import com.modserver.economy.EconomyService;
import com.modserver.economy.Investment;
import com.modserver.economy.NationEconomy;
import com.modserver.nation.Nation;
import com.modserver.nation.NationManager;
import com.modserver.nation.NationRelation;
import com.modserver.network.NationMapPayload;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain service: the game rules of the nations and the claims.
 *
 * <p>Rules set by the project owner:</p>
 * <ul>
 *   <li>One nation per player (owner).</li>
 *   <li>A nation survives the death of its owner.</li>
 *   <li>Claims do not expire.</li>
 *   <li>Blocks can be broken: there is no anti-grief protection.</li>
 *   <li>1 chunk per claim, only in the overworld.</li>
 *   <li>Nations can sign agreements (pacts, alliances, wars).</li>
 * </ul>
 */
public final class NationService {

    /** Result of an operation with a message ready to be sent to the player. */
    public record Result(boolean success, String message) {

        public static Result ok(String message) {
            return new Result(true, message);
        }

        public static Result fail(String message) {
            return new Result(false, message);
        }
    }

    private final NationManager nations = new NationManager();
    private final ClaimIndex claims = new ClaimIndex();
    private final DisputeIndex disputes = new DisputeIndex();
    private final DisputeService disputeService = new DisputeService(nations, claims, disputes);
    private final EconomyService economyService = new EconomyService(claims);
    private ModServerStore store;
    private boolean dirty;
    private boolean mapDirty;
    /** Current server (for the game time and the notifications). */
    private MinecraftServer currentServer;

    /** The live server (set by the mod lifecycle on startup). */
    public void setServer(MinecraftServer server) {
        this.currentServer = server;
    }

    // ------------------------------------------------------------- persistence
    public void load(Path worldDirectory) throws IOException {
        ModServerStore newStore = new ModServerStore(worldDirectory.resolve("modserver"));
        newStore.load(nations, claims, disputes);
        this.store = newStore;
        this.dirty = false;
    }

    public void save() throws IOException {
        if (store != null) {
            store.save(nations, claims, disputes);
            dirty = false;
        }
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    /** Marks the map data as outdated (it has to be resent to the clients). */
    public void markMapDirty() {
        mapDirty = true;
    }

    public boolean isMapDirty() {
        return mapDirty;
    }

    public void clearMapDirty() {
        mapDirty = false;
    }

    public Path dataDirectory() {
        return store == null ? null : store.directory();
    }

    // ----------------------------------------------------------------- queries
    public NationManager nations() {
        return nations;
    }

    public ClaimIndex claims() {
        return claims;
    }

    public Collection<Nation> allNations() {
        return nations.all();
    }

    public Optional<Nation> nationOf(UUID player) {
        return nations.ownedBy(player);
    }

    public Optional<Nation> membershipOf(UUID player) {
        return nations.membershipOf(player);
    }

    public Optional<Nation> nationByName(String name) {
        return nations.byName(name);
    }

    /** The nation the player can use to claim (their own or the one they belong to). */
    public Optional<Nation> actingNation(UUID player) {
        Optional<Nation> owned = nations.ownedBy(player);
        return owned.isPresent() ? owned : nations.membershipOf(player);
    }

    // ----------------------------------------------------------------- operations
    public Result createNation(ServerPlayer player, String name, int color) {
        return foundNation(player.getUUID(), name, color, dimensionId(player), player.chunkPosition(),
                player.serverLevel().getGameTime());
    }

    public Result createRivalNation(UUID owner, String name, String dimension, ChunkPos chunk, long now) {
        if (!configBoolean(ModServerConfig.CLAIMS_ENABLED, true) ||
                (configBoolean(ModServerConfig.CROSS_DIMENSION_DISABLED, true) && !isOverworld(dimension))) {
            return Result.fail("Territory claims are disabled in this dimension.");
        }
        if (!claims.isFree(dimension, chunk.x, chunk.z)) {
            return Result.fail("Choose an unclaimed chunk for the rival nation.");
        }
        Result founded = foundNation(owner, name, 0x9C78EA, dimension, chunk, now);
        if (founded.success()) {
            Nation rival = nationOf(owner).orElseThrow();
            claims.claim(new Claim(dimension, chunk.x, chunk.z, rival.id(), now));
            int limit = Math.min(3, configInt(ModServerConfig.MAX_CHUNKS_PER_NATION, 100));
            for (Direction side : Direction.Plane.HORIZONTAL) {
                if (claims.chunkCount(rival.id()) >= limit) break;
                claims.claim(new Claim(dimension, chunk.x + side.getStepX(), chunk.z + side.getStepZ(), rival.id(), now));
            }
        }
        return founded;
    }

    private Result foundNation(UUID owner, String name, int color, String dimension, ChunkPos chunk, long now) {
        List<String> problems = NationManager.validateName(name);
        if (!problems.isEmpty()) {
            return Result.fail(problems.get(0));
        }
        if (nations.byName(name).isPresent()) {
            return Result.fail("A nation called " + name + " already exists");
        }

        if (nations.ownedBy(owner).isPresent()) {
            return Result.fail("You already have a nation ("
                    + nations.ownedBy(owner).get().name() + ")");
        }

        int rgb = color & 0xFFFFFF;
        Nation nation;
        try {
            nation = nations.create(UUID.randomUUID(), name, owner, rgb,
                    now);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
        nation.setCapital(dimension, chunk.x, chunk.z);
        // Starting balance: the nation is born with a treasury to pay for its first defense.
        nation.economy().setBalance(economyRules().startingBalance());
        nation.economy().setDefended(true);

        markDirty();
        markMapDirty();
        return Result.ok("Nation " + nation.name() + " created. Capital at " + chunk.x + ", " + chunk.z);
    }

    public Result claimChunk(ServerPlayer player) {
        if (!configBoolean(ModServerConfig.CLAIMS_ENABLED, true)) {
            return Result.fail("Claims are disabled on this server");
        }
        Optional<Nation> nation = actingNation(player.getUUID());
        if (nation.isEmpty()) {
            return Result.fail("You do not have a nation. Use /nation create <name>");
        }
        Nation owned = nation.get();
        if (!owned.roleOf(player.getUUID()).canManageClaims()) {
            return Result.fail("You cannot claim chunks for this nation");
        }

        String dimension = dimensionId(player);
        if (configBoolean(ModServerConfig.CROSS_DIMENSION_DISABLED, true) && !isOverworld(dimension)) {
            return Result.fail("You can only claim in the overworld");
        }

        ChunkPos chunk = player.chunkPosition();
        Claim existing = claims.claimAt(dimension, chunk.x, chunk.z);
        if (existing != null) {
            Optional<Nation> owner = nations.byId(existing.nationId());
            return Result.fail("This chunk already belongs to "
                    + (owner.map(Nation::name).orElse("another nation")));
        }

        int maxChunks = configInt(ModServerConfig.MAX_CHUNKS_PER_NATION, 100);
        if (claims.chunkCount(owned.id()) >= maxChunks) {
            return Result.fail("Your nation has reached the chunk limit (" + maxChunks + ")");
        }

        int minDistance = configInt(ModServerConfig.MIN_CLAIM_DISTANCE, 0);
        if (minDistance > 0 && owned.hasCapital()) {
            int dx = Math.abs(owned.capitalX() - chunk.x);
            int dz = Math.abs(owned.capitalZ() - chunk.z);
            if (Math.max(dx, dz) < minDistance) {
                return Result.fail("You must be " + minDistance + " chunks away from your capital to claim");
            }
        }

        // Contiguous territory: a chunk can only be claimed when it touches the
        // land the nation already owns. The first chunk of a nation must be next
        // to its capital, so the territory always grows as one shape.
        if (configBoolean(ModServerConfig.CLAIMS_MUST_BE_CONTIGUOUS, true)
                && !isNextToTerritory(owned, dimension, chunk.x, chunk.z)) {
            return Result.fail("You can only claim chunks next to your territory"
                    + (claims.chunkCount(owned.id(), dimension) == 0 ? " (next to your capital)" : ""));
        }

        claims.claim(new Claim(dimension, chunk.x, chunk.z, owned.id(), player.serverLevel().getGameTime()));
        markDirty();
        markMapDirty();
        return Result.ok("Chunk " + chunk.x + ", " + chunk.z + " claimed by " + owned.name());
    }

    public Result unclaimChunk(ServerPlayer player) {
        Optional<Nation> nation = actingNation(player.getUUID());
        if (nation.isEmpty()) {
            return Result.fail("You do not have a nation");
        }
        Nation owned = nation.get();
        String dimension = dimensionId(player);
        ChunkPos chunk = player.chunkPosition();
        Claim existing = claims.claimAt(dimension, chunk.x, chunk.z);
        if (existing == null) {
            return Result.fail("This chunk is not claimed");
        }
        if (!existing.nationId().equals(owned.id())) {
            return Result.fail("This chunk belongs to another nation");
        }
        claims.release(dimension, chunk.x, chunk.z);
        // If the chunk was under dispute, the dispute is cancelled: there is nothing to defend.
        notifyOutcome(disputeService.cancelOpenAt(dimension, chunk.x, chunk.z,
                gameTime(player), "release"));
        markDirty();
        markMapDirty();
        return Result.ok("Chunk " + chunk.x + ", " + chunk.z + " released");
    }

    // ------------------------------------------------------------------- disputes
    /** Disputes index (full history). */
    public DisputeIndex disputes() {
        return disputes;
    }

    /** Disputes domain service. */
    public DisputeService disputeService() {
        return disputeService;
    }

    /**
     * Opens a dispute over a chunk.
     *
     * @param chunkX chunk coordinate, or {@code null} to use the player's one
     */
    public Result openDispute(ServerPlayer player, Integer chunkX, Integer chunkZ) {
        Optional<Nation> nation = nationOf(player.getUUID());
        if (nation.isEmpty()) {
            return Result.fail("You do not have a nation");
        }
        Nation challenger = nation.get();
        if (!challenger.roleOf(player.getUUID()).canManageClaims()) {
            return Result.fail("You cannot open disputes with this nation");
        }

        int x = chunkX == null ? player.chunkPosition().x : chunkX;
        int z = chunkZ == null ? player.chunkPosition().z : chunkZ;
        String dimension = dimensionId(player);

        // Attacking costs coins: it is a deposit that is not refunded.
        EconomyRules rules = economyRules();
        boolean atWar = isAtWar(challenger, claimNationId(dimension, x, z));
        EconomyService.Outcome canPay = economyService.canAttack(challenger, rules, atWar);
        if (!canPay.success()) {
            return Result.fail(canPay.message());
        }

        DisputeService.Outcome outcome = disputeService.open(challenger.id(), dimension, x, z,
                gameTime(player), disputeRules());
        if (!outcome.success()) {
            return Result.fail(outcome.message());
        }
        economyService.spend(challenger, rules.attackCost(atWar));

        markDirty();
        markMapDirty();
        Dispute dispute = outcome.dispute();
        nations.byId(dispute.defenderNationId()).ifPresent(n -> notifyNation(n, "modserver.war.disputed", challenger.name(), x, z));
        long windowTicks = outcome.dispute().deadline() - outcome.dispute().openedAt();
        return Result.ok(outcome.message() + ". The affected nation has "
                + (windowTicks / 1200L) + " min to defend it");
    }

    /** The owning nation answers the dispute: the chunk is kept. */
    public Result defendDispute(ServerPlayer player, Integer chunkX, Integer chunkZ) {
        Optional<Nation> nation = nationOf(player.getUUID());
        if (nation.isEmpty()) {
            return Result.fail("You do not have a nation");
        }
        Nation defender = nation.get();

        int x = chunkX == null ? player.chunkPosition().x : chunkX;
        int z = chunkZ == null ? player.chunkPosition().z : chunkZ;
        String dimension = dimensionId(player);

        // An unprotected nation (it has not paid for defense) cannot defend.
        if (!economyService.canDefend(defender)) {
            return Result.fail("Your nation is UNPROTECTED: it has not paid for defense. "
                    + "Use /nation defense pay to pay it");
        }

        DisputeService.Outcome outcome = disputeService.defend(defender.id(), dimension, x, z,
                gameTime(player));
        if (!outcome.success()) {
            return Result.fail(outcome.message());
        }

        markDirty();
        markMapDirty();
        notifyOutcome(outcome.dispute());
        return Result.ok(outcome.message());
    }

    /** Manual resolution of a dispute (administrators). */
    public Result resolveDispute(long id, boolean keep) {
        long gameTime = currentGameTime();
        DisputeService.Outcome outcome = disputeService.resolve(id, keep, gameTime);
        if (!outcome.success()) {
            return Result.fail(outcome.message());
        }
        markDirty();
        markMapDirty();
        notifyOutcome(outcome.dispute());
        return Result.ok(outcome.message());
    }

    /**
     * Resolves the disputes whose defense window has expired.
     *
     * <p>The server tick calls it: if nobody defends, the chunk changes owner.
     * Before expiring, the <b>garrison</b> of the defending nation can cover the
     * defense without any player being connected (it is a paid investment).</p>
     */
    public List<Dispute> expireDisputes(long gameTime) {
        garrisonDefend(gameTime);
        List<Dispute> resolved = disputeService.expire(gameTime);
        if (!resolved.isEmpty()) {
            markDirty();
            markMapDirty();
            for (Dispute dispute : resolved) {
                notifyOutcome(dispute);
                String chunk = dispute.chunkX() + ", " + dispute.chunkZ();
                if (dispute.status() == DisputeStatus.TRANSFERRED) {
                    ModServerCommands.LOGGER.info("Dispute #{} expired: the chunk {} goes to {}",
                            dispute.id(), chunk, nameOf(dispute.challengerNationId()));
                } else {
                    ModServerCommands.LOGGER.info("Dispute #{} cancelled: the chunk {} was no longer claimed",
                            dispute.id(), chunk);
                }
            }
        }
        return resolved;
    }

    /**
     * The garrison defends the disputes that are about to expire.
     *
     * <p>Each garrison point covers one dispute and is restored when paying the
     * daily defense. An unprotected nation cannot use its garrison (there is nobody
     * to sustain it).</p>
     */
    private void garrisonDefend(long gameTime) {
        for (Dispute dispute : new ArrayList<>(disputes.open())) {
            if (gameTime <= dispute.deadline()) {
                continue;
            }
            Claim claim = claims.claimAt(dispute.dimension(), dispute.chunkX(), dispute.chunkZ());
            if (claim == null || !claim.nationId().equals(dispute.defenderNationId())) {
                continue;
            }
            Nation defender = nations.byId(dispute.defenderNationId()).orElse(null);
            if (defender == null) {
                continue;
            }
            if (!economyService.tryGarrisonDefense(defender)) {
                continue;
            }
            dispute.setStatus(DisputeStatus.DEFENDED);
            dispute.setResolvedAt(gameTime);
            dispute.setResolvedBy("garrison");
            disputes.reindex(dispute);
            markDirty();
            markMapDirty();
            notifyOutcome(dispute);
            ModServerCommands.LOGGER.info("The garrison of {} defends the chunk {}, {} (dispute #{})",
                    defender.name(), dispute.chunkX(), dispute.chunkZ(), dispute.id());
        }
    }

    private void notifyOutcome(Dispute dispute) {
        if (dispute == null) return;
        String chunk = dispute.chunkX() + ", " + dispute.chunkZ();
        String key = switch (dispute.status()) {
            case TRANSFERRED -> "modserver.war.captured";
            case DEFENDED, KEPT -> "garrison".equals(dispute.resolvedBy()) ? "modserver.war.garrison" : "modserver.war.kept";
            case CANCELLED -> "modserver.war.cancelled";
            case OPEN -> throw new IllegalArgumentException("The dispute is still open");
        };
        Object[] args = dispute.status() == DisputeStatus.CANCELLED ? new Object[]{chunk} :
                new Object[]{nameOf(dispute.status() == DisputeStatus.TRANSFERRED
                        ? dispute.challengerNationId() : dispute.defenderNationId()), chunk};
        nations.byId(dispute.challengerNationId()).ifPresent(n -> notifyNation(n, key, args));
        nations.byId(dispute.defenderNationId()).ifPresent(n -> notifyNation(n, key, args));
    }

    /** All the disputes (for the list command). */
    public Collection<Dispute> allDisputes() {
        return disputes.all();
    }

    public String nameOf(UUID nationId) {
        return nations.byId(nationId).map(Nation::name).orElse("unknown");
    }

    private long currentGameTime() {
        return currentServer == null ? 0L : currentServer.overworld().getGameTime();
    }

    /**
     * Overworld game time (the dimension where the claims live).
     *
     * <p>The overworld is always used and not the server {@code getTickCount()}: the
     * server tick does not pause with {@code /time set} and the defense windows
     *_would measure_ a different clock than the one used to open the dispute.</p>
     */
    private static long gameTime(ServerPlayer player) {
        return player.server == null ? 0L : player.server.overworld().getGameTime();
    }

    // ------------------------------------------------------------------- economy
    /** Economy service (pure domain). */
    public EconomyService economy() {
        return economyService;
    }

    /** Current economy rules (read from the configuration). */
    public EconomyRules economyRules() {
        return new EconomyRules(
                configInt(ModServerConfig.STARTING_BALANCE, 100) * NationEconomy.CENTS,
                configInt(ModServerConfig.CLAIM_INCOME, 12) * NationEconomy.CENTS,
                configInt(ModServerConfig.CLAIM_UPKEEP, 1) * NationEconomy.CENTS,
                configInt(ModServerConfig.DEFENSE_BASE, 20) * NationEconomy.CENTS,
                configInt(ModServerConfig.DEFENSE_PER_CHUNK, 2) * NationEconomy.CENTS,
                configInt(ModServerConfig.DEFENSE_SCALE, 5) * NationEconomy.CENTS,
                configInt(ModServerConfig.ATTACK_COST, 50) * NationEconomy.CENTS,
                configInt(ModServerConfig.WAR_ATTACK_DISCOUNT, 50),
                configInt(ModServerConfig.FARM_COST, 100) * NationEconomy.CENTS,
                configInt(ModServerConfig.GARRISON_COST, 150) * NationEconomy.CENTS,
                configInt(ModServerConfig.TREASURY_COST, 200) * NationEconomy.CENTS,
                configInt(ModServerConfig.FARM_UPKEEP, 10) * NationEconomy.CENTS,
                configInt(ModServerConfig.GARRISON_UPKEEP, 15) * NationEconomy.CENTS,
                configInt(ModServerConfig.TREASURY_UPKEEP, 20) * NationEconomy.CENTS,
                configInt(ModServerConfig.ECONOMY_MAX_LEVEL, 3));
    }

    /** Buys or levels up an investment (farm, garrison or treasury). */
    public Result invest(ServerPlayer player, String investmentName) {
        Optional<Nation> nation = nationOf(player.getUUID());
        if (nation.isEmpty()) {
            return Result.fail("You do not have a nation");
        }
        Nation owner = nation.get();
        if (!owner.roleOf(player.getUUID()).canManageMembers()) {
            return Result.fail("Only the owner or an officer can invest");
        }
        Investment investment = Investment.parse(investmentName);
        if (investment == null) {
            return Result.fail("Unknown investment: " + investmentName
                    + " (farm, garrison or treasury)");
        }
        EconomyService.Outcome outcome = economyService.purchase(owner, investment, economyRules());
        if (!outcome.success()) {
            return Result.fail(outcome.message());
        }
        markDirty();
        return Result.ok(outcome.message());
    }

    /** Pays the pending defense of the nation. */
    public Result payDefense(ServerPlayer player) {
        Optional<Nation> nation = nationOf(player.getUUID());
        if (nation.isEmpty()) {
            return Result.fail("You do not have a nation");
        }
        EconomyService.Outcome outcome = economyService.payDefense(nation.get(), economyRules());
        if (!outcome.success()) {
            return Result.fail(outcome.message());
        }
        markDirty();
        return Result.ok(outcome.message());
    }

    /**
     * Closes the economic day of every nation.
     *
     * <p>The server tick calls it once per game day.</p>
     */
    public List<EconomyService.DailyReport> closeEconomyDay(long gameTime) {
        List<EconomyService.DailyReport> reports = new ArrayList<>();
        if (!configBoolean(ModServerConfig.ECONOMY_ENABLED, true)) {
            return reports;
        }
        EconomyRules rules = economyRules();
        for (Nation nation : nations.all()) {
            EconomyService.DailyReport report = economyService.closeDay(nation, rules, gameTime);
            reports.add(report);
            ModServerCommands.LOGGER.info(report.message());
        }
        if (!reports.isEmpty()) {
            markDirty();
            markMapDirty();
        }
        return reports;
    }

    /** Identifier of the nation that owns a chunk (or null if it is free). */
    /**
     * Checks whether a chunk touches the territory of a nation.
     *
     * <p>If the nation has no chunk in that dimension yet, the capital is used as
     * the anchor: the first claim has to be next to it.</p>
     */
    private boolean isNextToTerritory(Nation nation, String dimension, int chunkX, int chunkZ) {
        if (claims.chunkCount(nation.id(), dimension) > 0) {
            return claims.isAdjacentTo(nation.id(), dimension, chunkX, chunkZ);
        }
        if (!nation.hasCapital()) {
            return false;
        }
        int dx = Math.abs(nation.capitalX() - chunkX);
        int dz = Math.abs(nation.capitalZ() - chunkZ);
        return Math.max(dx, dz) <= 1;
    }

    private UUID claimNationId(String dimension, int chunkX, int chunkZ) {
        Claim claim = claims.claimAt(dimension, chunkX, chunkZ);
        return claim == null ? null : claim.nationId();
    }

    /** Whether two nations are at war. */
    private boolean isAtWar(Nation nation, UUID otherNationId) {
        if (otherNationId == null) {
            return false;
        }
        return nation.relationWith(otherNationId) == NationRelation.WAR;
    }

    /** Forces the economic day close (administrators). */
    public Result adminEconomyTick() {
        var reports = closeEconomyDay(currentGameTime());
        if (reports.isEmpty()) {
            return Result.fail("The economy is disabled on this server");
        }
        StringBuilder summary = new StringBuilder("Economic day closed: ");
        for (var report : reports) {
            summary.append(report.message()).append(" | ");
        }
        return Result.ok(summary.toString());
    }

    /** Adjusts the balance of a nation (administrators). */
    public Result adminSetBalance(String nationName, double coins) {
        Optional<Nation> nation = nationByName(nationName);
        if (nation.isEmpty()) {
            return Result.fail("The nation " + nationName + " does not exist");
        }
        long cents = Math.round(coins * NationEconomy.CENTS);
        nation.get().economy().setBalance(cents);
        markDirty();
        return Result.ok("The balance of " + nation.get().name() + " is now "
                + EconomyService.coins(cents) + " coins");
    }

    // ----------------------------------------------------------------- diplomacy
    /**
     * Declares (or changes) the diplomatic relation between two nations.
     *
     * <p>It notifies the other nation and leaves a record in the server log.</p>
     */
    public Result setRelation(ServerPlayer player, String otherName, NationRelation relation) {
        Optional<Nation> mine = nationOf(player.getUUID());
        if (mine.isEmpty()) {
            return Result.fail("You do not have a nation");
        }
        Optional<Nation> other = nationByName(otherName);
        if (other.isEmpty()) {
            return Result.fail("The nation " + otherName + " does not exist");
        }
        if (other.get().id().equals(mine.get().id())) {
            return Result.fail("You cannot sign agreements with yourself");
        }
        if (!mine.get().roleOf(player.getUUID()).canManageMembers()) {
            return Result.fail("Only the owner or an officer can sign agreements");
        }
        NationRelation previous = mine.get().relationWith(other.get().id());
        mine.get().setRelation(other.get().id(), relation);
        other.get().setRelation(mine.get().id(), relation);
        markDirty();
        markMapDirty();
        notifyNation(other.get(), "modserver.relation." + relation.name().toLowerCase(java.util.Locale.ROOT));
        ModServerCommands.LOGGER.info("Relation between {} and {}: {} -> {}",
                mine.get().name(), other.get().name(), previous, relation);
        return Result.ok(relationLabel(relation)
                + " agreement signed between " + mine.get().name() + " and " + other.get().name());
    }

    /**
     * Attacks a chunk of another nation: it declares war (if there is none) and opens the dispute.
     *
     * <p>It is the "invasion" shortcut: a single order to go from neutrality to conflict.</p>
     */
    public Result attack(ServerPlayer player, Integer chunkX, Integer chunkZ) {
        Optional<Nation> mine = nationOf(player.getUUID());
        if (mine.isEmpty()) {
            return Result.fail("You do not have a nation");
        }
        int x = chunkX == null ? player.chunkPosition().x : chunkX;
        int z = chunkZ == null ? player.chunkPosition().z : chunkZ;
        return attack(mine.get(), dimensionId(player), x, z, gameTime(player));
    }

    public Result attack(Nation attacker, String dimension, int x, int z, long now) {
        Claim target = claims.claimAt(dimension, x, z);
        if (target == null) {
            return Result.fail("That chunk is not claimed by anyone");
        }
        if (target.nationId().equals(attacker.id())) {
            return Result.fail("That chunk already belongs to your nation");
        }
        Nation victim = nations.byId(target.nationId()).orElse(null);
        if (victim == null) {
            return Result.fail("That chunk belongs to a nation that no longer exists");
        }
        if (attacker.relationWith(victim.id()).blocksDisputes()) {
            return Result.fail("There is a peace agreement with " + victim.name()
                    + ": you cannot attack its chunks");
        }

        boolean declaringWar = attacker.relationWith(victim.id()) != NationRelation.WAR;
        EconomyRules rules = economyRules();
        EconomyService.Outcome canPay = economyService.canAttack(attacker, rules, true);
        if (!canPay.success()) {
            return Result.fail(canPay.message());
        }

        DisputeService.Outcome outcome = disputeService.open(attacker.id(), dimension, x, z,
                now, disputeRules(), declaringWar);
        if (!outcome.success()) {
            return Result.fail(outcome.message());
        }
        // Attack deposit: it is spent and not refunded.
        long cost = rules.attackCost(true);
        economyService.spend(attacker, cost);
        markDirty();
        markMapDirty();
        if (declaringWar) {
            ModServerCommands.LOGGER.info("{} declares war on {}", attacker.name(), victim.name());
        }
        notifyNation(victim, "modserver.war.invasion", attacker.name(), x, z);
        return Result.ok(outcome.message() + ". Attack paid: "
                + EconomyService.coins(cost) + " coins");
    }

    /** Dispute rules that apply right now (they are read from the configuration). */
    public DisputeService.Rules disputeRules() {
        return new DisputeService.Rules(
                configInt(ModServerConfig.DISPUTE_WINDOW_MINUTES, 10) * 1200L,
                configInt(ModServerConfig.DISPUTE_COOLDOWN_MINUTES, 30) * 1200L,
                configInt(ModServerConfig.DISPUTE_CHUNK_COOLDOWN_MINUTES, 120) * 1200L,
                configInt(ModServerConfig.WAR_DISPUTE_WINDOW_MINUTES, 5) * 1200L,
                configBoolean(ModServerConfig.WAR_IGNORES_DISPUTE_COOLDOWN, true));
    }

    /** Readable description of a diplomatic relation. */
    public static String describeRelation(NationRelation relation) {
        return switch (relation) {
            case WAR -> "at war";
            case PACT -> "with a non-aggression pact";
            case ALLY -> "allied";
            case NEUTRAL -> "neutral";
        };
    }

    /** Readable name of a relation (used in the agreement messages). */
    public static String relationLabel(NationRelation relation) {
        return switch (relation) {
            case WAR -> "War";
            case PACT -> "Pact";
            case ALLY -> "Ally";
            case NEUTRAL -> "Neutral";
        };
    }

    /** Warns the connected players of a nation. */
    private void notifyNation(Nation nation, String key, Object... args) {
        if (currentServer == null) {
            return;
        }
        for (ServerPlayer online : currentServer.getPlayerList().getPlayers()) {
            if (nation.isMember(online.getUUID())) {
                online.sendSystemMessage(Component.literal("[nation] " + nation.name() + ": " + ServerLanguage.of(online).text(key, args)));
            }
        }
    }

    // -------------------------------------------------------------------- utils
    /**
     * Builds the map data (nations + nearby claims) for a player.
     *
     * <p>Only the claims inside the sync radius are sent: the minimap does not
     * need the whole world and the packet stays small.</p>
     */
    public NationMapPayload buildMapPayload(ServerPlayer player) {
        int radius = configInt(ModServerConfig.MAP_SYNC_RADIUS_CHUNKS, 16);
        int maxClaims = configInt(ModServerConfig.MAP_MAX_MARKERS, 300);

        ChunkPos center = player.chunkPosition();
        String dimension = dimensionId(player);
        List<Claim> nearby = claims.claimsNear(dimension, center.x, center.z, radius);

        List<NationMapPayload.NationView> nationViews = new ArrayList<>();
        Map<UUID, Integer> nationIndex = new HashMap<>();
        for (Claim claim : nearby) {
            if (nationIndex.containsKey(claim.nationId()) || nationViews.size() >= 256) {
                continue;
            }
            Nation nation = nations.byId(claim.nationId()).orElse(null);
            if (nation == null) {
                continue;
            }
            nationIndex.put(nation.id(), nationViews.size());
            nationViews.add(new NationMapPayload.NationView(
                    nation.id(), nation.name(), nation.color(),
                    claims.chunkCount(nation.id()), nation.capitalX(), nation.capitalZ()));
        }

        List<NationMapPayload.ClaimView> claimViews = new ArrayList<>();
        for (Claim claim : nearby) {
            Integer index = nationIndex.get(claim.nationId());
            if (index == null || claimViews.size() >= maxClaims) {
                continue;
            }
            claimViews.add(new NationMapPayload.ClaimView(index, claim.chunkX(), claim.chunkZ()));
        }

        return new NationMapPayload(nationViews, claimViews, player.blockPosition().getY());
    }

    public static String dimensionId(ServerPlayer player) {
        return player.serverLevel().dimension().location().toString();
    }

    private static boolean isOverworld(String dimensionId) {
        return "minecraft:overworld".equals(dimensionId);
    }

    private static int configInt(ModConfigSpec.IntValue value, int fallback) {
        try {
            Integer result = value.get();
            return result == null ? fallback : result;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean configBoolean(ModConfigSpec.BooleanValue value, boolean fallback) {
        try {
            Boolean result = value.get();
            return result == null ? fallback : result;
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
