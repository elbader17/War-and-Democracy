package com.modserver.client.map;

import com.modserver.command.ModServerCommands;
import com.modserver.network.RequestMapPayload;
import journeymap.client.ui.UIManager;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Opens JourneyMap's terrain view. The optional mod's classes stay in the nested class. */
public final class JourneyMapScreenOpener {
    public static final String AUTO_OPEN_PROPERTY = "modserver.autoOpenJourneyMap";

    private JourneyMapScreenOpener() {
    }

    public static boolean open(Minecraft mc) {
        if (!ModList.get().isLoaded(JourneyMapProvider.JOURNEYMAP_MOD_ID) || !ScreenHolder.open()) {
            return false;
        }
        var connection = mc.getConnection();
        if (connection != null && NetworkRegistry.hasChannel(connection, RequestMapPayload.TYPE.id())) {
            PacketDistributor.sendToServer(RequestMapPayload.INSTANCE);
        }
        ModServerCommands.LOGGER.info("{} JOURNEYMAP full screen map opened", ModServerCommands.SELFTEST_MARKER);
        return true;
    }

    private static final class ScreenHolder {
        static boolean open() {
            return UIManager.INSTANCE.getOrOpenFullscreenMap() != null;
        }
    }
}
