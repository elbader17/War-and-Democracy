package com.modserver.data;

import com.modserver.military.TroopCounts;
import com.modserver.military.TroopTransfer;
import com.modserver.military.TroopBranch;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.modserver.claim.Claim;
import com.modserver.claim.ClaimIndex;
import com.modserver.dispute.Dispute;
import com.modserver.dispute.DisputeIndex;
import com.modserver.dispute.DisputeStatus;
import com.modserver.economy.NationEconomy;
import com.modserver.nation.Nation;
import com.modserver.nation.NationManager;
import com.modserver.nation.NationRelation;
import com.modserver.nation.NationRole;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JSON persistence inside the world folder: {@code world/modserver/}.
 *
 * <p>Plain JSON was chosen (instead of SavedData) so that a server administrator
 * can read and edit the data by hand; it also makes schema migrations easier.</p>
 */
public final class ModServerStore {

    public static final int SCHEMA_VERSION = 1;

    private static final String NATIONS_FILE = "nations.json";
    private static final String CLAIMS_FILE = "claims.json";
    private static final String DISPUTES_FILE = "disputes.json";
    private static final String PENDING_SAVE = "pending-save.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path directory;

    public ModServerStore(Path directory) {
        this.directory = directory;
    }

    public Path directory() {
        return directory;
    }

    // ------------------------------------------------------------------ load
    public void load(NationManager nations, ClaimIndex claims, DisputeIndex disputes)
            throws IOException {
        Files.createDirectories(directory);

        // Complete an interrupted checkpoint before reading the separate human-editable files.
        JsonObject pending = readJson(directory.resolve(PENDING_SAVE));
        if (pending != null) finishCheckpoint(pending);

        JsonObject nationsRoot = readJson(directory.resolve(NATIONS_FILE));
        if (nationsRoot != null) {
            JsonArray array = nationsRoot.getAsJsonArray("nations");
            if (array != null) {
                for (JsonElement element : array) {
                    Nation nation = readNation(element.getAsJsonObject());
                    if (nation != null) {
                        nations.add(nation);
                    }
                }
            }
        }

        JsonObject claimsRoot = readJson(directory.resolve(CLAIMS_FILE));
        if (claimsRoot != null) {
            JsonArray array = claimsRoot.getAsJsonArray("claims");
            if (array != null) {
                for (JsonElement element : array) {
                    Claim claim = readClaim(element.getAsJsonObject());
                    if (claim != null) {
                        claims.claim(claim);
                    }
                }
            }
        }

        JsonObject disputesRoot = readJson(directory.resolve(DISPUTES_FILE));
        if (disputesRoot != null) {
            JsonArray array = disputesRoot.getAsJsonArray("disputes");
            if (array != null) {
                for (JsonElement element : array) {
                    Dispute dispute = readDispute(element.getAsJsonObject());
                    if (dispute != null) {
                        disputes.add(dispute);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ save
    public void save(NationManager nations, ClaimIndex claims, DisputeIndex disputes)
            throws IOException {
        Files.createDirectories(directory);

        JsonObject nationsRoot = new JsonObject();
        nationsRoot.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonArray nationsArray = new JsonArray();
        for (Nation nation : nations.all()) {
            nationsArray.add(writeNation(nation));
        }
        nationsRoot.add("nations", nationsArray);

        JsonObject claimsRoot = new JsonObject();
        claimsRoot.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonArray claimsArray = new JsonArray();
        for (Claim claim : claims.all()) {
            claimsArray.add(writeClaim(claim));
        }
        claimsRoot.add("claims", claimsArray);

        JsonObject disputesRoot = new JsonObject();
        disputesRoot.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonArray disputesArray = new JsonArray();
        for (Dispute dispute : disputes.all()) {
            disputesArray.add(writeDispute(dispute));
        }
        disputesRoot.add("disputes", disputesArray);
        JsonObject checkpoint = new JsonObject();
        checkpoint.add(NATIONS_FILE, nationsRoot);
        checkpoint.add(CLAIMS_FILE, claimsRoot);
        checkpoint.add(DISPUTES_FILE, disputesRoot);
        // Field armies and the garrisons they left must always come from the same save.
        writeJson(directory.resolve(PENDING_SAVE), checkpoint);
        finishCheckpoint(checkpoint);
    }

    private void finishCheckpoint(JsonObject checkpoint) throws IOException {
        for (String name : List.of(NATIONS_FILE, CLAIMS_FILE, DISPUTES_FILE)) {
            writeJson(directory.resolve(name), checkpoint.getAsJsonObject(name));
        }
        Files.delete(directory.resolve(PENDING_SAVE));
    }

    // ------------------------------------------------------------------ nations
    private static Nation readNation(JsonObject json) {
        try {
            UUID id = UUID.fromString(json.get("id").getAsString());
            String name = json.get("name").getAsString();
            UUID owner = UUID.fromString(json.get("owner").getAsString());
            int color = json.has("color") ? json.get("color").getAsInt() : 0xFFFFFF;
            long createdAt = json.has("createdAt") ? json.get("createdAt").getAsLong() : 0L;

            Nation nation = new Nation(id, name, owner, color, createdAt);

            if (json.has("preamble")) nation.setPreamble(json.get("preamble").getAsString());
            if (json.has("doctrines")) nation.setDoctrines(json.get("doctrines").getAsString());
            if (json.has("capital")) {
                JsonObject capital = json.getAsJsonObject("capital");
                nation.setCapital(capital.get("dimension").getAsString(),
                        capital.get("x").getAsInt(), capital.get("z").getAsInt());
            }

            if (json.has("treasuryChest")) {
                String dimension = json.has("treasuryDimension") ? json.get("treasuryDimension").getAsString() : nation.capitalDimension();
                if (dimension != null) nation.setTreasuryChest(dimension, json.get("treasuryChest").getAsLong());
            }
            if (json.has("members")) {
                JsonObject members = json.getAsJsonObject("members");
                for (Map.Entry<String, JsonElement> entry : members.entrySet()) {
                    UUID player = UUID.fromString(entry.getKey());
                    NationRole role = parseRole(entry.getValue().getAsString());
                    // The top-level owner is authoritative for older saves with stale roles.
                    nation.setRole(player, role == NationRole.OWNER ? NationRole.MEMBER : role);
                }
            }

            if (json.has("economy")) {
                readEconomy(json.getAsJsonObject("economy"), nation);
            }

            if (json.has("relations")) {
                JsonObject relations = json.getAsJsonObject("relations");
                for (Map.Entry<String, JsonElement> entry : relations.entrySet()) {
                    nation.setRelation(UUID.fromString(entry.getKey()),
                            NationRelation.parse(entry.getValue().getAsString(), NationRelation.NEUTRAL));
                }
            }

            nation.setEnvoy(json.has("envoy") && json.get("envoy").getAsBoolean());
            if (json.has("council")) for (var row : json.getAsJsonArray("council")) {
                try {
                    nation.file(GSON.fromJson(row, com.modserver.council.CouncilPost.class));
                } catch (RuntimeException e) {
                    System.err.println("Invalid council dispatch for " + id + ": " + e.getMessage());
                }
            }
            if (json.has("transfers")) for (var entry : json.getAsJsonArray("transfers")) {
                try {
                    var row = entry.getAsJsonObject();
                    nation.addTransfer(new TroopTransfer(UUID.fromString(row.get("recipient").getAsString()),
                            row.get("dimension").getAsString(), row.get("fromX").getAsInt(), row.get("fromZ").getAsInt(),
                            row.get("toX").getAsInt(), row.get("toZ").getAsInt(), readForces(row, "armies", 0),
                            row.get("dueAt").getAsLong(), TroopTransfer.Leg.valueOf(row.get("leg").getAsString()),
                            row.has("secondsPerChunk") ? row.get("secondsPerChunk").getAsInt() : 5));
                } catch (RuntimeException e) {
                    System.err.println("[modserver] Invalid shipment for " + id + ": " + e.getMessage());
                }
            }
            return nation;
        } catch (RuntimeException e) {
            System.err.println("[modserver] Nation ignored due to invalid JSON: " + e.getMessage());
            return null;
        }
    }

    private static JsonObject writeNation(Nation nation) {
        JsonObject json = new JsonObject();
        json.addProperty("id", nation.id().toString());
        json.addProperty("name", nation.name());
        json.addProperty("owner", nation.owner().toString());
        json.addProperty("color", nation.color());
        json.addProperty("createdAt", nation.createdAt());
        json.addProperty("preamble", nation.preamble());
        json.addProperty("doctrines", nation.doctrines());
        if (nation.treasuryChest() != null) {
            json.addProperty("treasuryChest", nation.treasuryChest());
            json.addProperty("treasuryDimension", nation.treasuryDimension());
        }

        if (nation.hasCapital()) {
            JsonObject capital = new JsonObject();
            capital.addProperty("dimension", nation.capitalDimension());
            capital.addProperty("x", nation.capitalX());
            capital.addProperty("z", nation.capitalZ());
            json.add("capital", capital);
        }

        JsonObject members = new JsonObject();
        for (Map.Entry<UUID, NationRole> entry : nation.members().entrySet()) {
            members.addProperty(entry.getKey().toString(), entry.getValue().name());
        }
        json.add("members", members);

        JsonObject economy = new JsonObject();
        NationEconomy eco = nation.economy();
        economy.addProperty("balance", eco.balance());
        writeForces(economy, "garrisonPoints", eco.reserveForces());
        economy.addProperty("farmLevel", eco.farmLevel());
        economy.addProperty("garrisonLevel", eco.garrisonLevel());
        economy.addProperty("treasuryLevel", eco.treasuryLevel());
        economy.addProperty("lastDay", eco.lastDay());
        json.add("economy", economy);

        JsonObject relations = new JsonObject();
        for (Map.Entry<UUID, NationRelation> entry : nation.relations().entrySet()) {
            relations.addProperty(entry.getKey().toString(), entry.getValue().name());
        }
        json.add("relations", relations);
        if (nation.isEnvoy()) json.addProperty("envoy", true);
        if (!nation.register().isEmpty()) json.add("council", GSON.toJsonTree(nation.register()));
        var transfers = new JsonArray();
        for (var trip : nation.transfers()) {
            var row = new JsonObject();
            row.addProperty("recipient", trip.recipient().toString());
            row.addProperty("dimension", trip.dimension());
            row.addProperty("fromX", trip.fromX());
            row.addProperty("fromZ", trip.fromZ());
            row.addProperty("toX", trip.toX());
            row.addProperty("toZ", trip.toZ());
            row.addProperty("dueAt", trip.dueAt());
            row.addProperty("secondsPerChunk", trip.secondsPerChunk());
            row.addProperty("leg", trip.leg().name());
            writeForces(row, "armies", trip.troops());
            transfers.add(row);
        }
        json.add("transfers", transfers);

        return json;
    }

    private static void readEconomy(JsonObject json, Nation nation) {
        NationEconomy eco = nation.economy();
        try {
            if (json.has("balance")) {
                eco.setBalance(json.get("balance").getAsLong());
            }
            eco.setReserveForces(readForces(json, "garrisonPoints", 0));
            if (json.has("farmLevel")) {
                eco.setFarmLevel(json.get("farmLevel").getAsInt());
            }
            if (json.has("garrisonLevel")) {
                eco.setGarrisonLevel(json.get("garrisonLevel").getAsInt());
            }
            if (json.has("treasuryLevel")) {
                eco.setTreasuryLevel(json.get("treasuryLevel").getAsInt());
            }
            if (json.has("lastDay")) {
                eco.setLastDay(json.get("lastDay").getAsLong());
            }
        } catch (RuntimeException e) {
            System.err.println("[modserver] Nation economy ignored due to invalid JSON: "
                    + e.getMessage());
        }
    }

    private static NationRole parseRole(String name) {
        for (NationRole role : NationRole.values()) {
            if (role.name().equalsIgnoreCase(name)) {
                return role;
            }
        }
        return NationRole.MEMBER;
    }

    // ------------------------------------------------------------------- claims
    private static Claim readClaim(JsonObject json) {
        try {
            String dimension = json.get("dimension").getAsString();
            int x = json.get("x").getAsInt();
            int z = json.get("z").getAsInt();
            UUID nation = UUID.fromString(json.get("nation").getAsString());
            long createdAt = json.has("createdAt") ? json.get("createdAt").getAsLong() : 0L;
            Claim claim = new Claim(dimension, x, z, nation, createdAt);
            claim.setForces(readForces(json, "armies", 1));
            if (json.has("nuclearMissiles")) claim.setNuclearMissiles(Math.max(0, json.get("nuclearMissiles").getAsInt()));
            if (json.has("missiles")) claim.setMissiles(Math.max(0, json.get("missiles").getAsInt()));
            return claim;
        } catch (RuntimeException e) {
            System.err.println("[modserver] Claim ignored due to invalid JSON: " + e.getMessage());
            return null;
        }
    }

    private static JsonObject writeClaim(Claim claim) {
        JsonObject json = new JsonObject();
        json.addProperty("dimension", claim.dimension());
        json.addProperty("x", claim.chunkX());
        json.addProperty("z", claim.chunkZ());
        json.addProperty("nation", claim.nationId().toString());
        writeForces(json, "armies", claim.forces());
        json.addProperty("missiles", claim.missiles());
        json.addProperty("nuclearMissiles", claim.nuclearMissiles());
        json.addProperty("createdAt", claim.createdAt());
        return json;
    }

    // ------------------------------------------------------------------- disputes
    private static Dispute readDispute(JsonObject json) {
        try {
            long id = json.get("id").getAsLong();
            String dimension = json.get("dimension").getAsString();
            int x = json.get("x").getAsInt();
            int z = json.get("z").getAsInt();
            UUID challenger = UUID.fromString(json.get("challenger").getAsString());
            UUID defender = UUID.fromString(json.get("defender").getAsString());
            long openedAt = json.has("openedAt") ? json.get("openedAt").getAsLong() : 0L;
            long deadline = json.has("deadline") ? json.get("deadline").getAsLong() : 0L;

            Dispute dispute = new Dispute(id, dimension, x, z, challenger, defender, openedAt, deadline);
            if (json.has("status")) {
                try {
                    dispute.setStatus(DisputeStatus.valueOf(json.get("status").getAsString()));
                } catch (IllegalArgumentException e) {
                    dispute.setStatus(DisputeStatus.OPEN);
                }
            }
            if (json.has("resolvedAt")) {
                dispute.setResolvedAt(json.get("resolvedAt").getAsLong());
            }
            if (json.has("resolvedBy")) {
                dispute.setResolvedBy(json.get("resolvedBy").getAsString());
            }
            if (dispute.status().isOpen() && json.has("assault")) {
                JsonObject force = json.getAsJsonObject("assault");
                var troops = readForces(force, "attackers", 0);
                if (troops.total() > 0) dispute.setAssault(new Dispute.Assault(force.get("originX").getAsInt(),
                        force.get("originZ").getAsInt(), troops, force.get("nextDuel").getAsLong()));
            }
            return dispute;
        } catch (RuntimeException e) {
            System.err.println("[modserver] Dispute ignored due to invalid JSON: " + e.getMessage());
            return null;
        }
    }

    private static JsonObject writeDispute(Dispute dispute) {
        JsonObject json = new JsonObject();
        json.addProperty("id", dispute.id());
        json.addProperty("dimension", dispute.dimension());
        json.addProperty("x", dispute.chunkX());
        json.addProperty("z", dispute.chunkZ());
        json.addProperty("challenger", dispute.challengerNationId().toString());
        json.addProperty("defender", dispute.defenderNationId().toString());
        json.addProperty("openedAt", dispute.openedAt());
        json.addProperty("deadline", dispute.deadline());
        json.addProperty("status", dispute.status().name());
        json.addProperty("resolvedAt", dispute.resolvedAt());
        json.addProperty("resolvedBy", dispute.resolvedBy());
        if (dispute.assault() != null) {
            var force = dispute.assault();
            JsonObject order = new JsonObject();
            order.addProperty("originX", force.originX());
            order.addProperty("originZ", force.originZ());
            writeForces(order, "attackers", force.forces());
            order.addProperty("nextDuel", force.nextDuel());
            json.add("assault", order);
        }
        return json;
    }

    // -------------------------------------------------------------------- util
    private static TroopCounts readForces(JsonObject json, String legacyKey, int fallback) {
        if (!json.has("troops")) return new TroopCounts(json.has(legacyKey) ? Math.max(0, json.get(legacyKey).getAsInt()) : fallback, 0, 0);
        var troops = json.getAsJsonObject("troops");
        return new TroopCounts(troops.get("ground").getAsInt(), troops.get("air").getAsInt(), troops.get("naval").getAsInt());
    }

    private static void writeForces(JsonObject json, String totalKey, TroopCounts forces) {
        json.addProperty(totalKey, forces.total());
        var troops = new JsonObject();
        for (var branch : TroopBranch.ALL) troops.addProperty(branch.id, forces.count(branch));
        json.add("troops", troops);
    }

    private static JsonObject readJson(Path path) throws IOException {
        if (!Files.exists(path)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            throw new IOException("Could not read " + path.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static void writeJson(Path path, JsonObject json) throws IOException {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            GSON.toJson(json, writer);
        }
        // Atomic write: avoids corrupted files if the server is stopped halfway.
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
    }

    /** List of claims of a nation, used by commands and tests. */
    public static List<Claim> claimsOf(ClaimIndex index, UUID nationId) {
        return new ArrayList<>(index.claimsOf(nationId));
    }
}
