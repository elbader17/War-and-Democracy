package com.modserver.client.map;

import com.modserver.command.ModServerCommands;
import com.modserver.network.NationMapPayload;
import journeymap.api.client.impl.ClientAPI;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.display.Context;
import journeymap.api.v2.client.display.DisplayType;
import journeymap.api.v2.client.display.PolygonOverlay;
import journeymap.api.v2.client.model.MapPolygonWithHoles;
import journeymap.api.v2.client.model.ShapeProperties;
import journeymap.api.v2.client.util.PolygonHelper;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
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

    /** Opacity of the border of the territory. */
    private static final float STROKE_OPACITY = 0.95f;

    /** Width of the border of the territory (in blocks). */
    private static final float STROKE_WIDTH = 2.0f;

    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"));

    // Data pending publication (the latest received from the server).
    private static NationMapPayload.NationView[] pendingNations = new NationMapPayload.NationView[0];
    private static NationMapPayload.ClaimView[] pendingClaims = new NationMapPayload.ClaimView[0];
    private static int pendingPlayerY;
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

        try {
            // Remove the shapes of the previous update: the territory changes
            // every time a chunk is claimed or released.
            api.removeAll(MOD_ID, DisplayType.Polygon);

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
                            .setStrokeColor(brighten(nation.color()))
                            .setStrokeOpacity(STROKE_OPACITY)
                            .setStrokeWidth(STROKE_WIDTH)
                            .setStrokePosition(ShapeProperties.StrokePosition.CENTER);

                    PolygonOverlay overlay =
                            new PolygonOverlay(MOD_ID, OVERWORLD, shape, polygon.hull);
                    overlay.setHoles(polygon.holes);
                    overlay.setTitle(nation.name());
                    overlay.setLabel(nation.name());
                    overlay.setOverlayGroupName(GROUP_NAME);
                    overlay.setDisplayOrder(i);
                    overlay.setMinZoom(0);
                    overlay.setMaxZoom(10);
                    overlay.setActiveUIs(Context.UI.Minimap, Context.UI.Fullscreen);
                    overlay.setActiveMapTypes(Context.MapType.Day, Context.MapType.Night,
                            Context.MapType.Topo, Context.MapType.Biome, Context.MapType.Underground);
                    api.show(overlay);
                    shapes++;
                }
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

    private static void logNotReady(String motivo, Throwable error) {
        if (attempts == 1 || attempts % 100 == 0) {
            if (error == null) {
                LOGGER.info("{} JOURNEYMAP not ready yet ({}), will retry",
                        ModServerCommands.SELFTEST_MARKER, motivo);
            } else {
                LOGGER.info("{} JOURNEYMAP not ready yet ({}), will retry: {}",
                        ModServerCommands.SELFTEST_MARKER, motivo, error.toString());
            }
        }
    }

    /** Blends a color with white: used for the border of the territories. */
    private static int brighten(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        r = r + (255 - r) / 2;
        g = g + (255 - g) / 2;
        b = b + (255 - b) / 2;
        return (r << 16) | (g << 8) | b;
    }
}
