package com.modserver.client;

import com.modserver.command.ModServerCommands;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * Key bindings of the mod.
 *
 * <p>{@code M} opens the nation map of the mod (the full screen map with the
 * territory of every nation). The client asks the server for fresh data with
 * {@code /nacion mapa}, and the server answers with the map payload plus the
 * packet that opens the screen.</p>
 *
 * <p>The {@code M} key is also used by Xaero's full screen world map. Both maps
 * can be opened at the same time: the nation map is opened when the server
 * answers, that is, a few milliseconds later, so it ends up on top. If the
 * Xaero map stays in front, {@link #closeForeignMaps(Minecraft)} closes it.</p>
 */
@EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ModServerKeyBindings {

    /** Translation key of the map key binding. */
    public static final String MAP_KEY_NAME = "key.modserver.nation_map";

    /** Category shown in the vanilla controls screen. */
    public static final String CATEGORY = "key.categories.modserver";

    private static KeyMapping openMapKey;

    private ModServerKeyBindings() {
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        openMapKey = new KeyMapping(MAP_KEY_NAME, InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_M, CATEGORY);
        event.register(openMapKey);
    }

    /**
     * Polls the key bindings.
     *
     * @return true if the map key was pressed in this tick
     */
    public static synchronized boolean pollMapKey() {
        if (openMapKey == null) {
            return false;
        }
        while (openMapKey.consumeClick()) {
            ModServerCommands.LOGGER.info("{} KEYPRESS open nation map", ModServerCommands.SELFTEST_MARKER);
            requestMap();
            return true;
        }
        return false;
    }

    /**
     * Asks the server for the map data.
     *
     * <p>The server replies with the data and opens the screen, so the map is
     * always drawn with the current territory.</p>
     */
    private static void requestMap() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            mc.getConnection().sendCommand("nacion mapa");
        } else {
            ModServerClientNetworkingHolder.openMap();
        }
    }

    /**
     * Closes a third party full screen map (Xaero, JourneyMap) if it is the visible screen.
     *
     * <p>Only screens whose class name belongs to another mod are closed: the
     * screens of the game itself are never touched.</p>
     */
    public static void closeForeignMaps(Minecraft mc) {
        if (mc.screen == null) {
            return;
        }
        String name = mc.screen.getClass().getName().toLowerCase(Locale.ROOT);
        if (!name.contains("xaero") && !name.contains("journeymap")) {
            return;
        }
        ModServerCommands.LOGGER.info("{} CLOSE FOREIGN MAP {}", ModServerCommands.SELFTEST_MARKER, name);
        mc.setScreen(null);
    }

    /** Small holder so the class does not reference the client package from a common class. */
    private static final class ModServerClientNetworkingHolder {
        private ModServerClientNetworkingHolder() {
        }

        static void openMap() {
            com.modserver.client.network.ModServerClientNetworking.openMap();
        }
    }
}
