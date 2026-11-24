package com.modserver.client.map;

import com.modserver.command.ModServerCommands;
import com.modserver.network.NationMapPayload;
import com.modserver.network.DisputeMapPayload;
import journeymap.api.v2.common.event.FullscreenEventRegistry;
import net.minecraft.client.resources.language.I18n;
import journeymap.api.client.impl.ClientAPI;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.display.Context;
import journeymap.api.v2.client.display.DisplayType;
import journeymap.api.v2.client.display.PolygonOverlay;
import journeymap.api.v2.client.display.MarkerOverlay;
import journeymap.api.v2.client.display.Overlay;
import journeymap.api.v2.client.model.MapImage;
import journeymap.api.v2.client.model.TextProperties;
import journeymap.api.v2.client.model.MapPolygon;
import journeymap.api.v2.client.model.MapPolygonWithHoles;
import journeymap.api.v2.client.model.ShapeProperties;
import journeymap.api.v2.client.util.PolygonHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Real bridge with the JourneyMap API (v2).
 *
 * <p>Separate class from {@link JourneyMapProvider} on purpose: it is only loaded
 * when JourneyMap is installed, so a client without it never loads its classes.</p>
 *
 * <p>JourneyMap 6.0.9 is the first version with a <b>polygon</b> API
 * ({@code journeymap.api.v2.client.display.PolygonOverlay}), so the territory of
 * every nation is drawn as a <b>shape</b> (the union of its chunks, with the
 * holes of the enclaves) and not as a cloud of points. The same shapes appear in
 * the JourneyMap minimap and in its full screen map.</p>
 *
 * <p>API used (verified with javap on journeymap-api-neoforge-2.0.0-1.21.1):</p>
 * <pre>
 * ClientAPI.INSTANCE                                  -&gt; IClientAPI
 *   .removeAll(modId, DisplayType.Polygon)            -&gt; removes our previous shapes
 *   .show(overlay)                                    -&gt; registers a new shape
 * PolygonHelper.createChunksPolygon(chunks, y)         -&gt; union of the chunks
 * new PolygonOverlay(modId, dimension, shape, hull)     -&gt; the shape itself
 * </pre>
 */
final class JourneyMapBridge {

    static final Logger LOGGER = LoggerFactory.getLogger("MODSERVER/JOURNEYMAP");

    /** Mod id used as owner of the overlays (JourneyMap groups them by mod id). */
    private static final String MOD_ID = "modserver";

    /** Group name of the overlays, shown in the JourneyMap options. */
    private static final String GROUP_NAME = "modserver.nations";

    /** Opacity of the fill of the territory. */
    private static final float FILL_OPACITY = 0.45f;

    // Data pending publication (the latest received from the server).
    private static NationMapPayload.NationView[] pendingNations = new NationMapPayload.NationView[0];
    private static NationMapPayload.ClaimView[] pendingClaims = new NationMapPayload.ClaimView[0];
    private static int pendingPlayerY;
    private static boolean menuRegistered;
    private static boolean published;
    private static int attempts;

    private JourneyMapBridge() {
    }

    /** Stores the received data and draws it in JourneyMap. */
    static void update(NationMapPayload.NationView[] nations,
                       NationMapPayload.ClaimView[] claims,
                       int playerY) {
        pendingNations = nations;
        pendingClaims = claims;
        pendingPlayerY = playerY;
        published = false;
        attempts = 0;
        publish();
    }

    /**
     * Retries the publication of the pending data.
     *
     * <p>Called on every client tick: JourneyMap may not be ready (or the world
     * may not be loaded yet) when the map data arrives.</p>
     */
    static void retryIfPending() {
        if (!published) {
            publish();
        }
    }

    private static void publish() {
        attempts++;
        IClientAPI api;
        try {
            api = ClientAPI.INSTANCE;
        } catch (Throwable error) {
            logNotReady("the client API is not ready", error);
            return;
        }
        if (api == null) {
            logNotReady("the client API is not ready", null);
            return;
        }

        if (Minecraft.getInstance().level == null) {
            return;
        }
        var dimension = NationMapData.dimension();
        try {
            if (!menuRegistered) {
                FullscreenEventRegistry.FULLSCREEN_POPUP_MENU_EVENT.subscribe(MOD_ID, event -> {
                    if (!TerritoryScreen.supported()) return;
                    var world = event.getFullscreen().getUiState().dimension;
                    if (world == null) return;
                    event.getPopupMenu().addMenuItem(I18n.get("map.modserver.inspect"), pos -> {
                        var chunk = new ChunkPos(pos);
                        TerritoryScreen.open(world.location(), chunk.x, chunk.z);
                    });
                });
                menuRegistered = true;
            }
            // Remove the shapes of the previous update: the territory changes
            // every time a chunk is claimed or released.
            api.removeAll(MOD_ID, DisplayType.Polygon);
            api.removeAll(MOD_ID, DisplayType.Marker);

            int shapes = 0;
            for (int i = 0; i < pendingNations.length; i++) {
                NationMapPayload.NationView nation = pendingNations[i];
                List<ChunkPos> chunks = chunksOf(i);
                if (chunks.isEmpty()) {
                    continue;
                }
                // The union of the chunks of the nation: one shape (plus its
                // enclaves as holes) for each connected piece of land.
                List<MapPolygonWithHoles> polygons =
                        PolygonHelper.createChunksPolygon(chunks, pendingPlayerY);
                for (MapPolygonWithHoles polygon : polygons) {
                    ShapeProperties shape = new ShapeProperties()
                            .setFillColor(nation.color())
                            .setFillOpacity(FILL_OPACITY)
                            .setStrokeOpacity(0f);
                    PolygonOverlay overlay = new PolygonOverlay(MOD_ID, dimension, shape, polygon);
                    overlay.setTitle(nation.name());
                    overlay.setLabel(nation.name());
                    show(api, overlay, i);
                    shapes++;
                }
            }

            // Country fills can be cropped by the server sync radius. Only real
            // edges get a stroke, including those on the far side of the crop.
            for (var edge : NationMapData.borders()) {
                showBorder(api, dimension, edge);
            }

            for (var battle : NationMapData.battles()) {
                showBattle(api, dimension, battle);
            }
            for (long key : NationMapData.oilFields()) {
                showOilField(api, dimension, new ChunkPos(key));
            }
            published = true;
            LOGGER.info("{} JOURNEYMAP territories drawn: {} shapes from {} nations",
                    ModServerCommands.SELFTEST_MARKER, shapes, pendingNations.length);
        } catch (Throwable error) {
            logNotReady("the shapes could not be drawn", error);
        }
    }

    /** Chunks of a nation (the index of the nation in the payload). */
    private static List<ChunkPos> chunksOf(int nationIndex) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (NationMapPayload.ClaimView claim : pendingClaims) {
            if (claim.nationIndex() == nationIndex) {
                chunks.add(new ChunkPos(claim.chunkX(), claim.chunkZ()));
            }
        }
        return chunks;
    }

    private static void logNotReady(String reason, Throwable error) {
        if (attempts == 1 || attempts % 100 == 0) {
            if (error == null) {
                LOGGER.info("{} JOURNEYMAP not ready yet ({}), will retry",
                        ModServerCommands.SELFTEST_MARKER, reason);
            } else {
                LOGGER.info("{} JOURNEYMAP not ready yet ({}), will retry: {}",
                        ModServerCommands.SELFTEST_MARKER, reason, error.toString());
            }
        }
    }

    private static void showBorder(IClientAPI api, ResourceKey<Level> dimension,
                                   NationMapData.Border edge) throws Exception {
        int x = edge.x() * 16;
        int z = edge.z() * 16;
        int x2 = edge.horizontal() ? x + 16 * edge.length() : x + 1;
        int z2 = edge.horizontal() ? z + 1 : z + 16 * edge.length();
        int x1 = edge.horizontal() ? x : x - 1;
        int z1 = edge.horizontal() ? z - 1 : z;
        int y = pendingPlayerY;
        MapPolygon strip = new MapPolygon(new BlockPos(x1, y, z1), new BlockPos(x2, y, z1),
                new BlockPos(x2, y, z2), new BlockPos(x1, y, z2));
        int rgb = edge.color() & 0xFFFFFF;
        ShapeProperties shape = new ShapeProperties().setFillColor(rgb).setFillOpacity(1f)
                .setStrokeColor(rgb).setStrokeOpacity(1f).setStrokeWidth(1f);
        show(api, new PolygonOverlay(MOD_ID, dimension, shape, strip), pendingNations.length);
    }

    private static void showBattle(IClientAPI api, ResourceKey<Level> dimension, DisputeMapPayload.Battle battle) throws Exception {
        int x = battle.x() * 16;
        int z = battle.z() * 16;
        var origin = new BlockPos(x, pendingPlayerY, z);
        var tile = new MapPolygon(origin, origin.offset(16, 0, 0), origin.offset(16, 0, 16), origin.offset(0, 0, 16));
        var red = new ShapeProperties().setFillColor(0xE73B49).setFillOpacity(0.82f)
                .setStrokeColor(0xFF6469).setStrokeOpacity(1f).setStrokeWidth(2f);
        var area = new PolygonOverlay(MOD_ID, dimension, red, tile);
        String title = I18n.get("map.modserver.battle", battle.challenger(), battle.defender());
        area.setTitle(title);
        show(api, area, pendingNations.length + 1);
        // A cross is readable over country colours and does not depend on hue alone.
        var cross = new MapPolygon(origin.offset(2,0,4), origin.offset(4,0,2), origin.offset(8,0,6),
                origin.offset(12,0,2), origin.offset(14,0,4), origin.offset(10,0,8), origin.offset(14,0,12),
                origin.offset(12,0,14), origin.offset(8,0,10), origin.offset(4,0,14), origin.offset(2,0,12), origin.offset(6,0,8));
        var ink = new ShapeProperties().setFillColor(0xFFF1DA).setFillOpacity(1f).setStrokeOpacity(0f);
        var mark = new PolygonOverlay(MOD_ID, dimension, ink, cross);
        mark.setTitle(title);
        show(api, mark, pendingNations.length + 2);
    }

    private static void showOilField(IClientAPI api, ResourceKey<Level> dimension, ChunkPos chunk) throws Exception {
        BlockPos corner = new BlockPos(chunk.getMinBlockX(), pendingPlayerY, chunk.getMinBlockZ());
        var bounds = new MapPolygon(corner.offset(1, 0, 1), corner.offset(15, 0, 1),
                corner.offset(15, 0, 15), corner.offset(1, 0, 15));
        var outline = new ShapeProperties().setFillColor(OilMapMarker.GOLD & 0xFFFFFF).setFillOpacity(.16f)
                .setStrokeColor(OilMapMarker.GOLD & 0xFFFFFF).setStrokeOpacity(1).setStrokeWidth(2);
        var area = new PolygonOverlay(MOD_ID, dimension, outline, bounds);
        String name = I18n.get("map.modserver.oil");
        area.setTitle(name);
        show(api, area, pendingNations.length + 1, Context.UI.Fullscreen);

        var icon = new MapImage(OilMapMarker.ICON, OilMapMarker.WIDTH, OilMapMarker.HEIGHT)
                .setDisplayWidth(36).setDisplayHeight(43).setAnchorX(18).setAnchorY(43).setBlur(false);
        var marker = new MarkerOverlay(MOD_ID, corner.offset(8, 0, 8), icon);
        marker.setDimension(dimension).setTitle(I18n.get("gov.oil.field")).setLabel(name);
        marker.setTextProperties(new TextProperties().setScale(1.75f).setColor(0xFFF0BC).setBackgroundColor(0x342D22)
                .setBackgroundOpacity(.94f).setOffsetY(-34));
        show(api, marker, pendingNations.length + 3, Context.UI.Fullscreen);
    }

    private static void show(IClientAPI api, Overlay overlay, int order) throws Exception {
        show(api, overlay, order, Context.UI.Minimap, Context.UI.Fullscreen);
    }

    private static void show(IClientAPI api, Overlay overlay, int order, Context.UI... uis) throws Exception {
        overlay.setOverlayGroupName(GROUP_NAME);
        overlay.setDisplayOrder(order);
        overlay.setActiveUIs(uis);
        overlay.setActiveMapTypes(Context.MapType.Day, Context.MapType.Night,
                Context.MapType.Topo, Context.MapType.Biome, Context.MapType.Underground);
        api.show(overlay);
    }
}
