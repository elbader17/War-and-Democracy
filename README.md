# War & Democracy

## Getting started

Use Java 21 and NeoForge **21.1.266** on a Minecraft Java **1.21.1** server. Run
`tools/build.sh` with `JAVA_HOME` pointing to JDK 21, copy
`build/libs/modserver-0.1.0.jar` into the server's `mods/` directory, and start
the server. Players can join with vanilla Minecraft Java 1.21.1.

1. Run `/nation create Aurora` in the Overworld to found a nation at your current
   location. Each nation has one owner, and each player can found one nation.
2. Run `/nation claim` to claim the 16 × 16 block chunk you are standing in.
   Move into an adjacent chunk to expand. Press **F3 + G** to see chunk boundaries.
3. In the demo, press **M** to open JourneyMap's overhead terrain map. Gold lines
   separate neighboring nations. Pale lines border unclaimed land. A **pale X on
   red** marks a disputed chunk. Right-click a chunk and choose **Inspect / dispute
   territory** to see its owner, the participants in an active dispute, and the
   remaining defense time. On an eligible rival chunk, review the price and
   defense window and press **Dispute** to spend the coins. Install the mod and
   JourneyMap on your NeoForge client for this view. The political map opens
   with `/nation map`. On a vanilla client it appears in chat as a north-up grid.
   Hover over a cell to see its coordinates and owner, or click it to inspect it.
   The ▣ symbol marks an international border.
4. Run `/nation economy` to inspect your treasury. Nations start with **100
   coins**. With the default settings, three chunks generate 36 coins per game
   day and yield **6.55 coins** after upkeep and defense. The sidebar shows your
   balance, daily income and expenses, territory, capital, protection, and any
   defense debt. It updates on both vanilla and modded clients. An existing
   server scoreboard takes priority while it uses the sidebar.
5. Use `/nation dispute X Z` to challenge another nation's chunk. A neutral
   dispute costs **50 coins** and gives the owner **10 minutes** to defend.
   `/nation attack X Z` declares war and opens a dispute for **25 coins** with a
   **5-minute** defense window. X and Z are chunk coordinates. The owner can
   defend with `/nation defend X Z` and pay any defense debt with `/nation defense pay`.

The sample cog is available for existing worlds. Servers where every player has
the mod installed can enable its recipe with `/datapack enable "mod/modserver:cog_demo"`.

### Challenge a rival NPC

In the demo, right-click the **Envoy of Baluarte** by the border road and press
**T** to choose a challenge in chat. **Attack Baluarte** spends your nation's
coins. **Accept an attack** lets Baluarte pay to challenge one of your chunks.
Both options show the chunk coordinates before you commit.

The bar at the top shows your role, your opponent, the chunk, and the remaining
time. If several disputes are active, it shows the one ending first. Use
`/nation disputes` to list them all. When time expires, the attacker takes the
chunk unless the defender holds it. Use `/nation defend X Z` in the chunk's
dimension while your protection is active. A protected garrison can spend one
point at the deadline to hold the chunk automatically. Both nations receive the
result in chat. Diplomatic wars continue between disputes. Use
`/nation peace Baluarte` to return to neutrality. Open disputes keep their deadlines.

To add an envoy on another server, an operator can stand in an unclaimed chunk
and run `/nation admin rival Baluarte`. This creates a persistent envoy and founds
a nation with up to three adjacent chunks. The rival participates in the same
economy as player nations.

**Nations, disputes, diplomacy and economy for Minecraft 1.21.1 (NeoForge).**

A server-side mod for SMP servers that want the classic "land claiming" gameplay
with a political layer on top: every player founds a **nation**, the land is
**claimed chunk by chunk** (and always as one contiguous shape), the neighbours can
**dispute** it, the nations sign **pacts, alliances or wars** between them, and
every piece of land has a price in the **economy of the mod** (bigger territory =
more income, but also a more expensive defense).

Built as a **development base for Minecraft 1.21.1 with NeoForge** mods, so that
an agent can iterate against the real game without human supervision.

| Piece | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.266 |
| ModDevGradle | 2.0.91 |
| Gradle | 8.14.2 |
| Java | Temurin 21.0.13 (LTS) |
| Mod | `modserver` (package `com.modserver`) |

**Verified state:** the mod compiles, passes **31/31 GameTests** inside the game
(nation model, claims and contiguity, disputes, diplomacy and economy), the bot
joins the world as a real player (even as a *vanilla* client), the GUI client
connects to the server on its own, **the nation map paints the territory with the
exact RGB colour and draws the border of the shape**, and **JourneyMap shows those
borders on the demo’s minimap**. Reference
screenshots: `tools/logs/demo-2-nationmap.png` (own map),
`tools/logs/demo-1-minimap.png` (in-game view with the minimap),
`tools/logs/journeymap-1-territory.png` (JourneyMap) and
`tools/logs/client-ingame.png` (game running with the mod block).

---

## 1. Layout

```
projects/minecraft/
├── modServer/                  <- Gradle project (source of truth)
│   ├── build.gradle            <- ModDevGradle: runs client/clientAuto/clientMap/clientMinimap/server/gameTestServer/data
│   ├── gradle.properties       <- MC/NeoForge and mod versions
│   ├── gradlew / gradlew.bat
│   ├── src/main/java/com/modserver/
│   │   ├── ModServer.java              <- main @Mod class
│   │   ├── NationService.java          <- game rules (nations, claims, disputes, economy)
│   │   ├── block/ModServerBlocks.java   <- sample "cog" block
│   │   ├── item/ModServerItems.java     <- items + creative tab
│   │   ├── command/ModServerCommands.java   <- /modserver info|selftest|spawncog
│   │   ├── command/NationCommands.java      <- /nation create|claim|unclaim|info|list|members|color|agreement|map|dispute|defend|disputes|economy|defense|attack|war|peace|alliance|pact|admin
│   │   ├── nation/                   <- Nation, NationRole, NationRelation, NationManager (domain)
│   │   ├── claim/                    <- Claim, ClaimIndex (territory)
│   │   ├── dispute/                  <- Dispute, DisputeStatus, DisputeIndex, DisputeService (disputes)
│   │   ├── economy/                  <- NationEconomy, EconomyRules, EconomyService, Investment
│   │   ├── data/ModServerStore.java  <- JSON persistence in world/modserver
│   │   ├── data/ModServerDataGen.java   <- datagen (recipe + advancement)
│   │   ├── network/                  <- map payloads + registration (common code)
│   │   ├── client/ModServerClientCommands.java  <- /modserver screenshot (client only)
│   │   ├── client/ModServerKeyBindings.java     <- key bindings (M = nation map)
│   │   ├── client/map/                <- own map (Layer A) + Xaero and JourneyMap bridges (Layer B)
│   │   ├── client/network/ModServerClientNetworking.java  <- client handlers
│   │   └── gametest/                  <- ModServerGameTests (9), ModServerDisputeGameTests (11),
│   │                                   ModServerEconomyGameTests (11)
│   ├── src/main/resources/assets/modserver/     (lang, icon.png, block model and texture)
│   ├── src/main/resources/data/modserver/structure/empty.nbt  <- GameTest template
│   ├── src/main/templates/META-INF/neoforge.mods.toml
│   ├── src/generated/resources/        <- runData output
│   └── tools/                  <- automation (see below)
│       ├── env.sh build.sh server.sh console.sh client.sh
│       ├── gametest.sh smoke.sh screenshot.sh map_check.sh
│       ├── dispute_check.sh diplomacy_check.sh economy_check.sh
│       ├── rcon.py             <- RCON client (no dependencies)
│       ├── png_analyze.py      <- PNG screenshot analyser (pixel colour histogram)
│       ├── make_structure.py   <- NBT template generator for GameTest
│       ├── make_texture.py     <- PNG texture generator (no PIL)
│       └── bot/                <- node + mineflayer + scenarios
└── .tools/                     <- marker file for the project root
```

## 2. Toolchain and how to build it

The project is **cross platform**: Java 21 + Gradle + NeoForge, with no Windows
specific code. It was developed under WSL2 (the scripts are plain `bash`) and runs
natively on Linux, macOS and Windows (with WSL or Git Bash for the scripts).

| Tool | Path |
|---|---|
| JDK 21 | `~/.mctools/jdk21` (or any JDK 21 in `JAVA_HOME`) |
| Node 22 | `~/.mctools/node` (only for the test bots) |
| Gradle cache | `~/.gradle` (keep it on a native filesystem, not on a Windows drive) |
| Python 3 | only for `tools/png_analyze.py` and the texture/structure generators |

`tools/env.sh` exports them (it computes every path from its own location, so the
project can live anywhere). Every script sources `tools/env.sh` on its own.

On a native Linux machine you can re-enable the Gradle file watching (it is only
disabled in `gradle.properties` because WSL2's `drvfs` does not report file
changes reliably):

```properties
org.gradle.vfs.watch=true
```

First run on a new machine:

```bash
source tools/env.sh
./tools/build.sh          # ./gradlew build -> jar in build/libs/
./tools/gametest.sh       # 31 GameTests inside the game
```

## 3. Commands

```bash
cd projects/minecraft/modServer

tools/build.sh                 # ./gradlew build  -> jar in build/libs/
tools/server.sh start          # headless dev server (port 25565)
tools/server.sh wait 240       # waits until the world is ready
tools/console.sh "say hello"   # remote command (RCON, port 25575)
tools/server.sh logs 100       # last log lines
tools/server.sh stop           # clean shutdown (the server saves the world)

tools/client.sh                # dev client with GUI (WSLg)
tools/client.sh --auto         # client that joins the server by itself and takes a screenshot
tools/gametest.sh              # headless GameTest inside the game
tools/screenshot.sh            # copies the game screenshot -> tools/logs/screen.png
tools/map_check.sh             # visual check: the bot creates territory and the client
                               # captures the own map (tools/logs/nation-map.png) and the
                               # view with the JourneyMap minimap (tools/logs/nation-minimap.png)
tools/dispute_check.sh         # E2E check of the disputes with two real bots
                               # (open, defend and transfer a chunk)
tools/diplomacy_check.sh       # E2E check of the diplomacy with two real bots
                               # (a pact blocks disputes; war opens them)
tools/economy_check.sh         # E2E check of the economy with two real bots
                               # (earn, spend on attacking and become unprotected)

tools/smoke.sh                 # FULL LOOP: build -> gametest -> server -> bot -> asserts -> stop
```

## 4. The three control channels

The project lets you drive the game through three independent channels:

1. **RCON** (`tools/console.sh`) -> full control of the server console, remote and
   without depending on stdin (Gradle does not forward it to the game process).
2. **Mineflayer bot** (`tools/bot/run.js`) -> a real player joins the world, runs commands
   and can inspect the state (position, health, nearby blocks, inventory).
3. **GameTest** (`tools/gametest.sh`) -> automated tests that run inside the game itself,
   with no players and no interaction.

And a fourth channel, for the visual side:

4. **GUI client + in-game capture** (`tools/client.sh --auto`) -> the client starts with a
   real window under WSLg, joins the server on its own and saves a capture of the game
   framebuffer in `run/screenshots/modserver.png`.

### Bot scenarios

```bash
node tools/bot/run.js --scenario tools/bot/scenarios/smoke.json   # verifies the mod
node tools/bot/run.js --probe                                     # dumps the world state
```

A scenario is a JSON list of steps:

```json
[
  { "command": "/modserver selftest", "expect": ["PASS"], "timeout": 20000 },
  { "chat": "hello", "wait": 500 },
  { "wait": 1000 }
]
```

A step with `expect` fails if the game answer does not contain the expected text, and the
runner returns a non-zero exit code so an agent can decide on its own.

## 5. Mod commands

| Command | Side | What it does |
|---|---|---|
| `/modserver info` | server | Minecraft, NeoForge and mod versions |
| `/modserver selftest` | server | battery of checks; writes `MODSERVER_SELFTEST RESULT n/n PASS` in the server log and answers in chat |
| `/modserver spawncog` | server | places a copper test marker, or the sample cog when its data pack is enabled |
| `/modserver screenshot` | client | saves the game framebuffer in `<gameDir>/screenshots/modserver.png` |

### Nation commands (`/nation`, legacy roots `/nacion` and `/nac`)

| Command | What it does |
|---|---|
| `/nation create <name> [colour]` | founds a nation (one per player); the capital is the chunk you are on |
| `/nation claim` | claims the chunk you are on (it must touch your territory) |
| `/nation unclaim` | releases the chunk you are on |
| `/nation info` | your nation card (chunks, members, colour, capital, relations) |
| `/nation list` | lists the nations of the world |
| `/nation members add\|remove <player>` | member management |
| `/nation color <0-16777215>` | nation RGB colour |
| `/nation agreement <nation> <pact\|ally\|war\|neutral>` | signs an agreement between nations |
| `/nation map` | opens the graphical nation map on a modded client or a local territory grid in vanilla chat |
| `/nation dispute [x] [z]` | opens a dispute over a chunk (yours by default) |
| `/nation defend [x] [z]` | the owning nation answers the dispute: the chunk is kept |
| `/nation disputes` | lists the disputes (state, chunk, challenger and defender) |
| `/nation war <nation>` | declares war (it changes how disputes work) |
| `/nation peace <nation>` | back to neutrality |
| `/nation alliance <nation>` | signs an alliance (disputes are blocked) |
| `/nation pact <nation>` | signs a non-aggression pact (disputes are blocked) |
| `/nation attack [x] [z]` | declares war (if needed) and opens a dispute over that chunk |
| `/nation economy` | treasury state: balance, income, upkeep, defense and investments |
| `/nation economy invest <farm\|garrison\|treasury>` | invests coins in an upgrade |
| `/nation defense` | today's defense cost and whether the nation is protected |
| `/nation defense pay` | pays for defense (protects the territory again) |
| `/nation admin resolve <id> keep\|transfer` | manual resolution (op level 2) |
| `/nation admin economy tick\|balance <nation> <coins>` | forces the economy day or sets a balance |

The data is stored in **`world/modserver/nations.json`**, **`claims.json`** and
**`disputes.json`**. Server settings are in `config/modserver-server.toml`. A world can
override them in its own `serverconfig/modserver-server.toml`.

Fixed game rules: one nation per player, the nation survives the death of its owner,
claims do not expire, 1 chunk per claim, overworld only and **blocks can be broken**
(there is no anti-grief protection).

**The territory is always contiguous**: a chunk can only be claimed when it touches
the land the nation already owns (the eight neighbouring chunks count, so the corners
of a block of land also touch). The first chunk of a nation must be next to its
capital. This way every nation is a single shape on the map and never a cloud of
isolated squares. It can be turned off with `claimsMustBeContiguous = false` in
`config/modserver-server.toml`.

**Map layers**:
- **The political map** (`/nation map`) paints the territory of every nation
  as a solid area with its exact colour and draws the border of the shape.
- **JourneyMap** supplies the demo's single minimap. It draws national territories
  and borders during gameplay and on its full screen terrain map (`M` or `J`).
- **Xaero's Minimap** shows capital markers for players who install it separately.
  See **[docs/MAPS.md](docs/MAPS.md)**.

The client can also be automated with system properties:

| Property | Effect |
|---|---|
| `modserver.autoJoin=host:port` | joins that server on startup |
| `modserver.autoScreenshot=true` | takes a screenshot when joining the world |
| `modserver.autoScreenshotDelay=60` | ticks to wait before the capture |
| `modserver.autoOpenMap=true` | opens the map screen when joining the world |
| `modserver.autoOpenMapDelay=40` | ticks to wait before opening the map |
| `modserver.autoOpenJourneyMap=true` | opens the full screen map of JourneyMap |
| `modserver.autoCommands=/a;/b` | runs commands 20 ticks after joining the world |

The properties are set in `build.gradle`, in the run configurations `clientAuto`
(capture), `clientMap` (capture + map opened), `clientMinimap` (capture without
opening the map, to see the borders in the JourneyMap minimap) and `clientJourneyMap`
(capture of the full screen map of JourneyMap with the territories), which are
launched with `./gradlew runClientAuto`, `./gradlew runClientMap`,
`./gradlew runClientMinimap` and `./gradlew runClientJourneyMap`.

### Disputes (Phase 4)

The full cycle is:

1. A nation opens a dispute over another nation's chunk: `/nation dispute` (or
   `/nation dispute <x> <z>`). The defending nation gets a chat warning.
2. The defender answers in time: `/nation defend` -> the chunk is kept.
3. If nobody defends before the window expires, **the chunk goes to the challenging
   nation** (the server checks it once per second).
4. An administrator can resolve it by hand: `/nation admin resolve <id> keep|transfer`
   (it can also re-resolve an already defended dispute: a player's decision does not bind
   the server administration).

The defense window and the anti-abuse cooldowns live in
`config/modserver-server.toml`:

| Option | Default | Purpose |
|---|---|---|
| `disputeWindowMinutes` | 10 | minutes to defend the chunk |
| `disputeCooldownMinutes` | 30 | wait for a nation before disputing again |
| `disputeChunkCooldownMinutes` | 120 | wait for a chunk before it can be disputed again |

A **pact** or an **alliance** between the two nations blocks disputes; war or neutrality
allow them. The end-to-end check is `tools/dispute_check.sh` (two real bots + RCON
assertions).

### Diplomacy (Phase 5)

The relation between two nations is changed with `/nation war|peace|alliance|pact
<nation>` (or with the generic `/nation agreement <nation> <type>`). Relations are
bilateral and stored with the nation.

**War has real effects** on disputes:

| Situation | Defense window | Cooldown per attacker |
|---|---|---|
| Neutrality or broken pact | `disputeWindowMinutes` (10 min) | `disputeCooldownMinutes` (30 min) |
| War | `warDisputeWindowMinutes` (5 min) | none (`warIgnoresDisputeCooldown`) |

`/nation attack <x> <z>` is the invasion shortcut: it declares war (if needed) and opens
the dispute over that chunk. `/nation info` and `/nation list` show the relations and who
is at war with whom. The end-to-end check is `tools/diplomacy_check.sh`.

### Economy (Phase 6)

Every nation has its **own currency** (the mod treasury, with 2 decimals; vanilla gold is
not involved). The cycle is daily: when the game day closes (24000 ticks) the upkeep is
charged, the income comes in and the defense is paid.

| Concept | Rule |
|---|---|
| **Income** | every claimed chunk generates `claimDailyIncome` per day -> **more territory, more coins** |
| **Upkeep** | `claimDailyUpkeep` per chunk plus the upkeep of the investments |
| **Defense** | `defenseBaseCost` + `defensePerChunkCost` x chunks + `defenseScaleCost` x chunks^2/100 -> **a big territory costs much more to defend** (the growth is more than linear) |
| **Non-payment** | if the upkeep cannot be paid, the nation **loses the chunks farthest from its capital**; if the defense cannot be paid, it is left **UNPROTECTED** |
| **Unprotected** | an unprotected nation **cannot defend its disputes** and its garrison does not act, until it pays (`/nation defense pay`) |
| **Attack** | opening a dispute costs `attackCost` (a deposit, never refunded); at war it costs half (`warAttackDiscountPercent`) |

**What you invest in** (three investments, three levels each):

| Investment | Effect | Purchase cost |
|---|---|---|
| **Farms** | +25% income per chunk and per level | `farmCost` |
| **Garrison** | one point per level: it defends disputes **without any player connected** (the points are refilled when the defense is paid) | `garrisonCost` |
| **Treasury** | +1% daily interest on the balance per level | `treasuryCost` |

Commands: `/nation economy` (state), `/nation economy invest
<farm|garrison|treasury>`, `/nation defense` and `/nation defense pay`. For the
server administrator: `/nation admin economy tick` (forces the day close) and
`/nation admin economy balance <nation> <coins>`. End-to-end check:
`tools/economy_check.sh`.

## 6. Adding content

- **Block/item/tab**: `src/main/java/com/modserver/block|item/` and register it in
  `ModServer.java` (`modBus.addListener(...)`).
- **Textures/models**: `assets/modserver/...` (generate the PNGs with `tools/make_texture.py`).
- **Command**: add a `then(...)` in `ModServerCommands.register`.
- **Datagen**: `./gradlew runData` -> `src/generated/resources`.
- **Automated test**: a `@GameTest` method in `ModServerGameTests`. If you use a new
  template, generate it with `tools/make_structure.py` (MC 1.21.1 does not ship GameTest
  templates) and put it in `src/main/resources/data/<modid>/structure/`.

## 7. Roadmap of the nations mod

The full design (domain, persistence, disputes, own map and minimap integration) is in
**[docs/PLAN.md](docs/PLAN.md)**, with the verified reference of the minimap APIs in
**[docs/API-MINIMAPS.md](docs/API-MINIMAPS.md)** and of the three map layers in
**[docs/MAPS.md](docs/MAPS.md)**.

Summary of what was decided there:

- Every player founds a **nation** (name, RGB colour, capital, members with roles).
- Nations **claim chunks** (granularity: 1 chunk) and can **dispute** them (10 minute
  defense window; if nobody defends, the chunk changes owner).
- The territory of a nation is always **contiguous**: a chunk can only be claimed
  next to the land already owned, so a nation is a shape and not a scatter of squares.
- **Three map layers**: a political map (`/nation map`) that paints the real
  territory with the exact colour and the border of the shape, the **markers of the
  capitals** in Xaero's Minimap / World Map, and the **territory drawn as a polygon**
  in JourneyMap (used as a dependency: it is the most polished map mod and the only
  one with a public API for polygons).

## 8. Environment notes

- The dev server uses `run/` as its working directory (world, logs, config).
- `online-mode=false` is what lets the bot join without a Mojang account.
  **Do not use that server on the internet.** Remote control uses RCON on 25575 with the
  password `modserver`: change it in `run/server.properties` if the machine is shared.
- The client GUI works through WSLg (X11 over WSL2). The audio needs the `null` driver of
  OpenAL Soft (`ALSOFT_DRIVERS=null`, already set by `tools/client.sh`), because there is
  no audio device available in this environment.
- **The operating system screen cannot be captured**: the X11 root in WSLg is black. The
  capture is taken from inside the game (`/modserver screenshot`).
- FIFOs do not work in `/mnt/c` (drvfs); that is why the remote control uses RCON.
- `rm -f` with a pattern that matches nothing returns a non-zero code (the environment
  routes it to the trash): in scripts chained with `&&` it breaks the chain. Use `rm -f`
  only on files that exist.
- The dev server is started with `setsid` and `--no-daemon` so it survives the end of the
  task that launched it; otherwise it dies with it and the world is left with the
  `run/devworld/session.lock` file taken.
- The GameTest uses its own `run-gametest/` directory, which is recreated on every run:
  if it shares a world with the dev server, the leftovers of previous runs hang the tests.
- `tools/logs/` and `tools/bot/node_modules/` are in `.gitignore`.
- The bot prints a `PartialReadError ... ArmorTrimMaterial` when connecting: it is a known
  mismatch between `minecraft-data` and the 1.21.1 material registry. It does not affect
  the commands or the chat (the scenario runs correctly); it can be ignored.

### Two NeoForge rules this mod respects (and that break the startup if ignored)

1. **A common class cannot reference a client class in a position where the verifier has
   to check assignability.** When the dedicated server loaded `ModServerNetworking`, the
   JVM verifier loaded `NationMapScreen` (to check that it is assignable to `Screen`, the
   parameter of `Minecraft.setScreen`) and NeoForge aborted with `Attempted to load class
   net/minecraft/client/gui/screens/Screen for invalid dist DEDICATED_SERVER`. The fix is
   that all the client code lives under `client/...` (for example
   `client/network/ModServerClientNetworking`) and the common class only calls it with
   plain static calls, inside handlers that never run on the server.
2. **Our own payloads must be registered as `optional()`.** Otherwise the NeoForge
   negotiation aborts the connection of any client that does not speak the NeoForge
   protocol (the test bot included) with the message *"You are trying to connect to a
   server that is running NeoForge, but you are not"*. Also, before sending an optional
   payload you have to check `NetworkRegistry.hasChannel(...)`, because NeoForge throws
   `UnsupportedOperationException` when sending it to a client that did not negotiate it.
