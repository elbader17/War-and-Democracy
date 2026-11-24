package com.modserver.client.network;

import com.modserver.client.map.NationMapData;
import com.modserver.client.map.NationMapScreen;
import com.modserver.network.BorderedMapPayload;
import com.modserver.network.DisputeMapPayload;
import com.modserver.network.OilMapPayload;
import com.modserver.network.MapTerritoryInfo;
import com.modserver.client.map.TerritoryScreen;
import com.modserver.network.NationMapPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Management of the <b>client</b> side of the mod packets.
 *
 * <p>This class can never be loaded on the dedicated server: it holds references to
 * Minecraft client classes ({@link Minecraft}, {@link NationMapScreen}). The JVM
 * verifier loads {@code Screen} as soon as it has to check whether a screen is
 * assignable to {@code Screen} (for example when invoking
 * {@code Minecraft.setScreen(Screen)}), and NeoForge aborts the server startup if a
 * client class is loaded there.</p>
 *
 * <p>That is why the common code ({@code com.modserver.network.ModServerNetworking}) only
 * calls it from handlers that run exclusively on the client, and the
 * {@link OnlyIn} annotation turns any configuration error into a clear failure.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ModServerClientNetworking {

    public static void missile(com.modserver.network.MissileFlight flight, boolean focusLaunch) {
        com.modserver.client.MissileRenderer.launch(flight, focusLaunch);
    }

    public static void reserveChests(com.modserver.network.ReserveChests view) {
        com.modserver.client.ReserveChestRenderer.show(view);
    }

    public static void tactics(com.modserver.network.TacticalBoard board) {
        com.modserver.client.map.TacticalScreen.accept(board);
    }

    private ModServerClientNetworking() {
    }

    /** Caches the map data sent by the server. */
    public static void handleMapData(NationMapPayload payload) {
        NationMapData.accept(payload);
    }

    public static void handleMapData(BorderedMapPayload payload) {
        NationMapData.accept(payload);
    }

    public static void handleMapData(DisputeMapPayload payload) {
        NationMapData.accept(payload);
    }

    public static void handleMapData(OilMapPayload snapshot) {
        NationMapData.accept(snapshot);
    }

    public static void council(com.modserver.network.CouncilView view) {
        com.modserver.client.CouncilScreen.accept(view);
    }

    public static void government(com.modserver.network.GovernmentView view) {
        com.modserver.client.GovernmentScreen.accept(view);
    }

    public static void territoryInfo(MapTerritoryInfo info) {
        TerritoryScreen.accept(info);
    }

    /** Opens the nation map screen. */
    public static void openMap() {
        Minecraft.getInstance().setScreen(new NationMapScreen());
    }
}
