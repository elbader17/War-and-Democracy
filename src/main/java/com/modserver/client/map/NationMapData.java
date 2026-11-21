package com.modserver.client.map;

import com.modserver.command.ModServerCommands;
import com.modserver.network.NationMapPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Client cache of the nation map data.
 *
 * <p>The server fills it through {@link NationMapPayload} and both the map screen
 * and the minimap integration read from it.</p>
 */
public final class NationMapData {

    private static final Logger LOGGER = LoggerFactory.getLogger(NationMapData.class);

    private static NationMapPayload.NationView[] nations = new NationMapPayload.NationView[0];
    private static NationMapPayload.ClaimView[] claims = new NationMapPayload.ClaimView[0];
    private static int playerY;
    private static long updatedAt;

    private NationMapData() {
    }

    public static synchronized void accept(NationMapPayload payload) {
        nations = payload.nations().toArray(new NationMapPayload.NationView[0]);
        claims = payload.claims().toArray(new NationMapPayload.ClaimView[0]);
        playerY = payload.playerY();
        updatedAt = System.currentTimeMillis();

        LOGGER.info("{} MAPDATA {} nations, {} claims, Y={}", ModServerCommands.SELFTEST_MARKER,
                nations.length, claims.length, playerY);

        // The player minimap is updated too (if it is installed).
        XaeroMapProvider.update(nations, claims, playerY);

        // And the territory is drawn as a shape in JourneyMap (if it is installed).
        JourneyMapProvider.update(nations, claims, playerY);
    }

    public static synchronized NationMapPayload.NationView[] nations() {
        return nations;
    }

    public static synchronized NationMapPayload.ClaimView[] claims() {
        return claims;
    }

    public static synchronized int playerY() {
        return playerY;
    }

    public static synchronized long updatedAt() {
        return updatedAt;
    }

    /** Claims of a nation by index. */
    public static synchronized List<NationMapPayload.ClaimView> claimsOf(int nationIndex) {
        List<NationMapPayload.ClaimView> result = new ArrayList<>();
        for (NationMapPayload.ClaimView claim : claims) {
            if (claim.nationIndex() == nationIndex) {
                result.add(claim);
            }
        }
        return result;
    }

    /** Number of chunks of a nation according to the received data. */
    public static synchronized int chunkCount(int nationIndex) {
        int total = 0;
        for (NationMapPayload.ClaimView claim : claims) {
            if (claim.nationIndex() == nationIndex) {
                total++;
            }
        }
        return total;
    }
}
