package com.modserver.client.map;

import com.modserver.ModServer;
import com.modserver.command.ModServerCommands;
import com.modserver.network.NationMapPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xaero.common.XaeroMinimapSession;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.world.MinimapWorld;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.WaypointRenderInfo;
import xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypointManager;
import xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypoints;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Real bridge with the third party API of Xaero's Minimap.
 *
 * <p>Separate class from {@link XaeroMapProvider} on purpose: it is only loaded when the
 * minimap is installed.</p>
 *
 * <p>API used (verified with javap on xaerominimap-neoforge-1.21.1-26.6.0):</p>
 * <pre>
 * XaeroMinimapSession.getCurrentSession()                        -&gt; HUD session
 *   .getSession(BuiltInHudModules.MINIMAP)                        -&gt; MinimapSession
 *   .getWorldManager().getCurrentWorld().getContainer()           -&gt; MinimapWorld
 *   .getThirdPartyWaypointManager().get(id)                       -&gt; waypoint set
 *   .add(id, waypoint) / .clear() / .setEnabledStateGetter(supplier)
 *   .addRenderInfoOverride(id, renderInfo)
 * </pre>
 *
 * <p>Since the minimap and the World Map share the waypoint system, the markers
 * appear in both with no extra work.</p>
 *
 * <p><b>Resilience:</b> Xaero initializes its HUD session asynchronously, so the
 * map data may arrive before the session or the world exists. In that case the data
 * is stored as "pending" and retried on every client tick
 * ({@link #retryIfPending()}) until it can be published.</p>
 */
final class XaeroBridge {

    static final Logger LOGGER = LoggerFactory.getLogger("MODSERVER/XAERO");

    /** Marker limit: the minimap slows down with thousands of them. */
    private static final int MAX_MARKERS = 300;

    // Data pending publication (the latest received from the server).
    private static NationMapPayload.NationView[] pendingNations = new NationMapPayload.NationView[0];
    private static NationMapPayload.ClaimView[] pendingClaims = new NationMapPayload.ClaimView[0];
    private static int pendingPlayerY;
    private static boolean published;
    private static int attempts;

    /**
     * Xaero only supports 21 fixed colors (no free RGB): the closest one is picked.
     *
     * <p>Values verified with {@code javap} on
     * {@code xaero.hud.minimap.waypoint.WaypointColor} (field {@code hex}): in version
     * 26.6.0 the colors MAGENTA, LIGHT_BLUE, LIME, PINK and BROWN have the same hex
     * as BLACK (0x000000), so they are left as they are to avoid picking a
     * color that Xaero would draw in black.</p>
     */
    private static final Map<WaypointColor, Integer> PALETTE = new LinkedHashMap<>();

    static {
        PALETTE.put(WaypointColor.BLACK, 0x000000);
        PALETTE.put(WaypointColor.DARK_BLUE, 0x0000AA);
        PALETTE.put(WaypointColor.DARK_GREEN, 0x00AA00);
        PALETTE.put(WaypointColor.DARK_AQUA, 0x00AAAA);
        PALETTE.put(WaypointColor.DARK_RED, 0xAA0000);
        PALETTE.put(WaypointColor.DARK_PURPLE, 0xAA00AA);
        PALETTE.put(WaypointColor.GOLD, 0xFFAA00);
        PALETTE.put(WaypointColor.GRAY, 0xAAAAAA);
        PALETTE.put(WaypointColor.DARK_GRAY, 0x555555);
        PALETTE.put(WaypointColor.BLUE, 0x5555FF);
        PALETTE.put(WaypointColor.GREEN, 0x55FF55);
        PALETTE.put(WaypointColor.AQUA, 0x55FFFF);
        PALETTE.put(WaypointColor.RED, 0xFF0000);
        PALETTE.put(WaypointColor.PURPLE, 0xFF00FF);
        PALETTE.put(WaypointColor.YELLOW, 0xFFFF55);
        PALETTE.put(WaypointColor.WHITE, 0xFFFFFF);
        PALETTE.put(WaypointColor.MAGENTA, 0x000000);
        PALETTE.put(WaypointColor.LIGHT_BLUE, 0x000000);
        PALETTE.put(WaypointColor.LIME, 0x000000);
        PALETTE.put(WaypointColor.PINK, 0x000000);
        PALETTE.put(WaypointColor.BROWN, 0x000000);
    }

    private XaeroBridge() {
    }

    /** Stores the received data and tries to publish it in the minimap. */
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
     * <p>Called by the client tick: Xaero may finish initializing its session
     * after the map data has arrived.</p>
     */
    static void retryIfPending() {
        if (!published) {
            publish();
        }
    }

    private static void publish() {
        attempts++;
        XaeroMinimapSession session = XaeroMinimapSession.getCurrentSession();
        if (session == null) {
            logNotReady("no HUD session");
            return;
        }
        MinimapSession minimapSession = session.getSession(BuiltInHudModules.MINIMAP);
        if (minimapSession == null) {
            logNotReady("no minimap session");
            return;
        }

        // The third party manager belongs to the WORLD (a standalone one cannot be
        // created: its store is internal and the waypoints would be lost). It is the
        // same manager that Xaero itself uses when collecting the waypoints to draw.
        MinimapWorld world = minimapSession.getWorldManager().getCurrentWorld();
        if (world == null || world.getContainer() == null) {
            logNotReady("no minimap world");
            return;
        }
        ThirdPartyWaypointManager manager = world.getContainer().getThirdPartyWaypointManager();
        if (manager == null) {
            logNotReady("no waypoint manager");
            return;
        }

        ThirdPartyWaypoints waypoints = manager.get(ModServer.id("nations"));
        waypoints.clear();
        // Without this switch the set may stay hidden: it is the "global switch"
        // offered by the Xaero third party API.
        waypoints.setEnabledStateGetter(() -> true);

        // Only one marker per nation (its capital): the territory itself is drawn
        // as an area in the map of the mod, not as a cloud of points in the minimap.
        int publishedCount = 0;
        for (int i = 0; i < pendingNations.length && publishedCount < MAX_MARKERS; i++) {
            NationMapPayload.NationView nation = pendingNations[i];
            int blockX = nation.capitalX() * 16 + 8;
            int blockZ = nation.capitalZ() * 16 + 8;
            String id = "capital:" + nation.name();

            WaypointColor color = nearestPaletteColor(nation.color());
            // The last boolean of the Xaero constructor is "temporary": these
            // waypoints are permanent while the nation exists.
            Waypoint waypoint = new Waypoint(blockX, pendingPlayerY, blockZ, nation.name(),
                    initials(nation.name()), color, WaypointPurpose.NORMAL, false);
            waypoints.add(id, waypoint);

            // Xaero draws third party waypoints with the render info registered
            // here: without this it uses its default color (yellow).
            WaypointRenderInfo renderInfo = new WaypointRenderInfo();
            renderInfo.setWaypointColor(color);
            renderInfo.setInitials(initials(nation.name()));
            waypoints.addRenderInfoOverride(id, renderInfo);
            publishedCount++;
        }

        published = true;
        LOGGER.info("{} XAERO waypoints published: {} capital markers from {} nations (Xaero sees {})",
                ModServerCommands.SELFTEST_MARKER, publishedCount, pendingNations.length, waypoints.getCount());
    }

    private static void logNotReady(String reason) {
        // Only the first attempts are reported: if Xaero is still not ready, there is
        // no point in repeating the message every tick.
        if (attempts == 1 || attempts % 100 == 0) {
            LOGGER.info("{} XAERO not ready yet ({}), will retry",
                    ModServerCommands.SELFTEST_MARKER, reason);
        }
    }

    /** Xaero palette color closest to the nation RGB. */
    static WaypointColor nearestPaletteColor(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        WaypointColor best = WaypointColor.WHITE;
        int bestDistance = Integer.MAX_VALUE;
        for (Map.Entry<WaypointColor, Integer> entry : PALETTE.entrySet()) {
            int value = entry.getValue();
            int dr = r - ((value >> 16) & 0xFF);
            int dg = g - ((value >> 8) & 0xFF);
            int db = b - (value & 0xFF);
            // Weighted distance: the eye is more sensitive to green.
            int distance = dr * dr * 3 + dg * dg * 4 + db * db * 2;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entry.getKey();
            }
        }
        return best;
    }

    private static String initials(String name) {
        if (name == null || name.isEmpty()) {
            return "?";
        }
        return name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
    }
}
