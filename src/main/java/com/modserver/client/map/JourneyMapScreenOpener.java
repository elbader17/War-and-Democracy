package com.modserver.client.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModList;

/**
 * Opens the full screen map of JourneyMap (used by the automated captures).
 *
 * <p>The classes of JourneyMap live in its jar, so they are only touched when the
 * mod is installed: the calls are inside a nested class that is loaded on demand
 * (a client without JourneyMap never loads it).</p>
 */
public final class JourneyMapScreenOpener {

    /** System property that opens the JourneyMap map when the client joins. */
    public static final String AUTO_OPEN_PROPERTY = "modserver.autoOpenJourneyMap";

    private static boolean opened;

    private JourneyMapScreenOpener() {
    }

    /**
     * Prepares the full screen map of JourneyMap.
     *
     * <p>On the first launch JourneyMap shows its welcome screen: it is closed
     * and the map is opened right after.</p>
     *
     * @return true if the map was opened in this call
     */
    public static boolean tick(Minecraft mc) {
        if (!ModList.get().isLoaded(JourneyMapProvider.JOURNEYMAP_MOD_ID)) {
            return false;
        }
        try {
            return ScreenHolder.tick(mc);
        } catch (Throwable error) {
            com.modserver.command.ModServerCommands.LOGGER.warn(
                    "Could not open the JourneyMap full screen map", error);
            return false;
        }
    }

    /** Holder of the JourneyMap classes: it is loaded only when they are needed. */
    private static final class ScreenHolder {
        private ScreenHolder() {
        }

        static boolean tick(Minecraft mc) {
            Screen screen = mc.screen;
            if (screen instanceof journeymap.client.ui.dialog.about.AboutScreen) {
                // Welcome screen of JourneyMap: close it and wait for the next tick.
                mc.setScreen(null);
                return false;
            }
            if (opened || screen != null) {
                return false;
            }
            mc.setScreen(new journeymap.client.ui.fullscreen.Fullscreen());
            opened = true;
            com.modserver.command.ModServerCommands.LOGGER.info(
                    "{} JOURNEYMAP full screen map opened",
                    com.modserver.command.ModServerCommands.SELFTEST_MARKER);
            return true;
        }
    }
}
