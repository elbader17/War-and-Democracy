package com.modserver.client;

import com.modserver.client.map.JourneyMapProvider;
import com.modserver.client.map.JourneyMapScreenOpener;
import com.modserver.client.map.NationMapScreen;
import com.modserver.client.map.NationMapData;
import com.modserver.client.map.XaeroMapProvider;
import com.modserver.command.ModServerCommands;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Client utilities: screenshot capture and automatic connection to the server.
 *
 * <p>{@code /modserver screenshot} saves a capture of the game framebuffer in
 * {@code <gameDir>/screenshots/modserver.png}. This is the most reliable way to verify
 * the game visually: the X11 root in WSLg is black, so capturing the operating
 * system screen does not work.</p>
 *
 * <p>System properties to automate the client:</p>
 * <ul>
 *   <li>{@code modserver.autoScreenshot=true}: capture on joining the world.</li>
 *   <li>{@code modserver.autoScreenshotDelay=100}: ticks to wait (default 100).</li>
 *   <li>{@code modserver.autoJoin=127.0.0.1:25565}: connect only to that server.</li>
 *   <li>{@code modserver.autoOpenMap=true}: open the nation map on joining.</li>
 *   <li>{@code modserver.autoQuit=true}: close the game once the capture is done.</li>
 * </ul>
 */
@EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class ModServerClientCommands {

    /** Fixed file name so the automation always knows where to look. */
    public static final String SCREENSHOT_NAME = "modserver.png";

    public static final String AUTO_SCREENSHOT_PROPERTY = "modserver.autoScreenshot";
    public static final String AUTO_SCREENSHOT_DELAY_PROPERTY = "modserver.autoScreenshotDelay";
    public static final String AUTO_JOIN_PROPERTY = "modserver.autoJoin";
    public static final String AUTO_OPEN_MAP_PROPERTY = "modserver.autoOpenMap";
    public static final String AUTO_OPEN_MAP_DELAY_PROPERTY = "modserver.autoOpenMapDelay";
    public static final String AUTO_QUIT_PROPERTY = "modserver.autoQuit";
    public static final String AUTO_COMMANDS_PROPERTY = "modserver.autoCommands";

    private static int pendingScreenshotTicks = -1;
    private static int pendingOpenMapTicks = -1;
    private static int pendingTerrainMapTicks = -1;
    private static String[] pendingCommands = new String[0];
    private static int pendingCommandTicks = -1;
    private static boolean autoJoinPending;
    private static boolean autoJoinDone;

    private ModServerClientCommands() {
    }

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("modserver")
                .then(Commands.literal("screenshot").executes(ModServerClientCommands::screenshot)));
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (Boolean.getBoolean(JourneyMapScreenOpener.AUTO_OPEN_PROPERTY)) {
            pendingTerrainMapTicks = Integer.getInteger(AUTO_OPEN_MAP_DELAY_PROPERTY, 60);
        }
        if (Boolean.getBoolean(AUTO_SCREENSHOT_PROPERTY)) {
            pendingScreenshotTicks = Integer.getInteger(AUTO_SCREENSHOT_DELAY_PROPERTY, 100);
        }
        if (Boolean.getBoolean(AUTO_OPEN_MAP_PROPERTY)) {
            pendingOpenMapTicks = Integer.getInteger(AUTO_OPEN_MAP_DELAY_PROPERTY, 40);
        }
        String commands = System.getProperty(AUTO_COMMANDS_PROPERTY);
        if (commands != null && !commands.isBlank()) {
            pendingCommands = commands.split(";");
            pendingCommandTicks = 20;
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        NationMapData.clear();
        ReserveChestRenderer.show(null);
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        ModServerKeyBindings.pollMapKey(mc);

        // Retry publishing markers to third party minimaps: Xaero may initialize
        // after the map data has arrived.
        XaeroMapProvider.retry();

        // The same for the territories drawn in JourneyMap.
        JourneyMapProvider.retry();

        // Automatic opening of the full screen map of JourneyMap (captures).
        if (pendingTerrainMapTicks >= 0 && mc.player != null && pendingTerrainMapTicks-- == 0) {
            JourneyMapScreenOpener.open(mc);
        }

        // Automatic connection to the development server (vanilla quickPlay never
        // runs in the NeoForge development environment).
        if (!autoJoinDone && System.getProperty(AUTO_JOIN_PROPERTY) != null) {
            if (autoJoinPending) {
                autoJoinPending = false;
                autoJoinDone = true;
                joinServer(mc, System.getProperty(AUTO_JOIN_PROPERTY));
            } else if (mc.getOverlay() == null) {
                autoJoinPending = true;
            }
        }

        if (pendingCommandTicks > 0) {
            pendingCommandTicks--;
            if (pendingCommandTicks == 0) {
                pendingCommandTicks = -1;
                runPendingCommands(mc);
            }
        }

        if (pendingScreenshotTicks < 0 && pendingOpenMapTicks < 0) {
            return;
        }

        if (pendingOpenMapTicks > 0) {
            pendingOpenMapTicks--;
            if (pendingOpenMapTicks == 0) {
                pendingOpenMapTicks = -1;
                NationMapScreen.open();
            }
        }

        if (pendingScreenshotTicks > 0) {
            pendingScreenshotTicks--;
            if (pendingScreenshotTicks == 0) {
                pendingScreenshotTicks = -1;
                takeScreenshot(null);
            }
        }
    }

    /** Runs the commands of the {@code modserver.autoCommands} property, one every 20 ticks. */
    private static void runPendingCommands(Minecraft mc) {
        String[] commands = pendingCommands;
        pendingCommands = new String[0];
        for (String command : commands) {
            String trimmed = command.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("/")) {
                trimmed = trimmed.substring(1);
            }
            if (mc.getConnection() != null) {
                mc.getConnection().sendCommand(trimmed);
            }
            ModServerCommands.LOGGER.info("{} AUTOCOMMAND {}", ModServerCommands.SELFTEST_MARKER, trimmed);
        }
    }

    private static void joinServer(Minecraft mc, String address) {
        try {
            ServerList serverList = new ServerList(mc);
            serverList.load();
            ServerData serverData = serverList.get(address);
            if (serverData == null) {
                serverData = new ServerData("modserver-dev", address, ServerData.Type.OTHER);
                serverList.add(serverData, true);
                serverList.save();
            }
            ModServerCommands.LOGGER.info("{} AUTOJOIN {}", ModServerCommands.SELFTEST_MARKER, address);
            ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), mc,
                    ServerAddress.parseString(address), serverData, true, null);
        } catch (Exception e) {
            ModServerCommands.LOGGER.error("{} AUTOJOIN FAIL {}", ModServerCommands.SELFTEST_MARKER, address, e);
        }
    }

    private static int screenshot(CommandContext<CommandSourceStack> ctx) {
        takeScreenshot(ctx.getSource());
        return 1;
    }

    private static void takeScreenshot(CommandSourceStack source) {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, SCREENSHOT_NAME, mc.getMainRenderTarget(), message -> {
            ModServerCommands.LOGGER.info("{} SCREENSHOT {}", ModServerCommands.SELFTEST_MARKER, message.getString());
            if (source != null) {
                source.sendSuccess(() -> Component.literal("[modserver] " + message.getString()), true);
            }
            // Clean client shutdown: let the game save and close by itself.
            if (Boolean.getBoolean(AUTO_QUIT_PROPERTY)) {
                ModServerCommands.LOGGER.info("{} AUTOQUIT", ModServerCommands.SELFTEST_MARKER);
                mc.stop();
            }
        });
    }
}
