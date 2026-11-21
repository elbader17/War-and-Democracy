# Plan — Nations and Claims Mod (Minecraft 1.21.1 / NeoForge)

Design document for the mod that lives in `projects/minecraft/modServer`.
Toolchain and codebase are already set up and verified (see `README.md`).

---

## 1. Objective

**Server-side** mod for servers that are "occasionally" played (SMP with claims, in the
Towny/SimpleClaims style) where:

1. Every player can **found a nation** (with name, color, capital and members).
2. Nations **claim chunks** of the world.
3. Claims can be **disputed** between nations/players.
4. All of this **shows up on the minimap** people already use (Xaero, JourneyMap, JEM) and
   also on the mod's **own map**, for those without a minimap installed.

Out of scope for v1: anti-grief protection, economy/empires, missions, diplomacy GUI.

## 2. Research status: minimaps and their APIs

Verified against the official jars and repos (not from memory):

| Mod | Downloads | 1.21.1 NeoForge? | API for markers? | Notes |
|---|---|---|---|---|
| **Xaero's Minimap** | 120.7 M | **Yes** — `neoforge-1.21.1-26.6.0` | **Yes, official**: `ThirdPartyWaypointManager` | Publishes with official Mojang mappings on 1.21.1+ → compiles against Parchment with no problem |
| **Xaero's World Map** | (companion) | **Yes** — `1.41.2` | Shares the minimap's waypoint system | **A single integration covers both** |
| **JourneyMap** | ~30 M | **Yes** — `6.0.0-beta.88+neoforge` | Yes (`jmapi`, waypoints) | Artifact via Modrinth's maven |
| **Just Enough Minimap** | ~10 M | **Pending verification** for NeoForge 1.21.1 | Yes (own waypoint API) | Historically first for Fabric |
| **Open Parties and Claims** | 24 M | **Yes** — `neoforge-1.21.1-0.25.8` | Yes (own claims API) | **Direct competitor**, same author as Xaero |

### 2.1 Competitor: Open Parties and Claims (OPAC)

It is the reference mod of the genre and **already integrates with Xaero's Minimap/World Map**.
What it covers: per-chunk claims (with forceload), *parties*, group-owned claims,
rank-based limits, LuckPerms / FTB Ranks / FTB Teams integration.

**What it does NOT cover, and is our gap:**
- **Nations** (OPAC has *parties*, which are groups, not Wiener Nations with capital/diplomacy).
- **Disputes** between stakeholders over a chunk (OPAC has expiration, not conflict).
- **Nation map** with per-color territory rendering.

This defines the positioning: we do not compete on "claims", we compete on **nations +
disputes + territory map**, and **we rely on the minimap people already use**.

### 2.2 The real technical limit (important for the design)

**No minimap exposes a public API to draw territory polygons.** Xaero, JourneyMap
and JEM only allow *markers* (waypoints). Design consequence:

- Minimap integration = **dynamic waypoints**. Since the claimed chunks would be a
  cloud of points that hide the shape of the land, the minimap only shows **one
  marker per nation** (its capital); the claimed chunks are *not* published there.
- The **actual territory color** can only be painted on the mod's own map, where the
  territory is drawn as a solid area with the border of the shape.
- The territory of a nation is always **contiguous**: a chunk can only be claimed when
  it touches the land the nation already owns (the first chunk must be next to the
  capital). The land of a nation is therefore one shape on the map, not a scatter of
  squares. Configurable with `claimsMustBeContiguous`.

Additionally, in Xaero the waypoint color is a **21-color fixed enum** (not free RGB):
the nation's RGB is mapped to the closest palette color, and the exact color is only
visible on the own map. Another argument for Layer A not being optional.

That is why the plan has **two map layers** (see §5).

Exact technical detail of the Xaero API in
[`docs/API-MINIMAPS.md`](API-MINIMAPS.md) (signatures verified with `javap` over the 26.6.0 jar).

## 3. Domain model

```
Nation
  id            UUID
  name          String (unique, 3-24 chars, [a-zA-Z0-9_])
  owner         UUID
  members       Map<UUID, Role>       // OWNER | OFFICER | MEMBER
  color         int (RGB)             // for minimap and own map
  capital       ChunkPos              // capital chunk
  treasury      long                  // economy (phase 4)
  createdAt     long
  stats         { chunks, kills, wars }

Claim
  dimension     ResourceKey<Level>
  chunkX,chunkZ int                    // chunk key (16x16)
  nationId      UUID
  state         CLAIMED | DISPUTED | FROZEN
  createdAt     long
  key           = (dimension, chunkX, chunkZ)

Dispute
  id            UUID
  claimKey      (dimension, chunkX, chunkZ)
  challenger    UUID (player who disputes)
  defender      UUID (nation owner)
  openedAt      long
  deadline      long                   // end of the defense window
  state         OPEN | DEFENDED | TRANSFERRED | CANCELLED
  reason        String (optional reason)
```

**Game decisions CONFIRMED by the user (basis of Phase 1):**

| Topic | Decision | Implementation |
|---|---|---|
| Who can have a nation | **One nation per player** (each player is the owner of one) | `maxNationsPerPlayer = 1` in config; `NationManager.ownedBy()` |
| Death of the owner | **The nation stays as it is**: it is not dissolved and the owner does not change | there is no death hook; the nation persists as it is |
| Expiration | **Claims never expire** | `Claim` has no expiry date; no upkeep |
| Anti-grief | **Blocks can be broken**: there is no block protection | the mod does not intercept break events; only ownership |
| Granularity | **1 chunk** (16x16) | `Claim` = chunk; key packed into a `long` |
| Dimensions | **Overworld only** | `overworldOnly = true` in config (the model is already multi-dimension) |
| Mod id | **`modserver` is kept** | see note below |
| Relations | **Agreements between nations are allowed** | `NationRelation`: NEUTRAL / PACT / ALLY / WAR (implemented) |

> **Note about the "mod id"**: it is the mod's internal identifier (the one that appears in
> `neoforge.mods.toml`, in the resource namespace `modserver:...`, in the data folder
> `world/modserver/` and in the jar name). Renaming it now is cheap because there is no
> saved data yet; renaming it later would force a migration of the world files. `modserver`
> is kept so it matches the project folder; if `naciones` is wanted later, the change is
> `mod_id` + package + a few references.

**Design decisions (proposed defaults, adjustable):**

| Topic | Default | Reason |
|---|---|---|
| Claim granularity | 1 chunk (16x16) | simple and granular; `/nacion claim radio <n>` adds a 3x3/5x5 region in phase 3 |
| Inheritance on death | **the nation survives**; if the owner dies and there are no officers, it passes to the oldest member; with no members → it is dissolved | avoids losing progress because of a silly death |
| Claim expiration | **they do not expire** in v1 (upkeep configurable in phase 4) | disputes already create tension; expiration due to non-payment is a different thing |
| Dimensions | key per dimension from the model; claim **only in the overworld** in v1 (configurable) | the model does not need to be migrated later |
| Anti-grief | **no** in v1 | that is OPAC/Landlord territory; focus on tenancy + dispute |
| Claim limit | per nation and per player (config, default 100 / 20) | avoids claim inflation |
| Claiming an occupied chunk | **not allowed** without a dispute | simple base flow |

## 4. Persistence

- **Nations + disputes**: NeoForge `SavedData` (`DimensionDataStorage`) →
  `world/data/modserver/nations.dat` and `disputes.dat`. They are saved with the world,
  survive restarts, and admins can edit the JSON by hand.
- **Claims**: in-memory index `Map<ResourceKey<Level>, Map<Long, Claim>>`
  (chunk key packed into a `long`), dumped to a custom `SavedData`
  (`claims.dat`) periodically and on shutdown. Format: compact JSON per nation.
  With 50,000 claims ≈ 2-3 MB: acceptable. If it grows, the next step is SQLite.
- **Migration**: `schemaVersion` field in the SavedData; idempotent migrations.

## 5. The two map layers

### Layer A — The mod's own map (`/nacion mapa`)

Full-screen GUI rendered by us, depending on nothing:
- Rendering of chunks colored per nation (the color comes from the claim).
- Capitals, flags, dispute markers (lightning icon).
- Zoom with the mouse wheel, side panel with the list of nations (name, color, chunk
  count, members).
- It is the one that **really** shows the territory. It is also the one that can be
  validated automatically with a screenshot from the client.

### Layer B — Minimap integration (markers)

Implemented against each mod's API, behind a common interface:

```java
interface MapProvider {                 // client only
    void sync(NationView[] nations, ClaimMarker[] markers);
    boolean available();
}
```

- `XaeroMapProvider` (v1): uses `ThirdPartyWaypointManager` (official, verified API).
  One waypoint per nation (at its capital), with the nation's color and its own icon;
  `setEnabledStateGetter` to instantly hide the markers of the nations that are gone.
  Since it is the same waypoint system, **Xaero's World Map shows them without any change**.
  The color is mapped from the nation's RGB to the 21 colors of the Xaero palette
  (nearest-neighbor); the exact RGB is visible on the own map.
  The claimed chunks are **not** published as markers: the minimap stays clean and
  the shape of the territory is only shown in the map of the mod.
- `JourneyMapProvider` (v2): via its waypoint API.
- `JemProvider` (v2): if NeoForge 1.21.1 support is confirmed.

**Optional dependency, never forced**: `compileOnly` + `ModList.isLoaded()`.
If the player has no minimap, the mod works anyway (Layer A only).

**Server->client synchronization**: custom NeoForge payload
(`CustomPacketPayload` + `StreamCodec`). The server sends only what the player
needs: the claims within their view radius (config, default 256 blocks) and the
summary of nearby nations. With 500 visible claims that is ~15 KB per packet: it is
sent in batches on join and made incremental when things change.

## 6. Disputes: mechanics

Core mechanic (v1), in two phases:

1. **Open**: `/nacion disputar <chunk>` (chunk where the player is).
   - Only if the chunk belongs to another nation and there is no open dispute.
   - The `Dispute` is created with a **10 minute** window (configurable).
   - Notification to the defending nation (and to its owner).
2. **Resolve**:
   - **Defend**: `/nacion defender <chunk>` → the dispute becomes `DEFENDED`, the claim
     is kept. Optional cost (paying a "deposit" in the nation's treasury).
   - **Expire**: if nobody defends before the `deadline` → `TRANSFERRED`, the chunk goes
     to the challenger's nation.
   - **Admin**: `/nacion admin resolve <disputeId> keep|transfer` (permission
     `modserver.nation.admin`).

Optional mechanic (v2): **sacking** — killing a player of the nation inside its own
claim transfers that chunk (Towny/GriefPrevention style), with a cooldown per victim.

Anti-abuse: dispute cooldown per challenger and per claim; you cannot dispute your own
chunk nor one that is already disputed.

## 7. Commands

Root: `/nacion` (alias `/nation`, `/nac`).

| Command | Description |
|---|---|
| `/nacion crear <nombre> | creates the nation (1 per player) |
| `/nacion claim` / `unclaim` | claims/releases the current chunk |
| `/nacion claim radio <n>` | claims an n×n region (phase 3) |
| `/nacion info [nombre]` | nation record sheet |
| `/nacion mapa` | **the mod's map** (Layer A) |
| `/nacion miembros add|remove|rol <jugador>` | member management |
| `/nacion color <rgb>` | nation color |
| `/nacion capital` | sets the capital at the current chunk |
| `/nacion disputa <chunk>` / `defender <chunk>` | open / defend a dispute |
| `/nacion lista` | list of the world's nations |
| `/nacion admin resolve <id> <keep\|transfer>` | manual resolution |

Permissions (NeoForge permissions API): `modserver.nation.create`, `.claim`,
`.dispute`, `.admin`, `.map`.

## 8. Phase plan

| Phase | Content | Verification | Status |
|---|---|---|---|
| **0. Base** | NeoForge project, toolchain, QA loop | `tools/smoke.sh` green | ✅ Done |
| **1. Domain** | `Nation`/`Claim`/`NationRelation`, `NationManager`, `ClaimIndex`, JSON store, `/nacion crear\|claim\|unclaim\|info\|lista\|miembros\|color\|acuerdo` | 8/8 GameTest + bot scenario 8/8 | ✅ **Done** |
| **2. Own map** | GUI `/nacion mapa` with per-color rendering | client screenshot + visual test | ✅ **Done** |
| **3. Xaero** | `XaeroMapProvider` + packet sync | install Xaero in the dev runtime and see the markers in the screenshot | ✅ **Done** |
| **4. Disputes** | opening, defense window, transfer, admin | 10 GameTests of the state machine + E2E with two bots | ✅ **Done** |
| **5. Diplomacy** | war/alliance between nations, `/nacion guerra`, attacks | E2E with two bots | ✅ **Done** |
| **6. Economy** | daily upkeep, taxes, treasury | config + save test | ✅ **Done** |

### Status of Phase 1 (implemented)

- `com.modserver.nation`: `Nation`, `NationRole`, `NationRelation`, `NationManager`
  (the "one nation per player" invariant is guaranteed in the domain, not in the command).
- `com.modserver.claim`: `Claim` (chunk key packed into a `long`), `ClaimIndex`.
- `com.modserver.data.ModServerStore`: JSON in `world/modserver/{nations,claims.json}`,
  atomic write (temp file + `move`), schema version and tolerant loading
  of invalid JSON.
- `com.modserver.NationService`: game rules; `com.modserver.config.ModServerConfig`:
  limits and behavior in `world/config/modserver-server.toml`.
  → Persistence: load on server start, save on stop and autosave every 5 min
  if there are changes.
  → Verification: 8 domain GameTests + bot E2E scenario (create -> claim -> info ->
  lista -> unclaim -> claim) and `tools/smoke.sh` green.

Phases 1 and 3 are the value core; 2 is what makes the mod understandable at a glance.

### Status of Phases 2 and 3 (implemented)

- **Network**: `NationMapPayload` (nations + nearby claims + the player's Y, with a nation
  index so the name/color is not repeated per chunk) and `OpenMapPayload` ("open the
  map"), registered in `ModServerNetworking` with `event.registrar("1").optional()`.
- **Server**: `NationService.buildMapPayload(player)` builds the payload with the
  `mapSyncRadiusChunks` radius (16 by default) and the `mapMaxMarkers` cap; it is sent on
  player join and every 20 ticks if the map is "dirty" (claim, color or agreement changes).
- **Client**: `NationMapScreen` (full screen, territory with the exact RGB color,
  capitals, player marker, nation panel, wheel zoom, drag to move, click on the panel to
  go to the capital).
- **Minimaps**: `XaeroMapProvider` (guard: Xaero classes are not loaded if the mod is not
  installed) + `XaeroBridge` (publishes third-party waypoints, max 300, with the fixed
  Xaero palette color closest to the nation's RGB). The minimap and the World Map
  share the waypoint system, so the markers show up in both.
- **`/nacion mapa`** is a **server-side** command: the client would open its own dispatcher
  and swallow `/nacion` entirely, breaking `crear`, `claim`, etc. The server sends the
  data and the order to open the screen.
- **Verification**: `tools/map_check.sh` creates a territory with the bot (nation + 10 chunks
  by teleporting it over RCON) and captures two screenshots from the real client:
  `tools/logs/nation-map.png` (own map) and `tools/logs/nation-minimap.png` (in-game view
  with the Xaero minimap).

### Status of Phase 4 (implemented)

- **Domain** (`com.modserver.dispute`): `Dispute` (numeric id, dimension, chunk,
  challenger, defender, `openedAt`, `deadline`, state and how it was resolved),
  `DisputeStatus` (`OPEN` -> `DEFENDED` / `TRANSFERRED` / `KEPT` / `CANCELLED`),
  `DisputeIndex` (history + index of the open dispute of each chunk) and `DisputeService`
  (the rules, with no Minecraft dependency: the state machine is fully testable without
  players).
- **Rules**: only a chunk of another nation with no open dispute can be disputed; a pact or
  an alliance prevents it; there is a cooldown per challenging nation and per chunk; if the
  defender releases the chunk, the dispute is cancelled; if the window expires without a
  defense, the chunk automatically changes owner (server tick, once per second).
- **Time**: all deadlines use the overworld `gameTime` (not the server tick),
  so the defense window always measures the same clock that was used when opening the
  dispute.
- **Config** (`world/config/modserver-server.toml`): `disputeWindowMinutes` (10),
  `disputeCooldownMinutes` (30) and `disputeChunkCooldownMinutes` (120).
- **Persistence**: `world/modserver/disputes.json` (same readable schema as the rest).
- **Commands**: `/nacion disputar [x] [z]`, `/nacion defender [x] [z]`,
  `/nacion disputas` and `/nacion admin resolve <id> keep|transfer` (op level 2).
  The defending nation receives a chat notice when a dispute is opened. The administrator
  can re-resolve an already defended dispute (their decision does not bind the
  administration), but not one whose chunk has already changed hands.
- **Verification**: 10 new GameTests (18/18 in total) and `tools/dispute_check.sh`, which
  does E2E with two real bots (Alfa claims, Beta disputes, Alfa defends) and then
  checks via RCON the state of the dispute and the manual transfer.

### Status of Phase 5 (implemented)

The base (`NationRelation` and `/nacion acuerdo`) already existed since Phase 1; Phase 5 gives
it gameplay meaning:

- **Commands**: `/nacion guerra|paz|alianza|pacto <nacion>` (shortcuts of the generic
  command `/nacion acuerdo <nacion> <tipo>`) and `/nacion atacar [x] [z]`, which declares
  war (if it was not already declared) and opens the dispute over that chunk: a single
  order to go from neutrality to conflict.
- **Effect of war on disputes**: shorter defense window
  (`warDisputeWindowMinutes`, 5 min versus the normal 10) and no per-challenger cooldown
  (`warIgnoresDisputeCooldown`). It is the attacker's advantage without letting the
  attacker wipe the map in one blow (the per-chunk cooldown still applies).
- **Notifications**: the affected nation receives a chat notice when war is declared,
  when an agreement is signed and when one of its chunks is attacked.
- **Visibility**: `/nacion info` lists your nation's relations and `/nacion lista`
  marks who is at war with whom.
- **Verification**: `tools/diplomacy_check.sh` (E2E with two bots: the pact blocks the
  dispute, war opens it with a reduced window) and a GameTest of the war effect.

### Status of Phase 6 (implemented)

Currency **owned by the mod** (each nation's treasury; the game's gold plays no role),
in cents so percentages are not truncated.

- **Domain** (`com.modserver.economy`): `NationEconomy` (balance, defense debt,
  protection state, garrison points, investment levels),
  `EconomyRules` (the numbers from the configuration) and `EconomyService` (the daily
  cycle, pure domain, with no Minecraft dependency).
- **Daily cycle** (every 24000 game ticks): upkeep is charged (per chunk and per
  investment), income comes in (per chunk, with the farm bonus), the defense is
  charged and the treasury pays interest.
- **More territory, more coins**: each chunk produces every day.
- **More territory, more expensive to defend**: the defense cost is
  `base + perChunk * chunks + scale * chunks^2 / 100`, with **quadratic** growth:
  an empire becomes unsustainable before it becomes big.
- **If it is not paid**: without upkeep, the nation loses the chunks farthest from its
  capital until it can pay; without defense, it stays **UNPROTECTED** (accumulates debt) and
  **cannot defend its disputes** nor use its garrison.
- **Attack**: opening a dispute costs coins (deposit, not refunded), with a discount
  during war. The same currency is used to defend and to attack.
- **Investments** (chosen here, three, with three levels each): **farms** (income
  x1.25 per level), **garrison** (defends disputes without a connected player; points
  are refilled when paying the defense) and **treasury** (daily interest).
- **Persistence**: the economy lives inside `world/modserver/nations.json`.
- **Verification**: 11 GameTests of the economic cycle (30/30 in total) and
  `tools/economy_check.sh`, which checks with two bots that attacking deducts the
  deposit and that a nation without treasury becomes unprotected and cannot defend.

Two NeoForge constraints that shaped the design (see README section 8):

1. A **common** class cannot reference a client class in a position where the
   verifier has to check assignability (for example
   `Minecraft.setScreen(new NationMapScreen())`): when that class is loaded on the
   dedicated server, the JVM loads `Screen` and NeoForge aborts the startup. That is why all
   client code lives in `client/...` and the common class only invokes it with static calls
   inside handlers that never run on the server.
2. Custom payloads are **optional**: otherwise NeoForge's negotiation kicks out
   any client that does not speak its protocol (the test bot included). And before sending
   an optional payload you have to check `NetworkRegistry.hasChannel(...)`.

## 9. QA with the tooling that already exists

The current loop makes it possible to validate all of this without supervision:

- **GameTest** (`tools/gametest.sh`): pure model tests — claiming an already
  claimed chunk, transferring through an expired dispute, per-player limits, serialization of
  `SavedData`.
- **mineflayer bot** (`tools/bot/run.js`): real E2E — the bot joins, runs
  `/nacion crear Testland`, `/nacion claim`, and it is verified with `/nacion info` + RCON.
  With two bots, the full dispute scenario.
- **RCON** (`tools/console.sh`): server inspection and preparation (grant OP, teleport).
- **Client + screenshot** (`tools/client.sh --auto` + `tools/screenshot.sh`): visual verification
  of the own map and of the markers in Xaero. For this, Xaero is needed in the development
  runtime (`localRuntime`), which is one line in `build.gradle`.
- **Visual verification of the map** (`tools/map_check.sh`): leaves a real territory (bot +
  teleports), opens the client in `runClientMap` mode (it connects by itself, opens the map
  and captures) and in `runClientAuto` mode (captures the in-game view with the minimap). It
  leaves the screenshots in `tools/logs/nation-map.png` and `tools/logs/nation-minimap.png`.

## 10. Dependencies (Gradle)

```groovy
repositories {
    maven { url = 'https://chocolateminecraft.com/maven' }   // Xaero
    maven { url = 'https://api.modrinth.com/maven' }          // JourneyMap, JEM
}

dependencies {
    // Xaero: official third-party waypoints API (verified in the 26.6.0 jar)
    compileOnly "xaero.minimap:xaerominimap-neoforge-${minecraft_version}:${xaero_minimap_version}"
    localRuntime "xaero.minimap:xaerominimap-neoforge-${minecraft_version}:${xaero_minimap_version}"
}
```

In `neoforge.mods.toml`, an **optional** dependency so the mod does not require it:

```toml
[[dependencies.${mod_id}]]
    modId = "xaerominimap"
    type = "optional"
    versionRange = "[24.0,)"
    side = "CLIENT"
```

No dependency on Xaero's World Map is needed: it shares the waypoint system.

## 11. Risks and open decisions

**Decisions already fixed** (see §3): one nation per player, the nation survives death,
claims without expiration, no anti-grief protection, 1 chunk, overworld only, mod id `modserver`,
agreements between nations.

**Pending decisions (they do not block Phase 2/3):**
1. Can claims be **transferred** to another nation (donation)? → *no in v1*.
2. Does **sacking** (killing the owner inside their claim) transfer the chunk? → *proposed in v4*.
3. Can the capital be **moved** with `/nacion capital`? → *yes, in v2*.
4. Will there be **taxes/upkeep**? → *v6*.
5. Mod name shown in `mods.toml` (`displayName`)? → *today "ModServer"*.

**Technical risks**
1. *Waypoints per claim can hurt minimap performance* if there are thousands.
   Mitigation: limited sync radius + marker cap + `setEnabledStateGetter`.
2. *The Xaero API has no stable semantic version*: compiling against the jar means
   coupling to internal classes. Mitigation: encapsulate everything behind
   `MapProvider` and use reflection for the sensitive entry point, so that an
   Xaero update does not break the mod (degrade to Layer A).
3. *Claim scaling*: 50 k+ claims in JSON slow down saving. Mitigation: incremental
   saves / SQLite in phase 6.
4. *Conflict with Open Parties and Claims*: both track chunk ownership, so they
   **must not be installed at the same time** on the same server (contradictory claims).
   Document it clearly in the mod's README and in the server documentation.

**Decisions I need from the user** (I propose a default in case there is no preference):
1. Does the nation **survive the death** of the owner? → *yes, with inheritance to the oldest member*.
2. Do claims **expire** if they are not "maintained"? → *no in v1*.
3. Anti-grief (not breaking blocks in foreign territory)? → *no in v1*.
4. Claim size? → *1 chunk*, with regions in phase 3.
5. Multi-dimension from the start? → *model yes, gameplay overworld-only*.
6. Definitive mod id? → currently `modserver`; renaming to `naciones` means changing
   `mod_id` + package + a couple of strings (affects saved data: a migration is needed).
7. Can claims be transferred to another nation (donation/alliance)? → *no in v1*.
