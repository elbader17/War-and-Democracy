# modServer

Development base for **Minecraft 1.21.1 with NeoForge** mods, built so that an agent can
iterate against the real game without human supervision.

| Piece | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.266 |
| ModDevGradle | 2.0.148 |
| Gradle | 8.14.2 |
| Java | Temurin 21.0.13 (LTS) |
| Mod | `modserver` (package `com.modserver`) |

**Verified state:** the mod compiles, passes **30/30 GameTests** inside the game (8 for the
nation model + 11 for disputes and diplomacy + 11 for the economy), the bot joins the world
as a real player (even as a *vanilla* client), the GUI client connects to the server on its
own under WSLg, **the nation map paints the territory with the exact RGB colour** and **the
nation markers show up in the Xaero minimap** (cyan = the nation colour). Reference
screenshots: `tools/logs/nation-map.png` (own map), `tools/logs/nation-minimap.png` (in-game
view with the minimap) and `tools/logs/client-ingame.png` (game running with the mod block).

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
│   │   ├── command/NationCommands.java      <- /nacion crear|claim|unclaim|info|lista|miembros|color|acuerdo|mapa|disputar|defender|disputas|economia|defensa|atacar|guerra|paz|alianza|pacto|admin
│   │   ├── nation/                   <- Nation, NationRole, NationRelation, NationManager (domain)
│   │   ├── claim/                    <- Claim, ClaimIndex (territory)
│   │   ├── dispute/                  <- Dispute, DisputeStatus, DisputeIndex, DisputeService (disputes)
│   │   ├── economy/                  <- NationEconomy, EconomyRules, EconomyService, Investment
│   │   ├── data/ModServerStore.java  <- JSON persistence in world/modserver
│   │   ├── data/ModServerDataGen.java   <- datagen (recipe + advancement)
│   │   ├── network/                  <- map payloads + registration (common code)
│   │   ├── client/ModServerClientCommands.java  <- /modserver screenshot (client only)
│   │   ├── client/map/                <- own map (Layer A) + Xaero bridge (Layer B)
│   │   ├── client/network/ModServerClientNetworking.java  <- client handlers
│   │   └── gametest/                  <- ModServerGameTests (8), ModServerDisputeGameTests (11),
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

## 2. Toolchain (installed in WSL, no sudo)

| Tool | Path |
|---|---|
| JDK 21 | `~/.mctools/jdk21` |
| Node 22 | `~/.mctools/node` |
| Gradle cache | `~/.gradle` (on ext4: much faster than `/mnt/c`) |

`tools/env.sh` exports them. Every script sources `tools/env.sh` on its own.

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
                               # view with the Xaero minimap (tools/logs/nation-minimap.png)
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
| `/modserver spawncog` | server | places the mod block in front of the player (useful for testing) |
| `/modserver screenshot` | client | saves the game framebuffer in `<gameDir>/screenshots/modserver.png` |

### Nation commands (`/nacion`, alias `/nation` and `/nac`)

| Command | What it does |
|---|---|
| `/nacion crear <name> [colour]` | founds a nation (one per player); the capital is the chunk you are on |
| `/nacion claim` | claims the chunk you are on |
| `/nacion unclaim` | releases the chunk you are on |
| `/nacion info` | your nation card (chunks, members, colour, capital, relations) |
| `/nacion lista` | lists the nations of the world |
| `/nacion miembros add\|remove <player>` | member management |
| `/nacion color <0-16777215>` | nation RGB colour |
| `/nacion acuerdo <nation> <pact\|ally\|war\|neutral>` | signs an agreement between nations |
| `/nacion mapa` | opens the nation map (full screen, exact territory colour) |
| `/nacion disputar [x] [z]` | opens a dispute over a chunk (yours by default) |
| `/nacion defender [x] [z]` | the owning nation answers the dispute: the chunk is kept |
| `/nacion disputas` | lists the disputes (state, chunk, challenger and defender) |
| `/nacion guerra <nation>` | declares war (it changes how disputes work) |
| `/nacion paz <nation>` | back to neutrality |
| `/nacion alianza <nation>` | signs an alliance (disputes are blocked) |
| `/nacion pacto <nation>` | signs a non-aggression pact (disputes are blocked) |
| `/nacion atacar [x] [z]` | declares war (if needed) and opens a dispute over that chunk |
| `/nacion economia` | treasury state: balance, income, upkeep, defense and investments |
| `/nacion economia invertir <farm\|garrison\|treasury>` | invests coins in an upgrade |
| `/nacion defensa` | today's defense cost and whether the nation is protected |
| `/nacion defensa pagar` | pays for defense (protects the territory again) |
| `/nacion admin resolve <id> keep\|transfer` | manual resolution (op level 2) |
| `/nacion admin economy tick\|balance <nation> <coins>` | forces the economy day or sets a balance |

The data is stored in **`world/modserver/nations.json`**, **`claims.json`** and
**`disputes.json`** (plain JSON so an admin can edit it by hand). Server config:
`world/config/modserver-server.toml`.

Fixed game rules: one nation per player, the nation survives the death of its owner,
claims do not expire, 1 chunk per claim, overworld only and **blocks can be broken**
(there is no anti-grief protection).

The client can also be automated with system properties:

| Property | Effect |
|---|---|
| `modserver.autoJoin=host:port` | joins that server on startup |
| `modserver.autoScreenshot=true` | takes a screenshot when joining the world |
| `modserver.autoScreenshotDelay=60` | ticks to wait before the capture |
| `modserver.autoOpenMap=true` | opens the map screen when joining the world |
| `modserver.autoOpenMapDelay=40` | ticks to wait before opening the map |

The properties are set in `build.gradle`, in the run configurations `clientAuto`
(capture), `clientMap` (capture + map opened) and `clientMinimap` (capture without
opening the map, to see the markers in the Xaero minimap), which are launched with
`./gradlew runClientAuto`, `./gradlew runClientMap` and `./gradlew runClientMinimap`.

### Disputes (Phase 4)

The full cycle is:

1. A nation opens a dispute over another nation's chunk: `/nacion disputar` (or
   `/nacion disputar <x> <z>`). The defending nation gets a chat warning.
2. The defender answers in time: `/nacion defender` -> the chunk is kept.
3. If nobody defends before the window expires, **the chunk goes to the challenging
   nation** (the server checks it once per second).
4. An administrator can resolve it by hand: `/nacion admin resolve <id> keep|transfer`
   (it can also re-resolve an already defended dispute: a player's decision does not bind
   the server administration).

The defense window and the anti-abuse cooldowns live in
`world/config/modserver-server.toml`:

| Option | Default | Purpose |
|---|---|---|
| `disputeWindowMinutes` | 10 | minutes to defend the chunk |
| `disputeCooldownMinutes` | 30 | wait for a nation before disputing again |
| `disputeChunkCooldownMinutes` | 120 | wait for a chunk before it can be disputed again |

A **pact** or an **alliance** between the two nations blocks disputes; war or neutrality
allow them. The end-to-end check is `tools/dispute_check.sh` (two real bots + RCON
assertions).

### Diplomacy (Phase 5)

The relation between two nations is changed with `/nacion guerra|paz|alianza|pacto
<nation>` (or with the generic `/nacion acuerdo <nation> <type>`). Relations are
bilateral and stored with the nation.

**War has real effects** on disputes:

| Situation | Defense window | Cooldown per attacker |
|---|---|---|
| Neutrality or broken pact | `disputeWindowMinutes` (10 min) | `disputeCooldownMinutes` (30 min) |
| War | `warDisputeWindowMinutes` (5 min) | none (`warIgnoresDisputeCooldown`) |

`/nacion atacar <x> <z>` is the invasion shortcut: it declares war (if needed) and opens
the dispute over that chunk. `/nacion info` and `/nacion lista` show the relations and who
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
| **Unprotected** | an unprotected nation **cannot defend its disputes** and its garrison does not act, until it pays (`/nacion defensa pagar`) |
| **Attack** | opening a dispute costs `attackCost` (a deposit, never refunded); at war it costs half (`warAttackDiscountPercent`) |

**What you invest in** (three investments, three levels each):

| Investment | Effect | Purchase cost |
|---|---|---|
| **Farms** | +25% income per chunk and per level | `farmCost` |
| **Garrison** | one point per level: it defends disputes **without any player connected** (the points are refilled when the defense is paid) | `garrisonCost` |
| **Treasury** | +1% daily interest on the balance per level | `treasuryCost` |

Commands: `/nacion economia` (state), `/nacion economia invertir
<farm|garrison|treasury>`, `/nacion defensa` and `/nacion defensa pagar`. For the
server administrator: `/nacion admin economy tick` (forces the day close) and
`/nacion admin economy balance <nation> <coins>`. End-to-end check:
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
**[docs/API-MINIMAPS.md](docs/API-MINIMAPS.md)**.

Summary of what was decided there:

- Every player founds a **nation** (name, RGB colour, capital, members with roles).
- Nations **claim chunks** (granularity: 1 chunk) and can **dispute** them (10 minute
  defense window; if nobody defends, the chunk changes owner).
- **Two map layers**: an own map (`/nacion mapa`) that paints the real territory with the
  exact colour, and dynamic markers in Xaero's Minimap / World Map (and later JourneyMap
  and JEM) through its official waypoint API.

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
