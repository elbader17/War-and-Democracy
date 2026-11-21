package com.modserver.client.map;

import com.modserver.network.NationMapPayload;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Safe entry point of the JourneyMap integration.
 *
 * <p>The check lives here (and not in the class that uses the JourneyMap APIs) so
 * that the server and the clients <b>without</b> JourneyMap installed never load
 * its classes.</p>
 *
 * <p>JourneyMap is the most polished of the map mods and, since version 6.0.9, it
 * is the only one with a public API to draw <b>polygons</b>: the territory of a
 * nation is drawn as a shape and not as a cloud of points.</p>
 */
public final class JourneyMapProvider {

    public static final String JOURNEYMAP_MOD_ID = "journeymap";

    private static final Logger LOGGER = LoggerFactory.getLogger("MODSERVER/JOURNEYMAP");

    private JourneyMapProvider() {
    }

    /** Draws the territories. Does nothing if JourneyMap is not installed. */
    public static void update(NationMapPayload.NationView[] nations,
                              NationMapPayload.ClaimView[] claims,
                              int playerY) {
        if (!ModList.get().isLoaded(JOURNEYMAP_MOD_ID)) {
            LOGGER.debug("JourneyMap is not installed: no territory is drawn");
            return;
        }
        try {
            JourneyMapBridge.update(nations, claims, playerY);
        } catch (Throwable error) {
            LOGGER.warn("Could not draw the territories in JourneyMap", error);
        }
    }

    /**
     * Retries drawing the pending territories.
     *
     * <p>Called on every client tick: JourneyMap may not be ready when the map
     * data arrives.</p>
     */
    public static void retry() {
        if (!ModList.get().isLoaded(JOURNEYMAP_MOD_ID)) {
            return;
        }
        try {
            JourneyMapBridge.retryIfPending();
        } catch (Throwable error) {
            LOGGER.warn("Could not retry drawing the territories in JourneyMap", error);
        }
    }
}
