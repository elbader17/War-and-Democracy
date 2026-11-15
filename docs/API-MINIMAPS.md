# Minimap integration reference

Signatures verified with `javap` over the real jars (not from memory).
Reference servers: `https://chocolateminecraft.com/maven` (Xaero) and
`https://api.modrinth.com/maven` (Modrinth).

---

## 1. Xaero's Minimap + Xaero's World Map

**Artifact** (NeoForge 1.21.1, official Mojang mappings → compatible with Parchment):

```groovy
repositories { maven { url = 'https://chocolateminecraft.com/maven' } }
dependencies {
    compileOnly  "xaero.minimap:xaerominimap-neoforge-1.21.1:26.6.0"
    localRuntime "xaero.minimap:xaerominimap-neoforge-1.21.1:26.6.0"
}
```

World Map shares the minimap's waypoint system → **no second integration is needed**
(artifact: `xaero.map:xaeroworldmap-neoforge-1.21.1:1.47.0`).

### 1.1 The official third-party hook

```
xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypointManager
    ThirdPartyWaypointManager(MinimapSession)
    ThirdPartyWaypoints get(ResourceLocation originId)
    void clear()
    void clearOrigin(ResourceLocation originId)
    int getCount()
    Collection<ThirdPartyWaypoints> getAll()

xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypoints
    void add(String id, Waypoint waypoint)
    void remove(String id)
    void clear()
    Waypoint get(String id)
    Collection<Waypoint> getWaypoints()
    ResourceLocation getOriginId()
    void addRenderInfoOverride(String id, WaypointRenderInfo renderInfo)
    void setEnabledStateGetter(Supplier<Boolean> enabledStateGetter)
```

Key points:
- `originId` = our `ResourceLocation` (`modserver:nations`). Xaero uses it to
  group and to be able to delete only our markers (`clearOrigin`).
- `addRenderInfoOverride` allows **forcing icon and color** of a waypoint without touching the
  original `Waypoint` → perfect for painting each claim with the color of its nation.
- `setEnabledStateGetter` is a global switch for the set: it allows hiding the claims
  in real time (when leaving a region, when losing a claim) without resending anything.

### 1.2 Creating a waypoint

```
xaero.common.minimap.waypoints.Waypoint extends xaero.hud.minimap.waypoint.WaypointRenderInfo
    Waypoint(int x, int y, int z, String name, String symbol,
             WaypointColor color, WaypointPurpose purpose, boolean global)
    // legacy constructors: (..., int color) and (..., int color, int purpose)
    setName(String), setWaypointColor(WaypointColor), setInitials(String), ...
```

```
xaero.hud.minimap.waypoint.WaypointRenderInfo
    setInitials(String)
    setWaypointColor(WaypointColor)
    setVisibility(WaypointVisibilityType)
    setDisabled(Boolean)
    isThirdPartyDeleted()
```

```
xaero.hud.minimap.waypoint.WaypointColor      (ENUM, 21 values)
    BLACK, DARK_BLUE, DARK_GREEN, DARK_AQUA, DARK_RED, DARK_PURPLE, GOLD, GRAY,
    DARK_GRAY, BLUE, GREEN, AQUA, RED, PURPLE, YELLOW, WHITE, MAGENTA, LIGHT_BLUE,
    LIME, PINK, BROWN

xaero.hud.minimap.waypoint.WaypointPurpose     (ENUM)
    NORMAL, DEATH, OLD_DEATH, DESTINATION, ...
```

### 2.2a ⚠️ Real limitation: the minimap color is not free RGB

`WaypointColor` is a **enum of 21 fixed colors**, not an arbitrary RGB. A nation with
color `#A64BFF` cannot be painted exactly on the Xaero minimap.

**Strategy** (in the plan, §5 Layer B):

1. The nation stores its **real RGB** (for our own map and for particles/GUI).
2. When publishing to Xaero, a **nearest-neighbor mapping from RGB to the 21 colors** of the
   palette is done (weighted distance in RGB space; the eye forgives well in Lab, but RGB
   is enough).
3. For nations that fall on the same palette color, they are disambiguated with the
   **initials** (`WaypointRenderInfo.setInitials`) and with the waypoint name.
4. The own map (§5 Layer A) does paint the exact RGB → the real color is always
   available; the minimap is a visual shortcut.

This reinforces that Layer A (own map) is not optional: it is the only way to see the
territory with the color the player chose.

### 1.3 Getting the session (access chain)

```
xaero.common.XaeroMinimapSession
    static XaeroMinimapSession getCurrentSession()
    static XaeroMinimapSession getForPlayer(LocalPlayer)
    <MS extends ModuleSession<MS>> MS getSession(HudModule<MS>)   // from HudSession
```

Full flow:

```java
XaeroMinimapSession hudSession = XaeroMinimapSession.getCurrentSession();
if (hudSession == null) return;                       // minimap not loaded yet
MinimapSession minimapSession = hudSession.getSession(XaeroMinimapMod.minimapModule);
ThirdPartyWaypointManager manager = new ThirdPartyWaypointManager(minimapSession);

ThirdPartyWaypoints set = manager.get(ResourceLocation.fromNamespaceAndPath("modserver", "claims"));
set.setEnabledStateGetter(() -> claimsVisible);       // show/hide on the fly

for (ClaimMarker m : markers) {
    String id = m.dimension() + ":" + m.chunkX() + ":" + m.chunkZ();
    set.add(id, new Waypoint(m.x(), m.y(), m.z(), m.nationName(), "N",
                             nearestPaletteColor(m.nationRgb()),
                             WaypointPurpose.NORMAL, true));
    WaypointRenderInfo info = new WaypointRenderInfo();
    info.setWaypointColor(nearestPaletteColor(m.nationRgb()));
    info.setInitials(String.valueOf(m.nationName().charAt(0)));
    set.addRenderInfoOverride(id, info);
}
```

### 1.4 Version notes

- The `xaero.common.*` package is internal: **encapsulate everything behind `MapProvider`** and
  use reflection for the entry point (`ThirdPartyWaypointManager`) if you want to
  survive changes in Xaero.
- On 1.21.1 Xaero publishes with official mappings; with Parchment (our case) the
  signatures match.
- If Xaero is not installed, the mod must keep working → `optional` dependency
  in `neoforge.mods.toml` and a check with `ModList.isLoaded()`.

### 1.5 Real implementation in this repo

Two classes, with a deliberate loading boundary:

- `client/map/XaeroMapProvider` — **the only one that is always loaded**. It checks
  `ModList.get().isLoaded("xaerominimap")` and, if present, calls `XaeroBridge`.
  `XaeroMapProvider` does not mention any Xaero class in its bytecode: if it did, the
  JVM verifier would load `XaeroBridge` (and with it the Xaero classes) even
  on a client without Xaero installed.
- `client/map/XaeroBridge` — the one that does use the API: `XaeroMinimapSession.getCurrentSession()`
  → `getSession(BuiltInHudModules.MINIMAP)` → `getWorldManager().getCurrentWorld()`
  → `getContainer().getThirdPartyWaypointManager()` → `manager.get(modserver:nations)`
  → `clear()` + `add(id, waypoint)` per claim.
  One waypoint per claimed chunk, with the nation name, its initials and the palette
  color closest to the RGB (weighting `3R + 4G + 2B`).

What was checked in practice:

- The minimap and the World Map share waypoints: publishing in the minimap session is enough.
- **The third-party waypoint manager belongs to the world**, not to the session: creating a
  loose one (`new ThirdPartyWaypointManager(...)`) does not work, because its store is internal and
  the waypoints get lost. The correct path is
  `session.getSession(BuiltInHudModules.MINIMAP).getWorldManager().getCurrentWorld()
  .getContainer().getThirdPartyWaypointManager()`.
- **The third-party set needs the global switch**: without
  `waypoints.setEnabledStateGetter(() -> true)` the set can end up hidden and is not drawn.
- **The render information must be registered**: without
  `waypoints.addRenderInfoOverride(id, renderInfo)` (with `setWaypointColor` and
  `setInitials`) Xaero uses its default color (yellow) instead of the nation's.
- The last boolean of the `Waypoint` constructor is `temporary`: these waypoints are
  permanent as long as the nation exists (`false`).
- `XaeroMinimapSession.getCurrentSession()` returns `null` until the minimap loads,
  and the minimap world can take longer than the session: that is why the publishing is
  **retried on every client tick** (`XaeroBridge.retryIfPending()`) instead of
  being published only once. Without that retry, the map data can arrive before
  the minimap world exists and the markers never get published.
- The client log confirms the publication:
  `MODSERVER_SELFTEST XAERO waypoints published: N claims from M nations (Xaero sees N)`.

Visual verification: `tools/logs/nation-minimap.png` and the pixel analysis of the
screenshot (the markers appear in `#54F0EB`, the cyan of the Xaero palette, which
matches the `#55FFFF` color of the example nation).

---

## 2. JourneyMap

- Present on Modrinth (`journeymap`) and with **NeoForge 1.21.1** versions
  (`1.21.1-6.0.0-beta.88+neoforge`).
- It exposes its API (`jmapi`) with waypoint support; it is distributed as a separate
  maven artifact (`journeymap:journeymap-api`) that has to be located in its maven at
  implementation time (v2 phase of the plan).
- Just like Xaero, the API is waypoint-based: there is no polygon API.

## 3. Just Enough Minimap

- **Pending verification** of NeoForge 1.21.1 support (JEM was born for Fabric).
  Verify before promising integration; if there is no NeoForge build, it is documented as
  "not supported" and dropped.
- It has its own waypoint API.

## 4. Design conclusion (why the own map is mandatory)

| Capability | Xaero | JourneyMap | JEM | Own map |
|---|---|---|---|---|
| Marker with icon/color | ✅ | ✅ | ✅ | ✅ |
| Territory by color (polygon) | ❌ | ❌ | ❌ | ✅ (ours) |
| Mandatory dependency | ❌ | ❌ | ❌ | ❌ |

Hence the two layers of the plan: **Layer A** (own map with the real territory) +
**Layer B** (markers on the minimap the player already uses).
