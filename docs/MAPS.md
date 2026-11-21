# Map layers (Xaero's Minimap and JourneyMap)

The demo uses JourneyMap for its minimap. Press `M` for the full screen terrain map
with country borders. Active disputes are red tiles with a pale X. Right-click a
chunk and select **Inspect / dispute territory** to see its owner. For an active
dispute the panel shows the participants and remaining time. For an eligible rival
chunk it offers the current price and defense window. Confirming opens the dispute
using the country's treasury. The tile clears when the dispute ends. Use `/nation map`
to open the political map.

| Layer | What it shows | How |
|---|---|---|
| **Political map** (`/nation map`) | The territory of every nation as a solid area with the exact RGB colour and the border of the shape | Own code (`NationMapScreen`), no external mod needed |
| **Xaero's Minimap / World Map** (optional, installed separately) | One marker per nation (its capital) | Third party waypoint API |
| **JourneyMap** (minimap and full screen map) | The territory of every nation drawn as a **polygon** (the union of its chunks) | JourneyMap overlay API v2 |

The claimed chunks are **not** published as points in any minimap: the minimaps stay
clean and the shape of the land is only shown in the maps that can draw areas.

---

## 1. Xaero's Minimap (waypoints)

Dependency (`build.gradle`):

```groovy
compileOnly "xaero.minimap:xaerominimap-neoforge-${minecraft_version}:${xaero_minimap_version}"
```

API used (verified with `javap` on `xaerominimap-neoforge-1.21.1-26.6.0`):

```java
XaeroMinimapSession.getCurrentSession()                      // HUD session
    .getSession(BuiltInHudModules.MINIMAP)                   // minimap session
    .getWorldManager().getCurrentWorld()                      // world
    .getContainer()                                          // container
    .getThirdPartyWaypointManager()                           // waypoint manager
    .get("modserver");                                       // our waypoint set
// set.clear(), set.setEnabledStateGetter(() -> true)
// set.add(id, waypoint), set.addRenderInfoOverride(id, info)
```

Notes:

- The third party manager belongs to the **world**: creating a standalone one makes
  the waypoints disappear.
- `setEnabledStateGetter(() -> true)` is the "global switch" of the API.
- Xaero only has a **21 colour palette** (no free RGB): the nation RGB is mapped to
  the nearest palette colour (`nearestPaletteColor`). The exact colour is only
  visible on the own map and in the JourneyMap polygons.
- The same waypoint system is used by Xaero's World Map, so the markers appear in
  both without extra work.

## 2. JourneyMap (polygons)

JourneyMap **6.0.9** is the first version with a public API that can draw
**polygons**, so the territory is painted as a shape and not as a cloud of points.

Dependencies:

```groovy
compileOnly "maven.modrinth:journeymap:${journeymap_version}"   // 1.21.1-6.0.9+neoforge
localRuntime "maven.modrinth:journeymap:${journeymap_version}"
// The public API (v2.0.0) is shipped inside the mod jar as a JarJar dependency,
// so it is vendored in libs/ to compile against it (see libs/README.md).
compileOnly files("libs/journeymap-api-neoforge-2.0.0-1.21.1.jar")
```

API used (verified with `javap` on `journeymap-api-neoforge-2.0.0-1.21.1`):

```java
ClientAPI.INSTANCE                                            // IClientAPI
    .removeAll("modserver", DisplayType.Polygon);              // clear the old shapes
    .show(overlay);                                            // register a new shape

PolygonHelper.createChunksPolygon(chunks, y)                   // union of the chunks
    -> List<MapPolygonWithHoles>                               // one per connected piece

new PolygonOverlay(modId, dimension, shapeProperties, hull)    // the shape
    .setHoles(holes)
    .setTitle(name).setLabel(name)
    .setOverlayGroupName("modserver.nations")
    .setActiveUIs(Context.UI.Minimap, Context.UI.Fullscreen);
```

Notes:

- `Displayable(String)` takes the **mod id** (the owner of the overlay), not an id:
  that is the value used by `removeAll(modId, displayType)`.
- `ShapeProperties.setFillColor(int)` takes a **RGB** colour; the opacity is a
  separate float (`setFillOpacity`, `setStrokeOpacity`, `setStrokeWidth`).
- `PolygonHelper.createChunksArea` works in **block** coordinates (16x16 per chunk),
  so the polygons are aligned with the world.
- A disconnected territory produces several polygons (one per connected piece) and
  the enclaves become holes of the outer polygon.
- The overlays appear in the **minimap** and in the **full screen terrain map**.
  Opening the terrain map with `M` requests fresh borders around the player.

## 3. Own map of the mod

Each claim uses its country's colour. A gold line follows the border between two
countries. A pale line follows the boundary with unclaimed land. The map legend
identifies both. The server checks all four neighbours of each claim when sending
a map update, including neighbours outside the displayed area. JourneyMap uses
the same edges on its terrain map and minimap. The vanilla chat map marks chunks
that touch another country with ▣.

Controls: wheel to zoom, drag to move, click a nation in the panel to go to its
capital, right-click a chunk to inspect or dispute it, `ESC` to close.

## 4. Verifying it

```bash
./tools/map_check.sh     # political map and JourneyMap minimap captures
./tools/demo.sh          # full demo: 3 nations, dispute, both captures
./gradlew runClientJourneyMap   # JourneyMap full screen map with the polygons
```

The client logs are the fastest check:

```
MODSERVER_SELFTEST JOURNEYMAP territories drawn: 5 shapes from 4 nations
```
