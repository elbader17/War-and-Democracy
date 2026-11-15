package com.modserver.data;

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
        writeJson(directory.resolve(NATIONS_FILE), nationsRoot);

        JsonObject claimsRoot = new JsonObject();
        claimsRoot.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonArray claimsArray = new JsonArray();
        for (Claim claim : claims.all()) {
            claimsArray.add(writeClaim(claim));
        }
        claimsRoot.add("claims", claimsArray);
        writeJson(directory.resolve(CLAIMS_FILE), claimsRoot);

        JsonObject disputesRoot = new JsonObject();
        disputesRoot.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonArray disputesArray = new JsonArray();
        for (Dispute dispute : disputes.all()) {
            disputesArray.add(writeDispute(dispute));
        }
        disputesRoot.add("disputes", disputesArray);
        writeJson(directory.resolve(DISPUTES_FILE), disputesRoot);
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

            if (json.has("capital")) {
                JsonObject capital = json.getAsJsonObject("capital");
                nation.setCapital(capital.get("dimension").getAsString(),
                        capital.get("x").getAsInt(), capital.get("z").getAsInt());
            }

            if (json.has("members")) {
                JsonObject members = json.getAsJsonObject("members");
                for (Map.Entry<String, JsonElement> entry : members.entrySet()) {
                    UUID player = UUID.fromString(entry.getKey());
                    NationRole role = parseRole(entry.getValue().getAsString());
                    if (role == NationRole.OWNER) {
                        nation.setOwner(player);
                    } else {
                        nation.setRole(player, role);
                    }
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
        economy.addProperty("defenseDebt", eco.defenseDebt());
        economy.addProperty("defended", eco.isDefended());
        economy.addProperty("garrisonPoints", eco.garrisonPoints());
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

        return json;
    }

    private static void readEconomy(JsonObject json, Nation nation) {
        NationEconomy eco = nation.economy();
        try {
            if (json.has("balance")) {
                eco.setBalance(json.get("balance").getAsLong());
            }
            if (json.has("defenseDebt")) {
                eco.setDefenseDebt(json.get("defenseDebt").getAsLong());
            }
            if (json.has("defended")) {
                eco.setDefended(json.get("defended").getAsBoolean());
            }
            if (json.has("garrisonPoints")) {
                eco.setGarrisonPoints(json.get("garrisonPoints").getAsInt());
            }
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
            return new Claim(dimension, x, z, nation, createdAt);
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
        return json;
    }

    // -------------------------------------------------------------------- util
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
