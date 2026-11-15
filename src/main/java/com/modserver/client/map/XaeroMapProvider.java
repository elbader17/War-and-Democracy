package com.modserver.client.map;

import com.modserver.network.NationMapPayload;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Safe entry point of the Xaero's Minimap integration.
 *
 * <p>The check lives here (and not in the class that uses the Xaero APIs) so that the
 * server and clients <b>without</b> Xaero installed never load its classes.</p>
 */
public final class XaeroMapProvider {

    public static final String XAERO_MOD_ID = "xaerominimap";

    private static final Logger LOGGER = LoggerFactory.getLogger("MODSERVER/XAERO");

    private XaeroMapProvider() {
    }

    /** Updates the minimap markers. Does nothing if Xaero is not installed. */
    public static void update(NationMapPayload.NationView[] nations,
                              NationMapPayload.ClaimView[] claims,
                              int playerY) {
        if (!ModList.get().isLoaded(XAERO_MOD_ID)) {
            LOGGER.debug("Xaero's Minimap is not installed: no markers are published");
            return;
        }
        try {
            XaeroBridge.update(nations, claims, playerY);
        } catch (Throwable error) {
            LOGGER.warn("Could not update the Xaero markers", error);
        }
    }

    /**
     * Retries publishing the pending markers.
     *
     * <p>Called on every client tick: Xaero may finish initializing its HUD
     * session after the map data has arrived.</p>
     */
    public static void retry() {
        if (!ModList.get().isLoaded(XAERO_MOD_ID)) {
            return;
        }
        try {
            XaeroBridge.retryIfPending();
        } catch (Throwable error) {
            LOGGER.warn("Could not retry the Xaero markers", error);
        }
    }
}
