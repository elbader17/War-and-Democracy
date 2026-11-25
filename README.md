# War & Democracy

The [territorial defense workshop](docs/TERRITORIAL-DEFENSE.md) covers V2 machines, power, ammunition, radar and command.

The [Six Nations Earth scenario](scenarios/six-nations/README.md) ships with the V1 campaign.

## Getting started

Use Java 21 and NeoForge **21.1.266** on a Minecraft Java **1.21.1** server. Run
`tools/build.sh` with `JAVA_HOME` pointing to JDK 21, copy
`build/libs/modserver-2.0.0-beta.1.jar` into the server's `mods/` directory, and start
the server. The base campaign supports vanilla Minecraft Java 1.21.1 players. For the physical defense workshop, install the same NeoForge, War & Democracy, Create and defense add-on versions on the server and each client.

1. Run `/nation create Aurora` in the Overworld to found a nation at your current
   location. Each nation has one owner, and each player can found one nation.
2. Run `/nation claim` to claim the 16 × 16 block chunk you are standing in.
   Move into an adjacent chunk to expand. Press **F3 + G** to see chunk boundaries.
3. In the demo, press **M** to open JourneyMap's overhead terrain map. Gold lines
   separate neighboring nations. Pale lines border unclaimed land. A **pale X on
   red** marks a disputed chunk. Right-click a chunk and choose **Inspect / dispute
   territory** to see its owner, the participants in an active dispute, and the
   time remaining in the engagement. Choose **Tactical map** for army orders. Install the mod and
   JourneyMap on your NeoForge client for this view. The political map opens
   with `/nation map`. On a vanilla client it appears in chat as a north-up grid.
   Hover over a cell to see its coordinates and owner, or click it to inspect it.
   The ▣ symbol marks an international border.
4. Run `/nation economy` to inspect your treasury. Nations start with **100
   coins**. With the default settings, three chunks earn **36 coins per game day**
   before any investment bonuses. The sidebar shows your balance, daily income,
   territory and capital. It updates on both vanilla and modded clients. An existing
   server scoreboard takes priority while it uses the sidebar.
5. Press **U** for the tactical map, or open **K → Manage country → Military → Tactical map**.
   Use **Buy** to recruit armies and **Deploy** to place them on a chunk.
   Use **Move** to send deployed armies to a neighboring chunk of your nation.
   In **Attack**, pick your origin and a neighboring rival chunk, choose how
   many armies to send, or select **All troops**, then click **Launch attack**.

The sample cog is available for existing worlds. Servers where every player has
the mod installed can enable its recipe with `/datapack enable "mod/modserver:cog_demo"`.

### Manage a nation and sell resources

Press **K → Manage country** with the client mod, or run `/nation panel` (also `/nacion panel`).
The owner can claim or release the current chunk, view its owner, open the map,
edit the nation's name, preamble and doctrines, review income,
and inspect a rival chunk to fund a dispute. Preambles and doctrines are
player-written text. The military tab shows reserve troops and lets the owner upgrade
the garrison for daily reinforcements. Vanilla clients receive an interactive book.
Click an edit link there, then click the chat prompt and press Enter to save.

Country names can contain spaces and accented letters, for example
`/nation create República Democrática`. Quote multiword names when another
argument follows, for example `/nation agreement "República Democrática" ally`.
To found a country whose name ends in a number, quote the name to distinguish
it from the optional map color.

Aim at a regular chest within reach in **any chunk your nation owns** and run
`/nation reserve` (Spanish: `/nacion reserva`). Both halves of a double chest
can supply the reserve if your nation owns the land under both. The active chest
gets a teal-and-gold texture with the mod installed and a gold band on vanilla
clients. Linking another chest moves the reserve; each chest keeps its contents.
A lost claim or a broken chest stops supplying the market. Open **K → Manage country → Treasury**
to review existing deposits and anything added later.

| Plain item | Coins per unit by default |
|---|---:|
| Iron ingot | 1 |
| Gold ingot | 4 |
| Diamond | 20 |
| Netherite ingot | 100 |

The treasury lists stock, unit prices, each material's value, total stock value
and the separate coin balance. Select a material, enter a quantity (leave it
empty for all), and review the sale. Confirming removes exactly that quantity
from the linked chest and credits the nation. Quotes expire after one minute.
Named or customized items are excluded. Server owners can change prices in the
`market` section of the mod's server configuration.

### Petroleum concessions (optional Create integration)

Install Create and Create: Diesel Generators on both the server and participating clients.
The integration was tested on Minecraft 1.21.1 with NeoForge 21.1.266, Create 6.0.10 and
Diesel Generators 1.3.16. The rest of War & Democracy can run independently.

1. Right-click with the addon's Oil Scanner in a chunk your nation controls. When the
   scan finishes, its reading is registered automatically at your location. The
   **Survey** button in the **Petroleum** tab and `/nation oil survey` also register a reading.
   The survey uses the addon's actual remaining deposit and scanner height limit.
   A successful survey marks the chunk with a gold outline and an oil pin on the campaign board (**U**) and the overhead terrain map (**M**). The terrain map labels the pin **Oil field** and keeps your own concessions visible beyond the nearby territory overlay.
   The Petroleum tab totals the latest surveys across all fields your nation currently owns and
   shows the current chunk's reading separately. Surveying the same chunk again replaces its
   previous reading, so you can update the estimate after extraction.
2. Use Create pipes to feed a fluid tank or the addon's oil barrel from a pumpjack or
   distillation tower. Build the entire tank inside the concession. The tank can
   also receive fuel transported from another facility.
3. Stand in the concession, aim at its tank and open `/nation oil`.
   The panel shows the physical volume, rates and remaining export allowance.
   Enter a number of whole buckets and confirm the offer. Chat also supports
   `/nation oil export <buckets>`. Each bucket is 1,000 mB.

| Product | Default national coins per bucket |
| --- | ---: |
| Crude oil | 8 |
| Diesel | 16 |
| Gasoline | 12 |

Each field may export a combined **32 buckets per 24,000 elapsed server ticks**.
A pipeline may keep feeding a tank while its owner is away. Selling draws only the
confirmed volume and credits the current owner's treasury. Disputes suspend exports.
Capturing or reclaiming the land transfers the concession with its remaining quota.
A depleted field can still export stored or imported fuel. Server owners can tune the
prices and allowance in the `petroleum` section of the mod's server config.

For development, add `-Ppetroleum=true` to a Gradle run to load both mods.
`ORG_GRADLE_PROJECT_petroleum=true bash tools/gametest.sh` runs the real tank and
deposit integration test alongside the rest of the suite.

### Challenge a rival NPC

In the demo, right-click the **Envoy of Baluarte** by the border road and press
**T** to choose a border challenge. Planning an attack opens the tactical board.
Accepting a counterattack lets the envoy buy and deploy reinforcements with its
own treasury and engage your border in a sustained battle. A surviving
defense holds the land. The war bar and map mark ongoing engagements.

To add an envoy on another server, an operator can stand in an unclaimed chunk
and run `/nation admin rival Baluarte`. This creates a persistent envoy and founds
a nation with up to three adjacent chunks. Its envoy uses its treasury to muster up to four armies in the capital. The rival participates in the same
economy as player nations.

**Nations, disputes, diplomacy and economy for Minecraft 1.21.1 (NeoForge).**

A server-side mod for SMP servers that want the classic "land claiming" gameplay
with a political layer on top: every player founds a **nation**, the land is
**claimed chunk by chunk** (and always as one contiguous shape), the neighbours can
**dispute** it, the nations sign **pacts, alliances or wars** between them, and
each claimed chunk earns daily income for its nation's treasury.

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

**Verified state:** the mod compiles, passes **37/37 GameTests** inside the game
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
│   │   ├── command/NationCommands.java      <- /nation create|claim|unclaim|info|list|members|color|agreement|map|dispute|defend|disputes|economy|attack|war|peace|alliance|pact|admin
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
./tools/gametest.sh       # 37 GameTests inside the game
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
                               # (earn income, buy troops and deploy them)

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
| `/nation tactics [x] [z]` | opens the tactical board or inspects forces on a vanilla client |
| `/nation dispute [x] [z]` | opens the tactical board at the specified chunk |
| `/nation defend [x] [z]` | opens the tactical board to reinforce a chunk |
| `/nation disputes` | lists the disputes (state, chunk, challenger and defender) |
| `/nation war <nation>` | declares war (it changes how disputes work) |
| `/nation peace <nation>` | proposes peace during war or leaves a treaty |
| `/nation alliance <nation>` | proposes or ratifies an alliance |
| `/nation pact <nation>` | proposes or ratifies a non-aggression pact |
| `/nation attack [x] [z]` | opens the tactical board at a planned target |
| `/nation economy` | treasury balance, daily income, reserve troops and investments |
| `/nation economy invest <farm\|garrison\|treasury>` | invests coins in an upgrade |
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

### Tactical map and combat

Open **U**, `/nation tactics`, or `/nacion tactica`. The campaign map draws national borders and
colors each claimed zone on parchment. A flag identifies each country. As armies accumulate, the map exchanges pieces
within each branch. Pieces represent 1, 10, 100 or 1,000 troops. The largest branch supplies the visible counter.
Ground uses military insignia, air uses wings and navy uses ships. A force of 15,000 ground troops appears as a battalion standard
marked **×15**. A **+** means there are additional troops. Hover over the chunk
to see every denomination and its exact count, or over the legend to see the
insignia and names of the formations. For example, 1,000 troops make a ground battalion, an air wing or a naval fleet. The battlefield uses the same denominations for its figures.
Orders, dice and losses are counted in individual troops.
Rockets mark stored missiles. Red hatching and crossed swords mark disputed chunks.
Terrain shading and signs for grassland, forest, water and steep ground follow the
surface of chunks your client has loaded. Drag to pan and scroll to zoom. At low
zoom the counters keep their formation insignia. Hover over a chunk for exact troop
and missile counts. Army counters appear exclusively on the tactical board.

The buttons above the order panel open separate forms. **Buy**, **Deploy** and
**Move** remember their own quantities while the board is open. **Attack**,
**Build missile** and **Launch missile** each remember their own chunks. A solid
turquoise frame marks your origin. Blue corners mark a friendly movement
destination and amber corners mark a rival target. To move either marker,
click its **Change** button and pick another
chunk on the map. Review the cost or expected effect, then use the order button
to execute.

- Choose **Ground**, **Air**, or **Navy** when recruiting. All three branches can use any claimed chunk.
  **Mixed** deployment, movement and attack orders draw troops in proportion to the available branches.
  Each duel randomly draws one surviving troop per side. Air has a slight edge over ground, ground over navy,
  and navy over air. The favored troop counts a roll of **1 as 2**. This changes one of the 36 possible
  dice pairs. An attacker wins 16/36 duels with the edge, 15/36 against the same branch, and 14/36 at a disadvantage.
  Only the branch that loses the duel takes a casualty.
  The defender wins a tie after modifiers. Group size adds no combat bonus.
- Armies cost 10 coins apiece by default (`economy.armyPrice`). Buying adds to
  the nation's reserve. Choose an owned chunk, enter the quantity, and deploy.
  New claims start with one ground troop. Armies, reserves and ongoing attacks from older saves
  load as ground troops. Garrison investments add ground troops to the reserve.
- In **Move**, select your origin and an owned chunk sharing an edge. Enter
  the quantity, review both garrisons, then click **Move armies**. At least one
  army stays at the origin. The transfer is free and missiles stay in place.
- In **Attack**, choose any number of troops, including the entire garrison.
  They leave the origin immediately. The land stays yours even if it is empty.
  The server rolls one die for each side every five seconds of game time. Each
  duel removes one army, and ties favor the defender. Survivors occupy the target
  when its defense falls. An empty target takes one interval to occupy.
  With 20 attacking 20, an uninterrupted battle takes 100–195 seconds.
  Defenders can deploy reinforcements while the battle is running. Battles
  continue with the map closed or players offline and resume after a server restart.
  On the battlefield, infantry charge with swords and shields, biplanes bank into
  strafing runs, and patrol boats fire arcing shells from recoiling deck guns.
  Formations carry their national colors and repeat these maneuvers every 2.5
  seconds, twice per duel. Each roll triggers an impact on
  the losing branch, including the final casualty. Nearby vanilla clients
  receive the battle sounds and particles.
- Exchange six deployed armies for one missile at the selected origin, leaving an
  occupying army. Open **Launch missile**. The map picks your armed workshop or
  your only armed base on the board when possible. Gold corners mark eligible
  targets. Click one to preview the losses and confirm the launch.
  A missile removes three, two or one defending armies at grid
  distances one, two or three respectively (count orthogonal edges). A launch
  consumes the missile and must leave at least one defender. Capturing a chunk
  also captures its missiles. Pacts and alliances block both hostile orders.
  After the server accepts an attack or missile launch, the issuing player's
  tactical board closes so they can watch the action in the world. A missile
  launch turns the firing player's camera toward the launch site at ignition.
  Mouse look is available immediately. Other observers can follow the flight on their boards.
- The **Nuclear** tab sells one nuclear missile at the selected friendly silo
  for 1,000,000 treasury coins by default (server setting `nuclearMissilePrice`).
  Select a rival center within three chunk edges to preview the 3×3 footprint.
  A strike removes every garrison and every troop fighting there, including
  friendly and allied forces. It ends disputes in those nine chunks and preserves
  ownership, player health and terrain. A treaty blocks targeting its signatory
  as the center. Use `/nation tactics nuclear buy X Z` and
  `/nation tactics nuclear fire X Z TX TZ` to issue the same orders in chat.
- On vanilla clients, `/nation tactics X Z` inspects forces. Order examples:
  `/nation tactics buy 5`, `/nation tactics deploy 0 0 5`, `/nation tactics move 0 0 1 0 3`,
  `/nation tactics roll 0 0 1 0 3`, `/nation tactics exchange 0 0`,
  and `/nation tactics fire 0 0 2 0`. Append `ground`, `air` or `naval` after the quantity to choose a branch,
  e.g. `/nation tactics buy 5 air`. Omitting the branch recruits ground or transfers a proportional mix.

Defender advantage on tied dice is inspired by [Yetem's TEG rules](https://yetem.com/wp-content/uploads/2017/06/TEG.pdf).
Missile exchanges and distance-based losses are adapted from
[TEG La Revancha](https://yetem.com/wp-content/uploads/2017/06/TEG-REVANCHA.pdf).
Players can issue individual orders in real time on the Minecraft server.

### Diplomacy

Press **K** for the Council of Nations, or run `/nation council` (`/nacion consejo`).
The overview shows territory, treasury, investments, reserve chest stock, oil when available,
deployed and reserve troops, engagements and convoys. **Review proposals** opens an incoming
offer directly. Use the index (under **Sections** on small displays) to choose an action,
then choose a country. **Change country** switches the partner for that action.

- **Treaties** — offer a non-aggression pact, military alliance or peace. Player nations
  ratify in **Proposals**. NPC envoys can sign immediately.
- **Sell resources** — offer iron, gold, diamonds or netherite from your linked reserve chest
  for a total price in whole coins. On acceptance the server rechecks both chests,
  available stock, space and funds, then delivers the goods and pays the seller.
- **Send troops** — open the tactical map on the chosen ally, select a garrison, troop
  branch and quantity, and confirm the timed shipment.
- **Messages** — exchange private dispatches, or send a circular to all player-run allies.
  Offers and correspondence remain available when the owner next logs in.

The chat commands `/nation pact|alliance|peace "Country"` propose treaties between
players. Issuing the matching command in return ratifies the proposal. Declaring war
remains unilateral.
A successful hostile order declares war. Both nations receive the battle result.
Use `/nation disputes` to inspect engagements and `/nation peace <nation>` to
propose an end to the war.

#### Sending allied reinforcements

Form an alliance with `/nation alliance "Country Name"` (Spanish: `/nacion alianza "Nombre del País"`).
In **U → Send**, choose a deployed garrison of your own, an allied destination, a branch or **Mixed**, and a quantity.
The preview shows the journey time. Each horizontal or vertical chunk edge takes **10 minutes** of server game time.
For example, traveling three chunks east and two south takes **50 minutes**. A six-chunk journey takes one hour.
Each new return trip uses the same pace. A leg already in transit when the server updates keeps its scheduled arrival.

Troops leave the origin at dispatch and become the ally's troops upon arrival. Both countries can open **In transit**
to see the remaining time, route and troop mix, even after reconnecting or restarting the server.
If the destination changes owner, the alliance ends, or the garrison fills up, delivery is checked on arrival and the
shipment travels back. Returning troops use their original garrison and then the sender's reserve if needed.

Vanilla clients can use `/nation tactics send <fromX> <fromZ> <toX> <toZ> <quantity> [ground|air|naval]`
and `/nation tactics shipments [page]`. The Spanish equivalents are `/nacion tactica enviar` and `/nacion tactica envios`.

### Economy (Phase 6)

Every nation has its **own currency** (the mod treasury, with 2 decimals; vanilla gold is
not involved). Every 24000 ticks, chunks earn income, garrisons supply reinforcements
and treasuries pay interest. Each investment level costs coins once, at purchase.

| Concept | Rule |
|---|---|
| **Income** | every claimed chunk generates `claimDailyIncome` per day -> **more territory, more coins** |
| **Armies** | each purchased piece costs `armyPrice` and can be deployed from the tactical board |

**What you invest in** (three investments, three levels each):

| Investment | Effect | Purchase cost |
|---|---|---|
| **Farms** | +25% income per chunk and per level | `farmCost` |
| **Garrison** | adds one ground troop per level to the national reserve each game day | `garrisonCost` |
| **Treasury** | +1% daily interest on the balance per level | `treasuryCost` |

Commands: `/nation economy` (state), `/nation economy invest
<farm|garrison|treasury>`. For the
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
- Nations **claim chunks** (granularity: 1 chunk) and can **dispute** them (deployed armies defend each chunk and dice determine ownership).
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
