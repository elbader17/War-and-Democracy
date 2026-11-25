package com.modserver.network;

import com.modserver.ModServer;
import com.modserver.defense.DefenseDesk;
import com.modserver.client.network.ModServerClientNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registration of the mod packets.
 *
 * <p>The protocol version ("1") must be bumped if the payload format changes.</p>
 *
 * <p><b>Important restriction:</b> this class is also loaded on the dedicated server,
 * so it cannot contain any reference to Minecraft client classes ({@code Screen},
 * {@code Minecraft}, ...). The JVM verifier loads {@code Screen} as soon as it has to
 * check that a screen is assignable to {@code Screen} (for example when invoking
 * {@code Minecraft.setScreen(Screen)}), and NeoForge aborts the server startup if a
 * client class is loaded there.</p>
 *
 * <p>That is why the client work lives in {@link ModServerClientNetworking}, and here it
 * is only invoked with simple static calls (no assignability checks) from handlers that
 * never run on the server.</p>
 */
@EventBusSubscriber(modid = ModServer.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class ModServerNetworking {

    private static final String PROTOCOL_VERSION = "1";

    private ModServerNetworking() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        // "optional": it lets clients without the mod join (for example the test bot,
        // which speaks the vanilla protocol). NeoForge aborts the negotiation if the
        // server requires a payload the client does not support.
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION).optional();
        if (net.neoforged.fml.ModList.get().isLoaded("create")) {
            registrar.playToServer(DefenseDesk.Request.TYPE, DefenseDesk.Request.CODEC, (order, ctx) -> DefenseDesk.handle((ServerPlayer)ctx.player(),order));
            registrar.playToClient(DefenseDesk.View.TYPE, DefenseDesk.View.CODEC, (view, ctx) -> ModServerClientNetworking.defense(view));
        }
        registrar.versioned("2").playToClient(MissileFlight.TYPE, MissileFlight.CODEC,
                (flight, context) -> ModServerClientNetworking.missile(flight, false));
        registrar.playToClient(MissileFlight.Launch.TYPE, MissileFlight.Launch.CODEC,
                (launch, context) -> ModServerClientNetworking.missile(launch.flight(), true));
        registrar.versioned("7").playToClient(TacticalBoard.TYPE, TacticalBoard.CODEC,
                (view, context) -> ModServerClientNetworking.tactics(view));
        registrar.versioned("7").playToServer(TacticalBoard.Request.TYPE, TacticalBoard.Request.CODEC,
                (request, context) -> com.modserver.military.Tactics.request((ServerPlayer)context.player(), request));
        registrar.playToServer(GovernmentRequest.TYPE, GovernmentRequest.CODEC, (request, context) ->
                com.modserver.government.Government.request((ServerPlayer) context.player(), request.arguments()));
        registrar.playToClient(ReserveChests.TYPE, ReserveChests.CODEC,
                (view, context) -> ModServerClientNetworking.reserveChests(view));
        registrar.playToClient(CouncilView.TYPE, CouncilView.CODEC,
                (view, context) -> ModServerClientNetworking.council(view));
        registrar.playToClient(GovernmentView.TYPE, GovernmentView.CODEC,
                (view, context) -> ModServerClientNetworking.government(view));
        registrar.playToClient(OilMapPayload.TYPE, OilMapPayload.CODEC,
                (snapshot, context) -> ModServerClientNetworking.handleMapData(snapshot));
        registrar.versioned("3").playToClient(DisputeMapPayload.TYPE, DisputeMapPayload.STREAM_CODEC,
                (payload, context) -> ModServerClientNetworking.handleMapData(payload));
        registrar.playToClient(MapTerritoryInfo.TYPE, MapTerritoryInfo.STREAM_CODEC,
                (payload, context) -> ModServerClientNetworking.territoryInfo(payload));

        registrar.playToServer(RequestMapPayload.TYPE, RequestMapPayload.STREAM_CODEC,
                (payload, context) -> ModServer.sendMap((ServerPlayer) context.player()));

        registrar.playToClient(NationMapPayload.TYPE, NationMapPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) {
                        ModServerClientNetworking.handleMapData(payload);
                    }
                }));

        registrar.playToClient(OpenMapPayload.TYPE, OpenMapPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) {
                        ModServerClientNetworking.openMap();
                    }
                }));

        registrar.playToClient(BorderedMapPayload.TYPE, BorderedMapPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) {
                        ModServerClientNetworking.handleMapData(payload);
                    }
                }));
    }
}
