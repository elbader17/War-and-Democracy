package com.modserver;

import com.modserver.military.TroopCounts;
import com.modserver.block.ModServerBlocks;
import com.modserver.command.ModServerCommands;
import com.modserver.command.NationCommands;
import com.modserver.config.ModServerConfig;
import com.modserver.data.ModServerDataGen;
import com.modserver.gametest.ModServerDisputeGameTests;
import com.modserver.gametest.ModServerEconomyGameTests;
import com.modserver.gametest.ModServerGameTests;
import com.modserver.item.ModServerItems;
import com.modserver.hud.NationSidebar;
import com.modserver.hud.WarHud;
import com.modserver.npc.RivalNpc;
import com.modserver.network.BorderedMapPayload;
import com.modserver.network.DisputeMapPayload;
import com.modserver.network.NationMapPayload;
import com.modserver.network.OpenMapPayload;
import com.modserver.network.OilMapPayload;
import com.modserver.petroleum.OilFields;
import com.modserver.petroleum.Petroleum;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.io.IOException;

/**
 * Entry point of the "modserver" mod for Minecraft 1.21.1 with NeoForge 21.1.x.
 *
 * <p>Development base of the nations mod: it centralizes the registration of blocks,
 * items, tabs, commands, data and game tests (GameTest).</p>
 */
@Mod(ModServer.MOD_ID)
public class ModServer {

    public static final String MOD_ID = "modserver";

    /** ticks between automatic saves (6000 ticks = 5 minutes). */
    private static final int AUTOSAVE_INTERVAL_TICKS = 6000;
    /** ticks between map resends (20 ticks = 1 second). */
    private static final int MAP_SYNC_INTERVAL_TICKS = 20;
    /** ticks of a game day (an economy close). */
    private static final long ECONOMY_DAY_TICKS = 24000L;

    private static NationService nations = new NationService();

    private int ticksSinceSave;
    private final NationSidebar nationSidebar = new NationSidebar();
    private final WarHud warHud = new WarHud();

    /** Creates a ResourceLocation of the mod: {@code ModServer.id("cog")} -> {@code modserver:cog}. */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    /** Nations service (it only holds data on the server). */
    public static NationService nations() {
        return nations;
    }

    public ModServer(IEventBus modBus, ModContainer modContainer) {
        ModServerBlocks.BLOCKS.register(modBus);
        ModServerItems.ITEMS.register(modBus);
        ModServerItems.TABS.register(modBus);

        modContainer.registerConfig(ModConfig.Type.SERVER, ModServerConfig.SPEC);

        // RegisterCommandsEvent goes on the game bus (NeoForge.EVENT_BUS).
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.LevelEvent.Save event) -> {
            if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level
                    && level == level.getServer().overworld() && nations.isDirty()) {
                try {
                    nations.save();
                } catch (IOException e) {
                    ModServerCommands.LOGGER.error("Could not save the nation treasury", e);
                }
            }
        });
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) sendMap(player);
        });
        NeoForge.EVENT_BUS.addListener(RivalNpc::interact);
        NeoForge.EVENT_BUS.addListener(RivalNpc::join);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            com.modserver.government.Government.disconnect(event.getEntity().getUUID());
            com.modserver.military.Tactics.disconnect(event.getEntity().getUUID());
        });
        // RegisterGameTestsEvent and GatherDataEvent go on the mod bus.
        modBus.addListener(this::registerGameTests);
        modBus.addListener(ModServerDataGen::gatherData);
        // Recipes for custom items cannot be decoded by vanilla clients.
        modBus.addListener((AddPackFindersEvent event) -> event.addPackFinders(
                id("cog_demo"), PackType.SERVER_DATA, Component.literal("Cog demo"),
                PackSource.FEATURE, false, Pack.Position.TOP));
    }

    private void registerCommands(RegisterCommandsEvent event) {
        ModServerCommands.register(event.getDispatcher());
        NationCommands.register(event.getDispatcher());
    }

    private void registerGameTests(RegisterGameTestsEvent event) {
        event.register(ModServerGameTests.class);
        event.register(ModServerDisputeGameTests.class);
        event.register(ModServerEconomyGameTests.class);
        event.register(com.modserver.gametest.GovernmentGameTests.class);
        event.register(com.modserver.gametest.TacticalGameTests.class);
    }

    // ------------------------------------------------------- world lifecycle
    private void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        nations = new NationService();
        nations.setServer(server);
        try {
            nations.load(server.getWorldPath(LevelResource.ROOT).toAbsolutePath());
            ModServerCommands.LOGGER.info("Nations loaded from {} ({} nations, {} claims, {} disputes)",
                    nations.dataDirectory(), nations.nations().count(), nations.claims().total(),
                    nations.allDisputes().size());
        } catch (IOException e) {
            ModServerCommands.LOGGER.error("Could not load the nations", e);
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        nationSidebar.clear();
        warHud.clear();
        try {
            nations.save();
            ModServerCommands.LOGGER.info("Nations saved in {}", nations.dataDirectory());
        } catch (IOException e) {
            ModServerCommands.LOGGER.error("Could not save the nations", e);
        }
    }

    private void onServerTick(ServerTickEvent.Post event) {
        long gameTime = event.getServer().overworld().getGameTime();

        // Economic day close: income, reinforcements and interest.
        if (gameTime % ECONOMY_DAY_TICKS == 0) {
            nations.closeEconomyDay(gameTime);
        }

        com.modserver.military.TroopTransfer.advance(nations, gameTime);
        nations.tickBattles(event.getServer(), gameTime);
        nations.missileEffects().tick(event.getServer());
        // Older, untimed-by-duel disputes retain their original defense window.
        if (event.getServer().getTickCount() % MAP_SYNC_INTERVAL_TICKS == 0) {
            nations.expireDisputes(gameTime);
        }

        // Resend of the map data when something changes (claims, nations, agreements).
        if (event.getServer().getTickCount() % MAP_SYNC_INTERVAL_TICKS == 0) {
            com.modserver.government.ReserveAppearance.update(event.getServer());
            syncMapToAll(event.getServer());
            nationSidebar.update(event.getServer(), nations);
            warHud.update(event.getServer(), nations);
        }

        if (!nations.isDirty()) {
            ticksSinceSave = 0;
            return;
        }
        ticksSinceSave++;
        if (ticksSinceSave >= AUTOSAVE_INTERVAL_TICKS) {
            ticksSinceSave = 0;
            try {
                nations.save();
            } catch (IOException e) {
                ModServerCommands.LOGGER.error("Could not save the nations", e);
            }
        }
    }

    /** Sends the map to all connected players (if it changed). */
    public static void syncMapToAll(MinecraftServer server) {
        if (!nations.isMapDirty()) {
            return;
        }
        nations.clearMapDirty();
        for (var player : server.getPlayerList().getPlayers()) {
            sendMap(player);
        }
    }

    /**
     * The client supports our map payload.
     *
     * <p>Payloads are optional: a client without the mod (or a bot that speaks the
     * vanilla protocol) does not negotiate the channel and cannot receive it.</p>
     */
    public static boolean supportsMapPayload(ServerPlayer player) {
        return NetworkRegistry.hasChannel(player.connection, NationMapPayload.TYPE.id());
    }

    /** The client supports the command to open the map. */
    public static boolean supportsOpenMapPayload(ServerPlayer player) {
        return NetworkRegistry.hasChannel(player.connection, OpenMapPayload.TYPE.id());
    }

    /** Sends the map data to a specific player. */
    public static void sendMap(ServerPlayer player) {
        if (!supportsMapPayload(player)) {
            return;
        }
        try {
            NationMapPayload map = nations.buildMapPayload(player);
            boolean showDisputes = NetworkRegistry.hasChannel(player.connection, DisputeMapPayload.TYPE.id());
            if (showDisputes || NetworkRegistry.hasChannel(player.connection, BorderedMapPayload.TYPE.id())) {
                String dimension = NationService.dimensionId(player);
                var bordered = BorderedMapPayload.from(map, (x, z) -> {
                    var claim = nations.claims().claimAt(dimension, x, z);
                    return claim == null ? null : claim.nationId();
                });
                if (showDisputes) {
                    var battles = nations.disputes().open().stream().filter(d -> dimension.equals(d.dimension()))
                            .map(d -> {
                                var assault = d.assault();
                                var target = nations.claims().claimAt(dimension, d.chunkX(), d.chunkZ());
                                return new DisputeMapPayload.Battle(d.chunkX(), d.chunkZ(), nations.nameOf(d.challengerNationId()),
                                        nations.nameOf(d.defenderNationId()), assault == null ? 1 : d.chunkX() - assault.originX(),
                                        assault == null ? 0 : d.chunkZ() - assault.originZ(), assault == null ? new TroopCounts(1, 0, 0) : assault.forces(),
                                        target == null ? TroopCounts.EMPTY : target.forces(), assault == null ? 0 : assault.nextDuel());
                            }).toList();
                    var snapshot = new DisputeMapPayload(bordered, player.level().dimension().location(), battles);
                    if (Petroleum.enabled() && NetworkRegistry.hasChannel(player.connection, OilMapPayload.TYPE.id())) {
                        var fields = OilFields.of(player.serverLevel()).visible(map, nations.claims(), dimension,
                                nations.nationOf(player.getUUID()).map(n -> n.id()).orElse(null));
                        PacketDistributor.sendToPlayer(player, new OilMapPayload(snapshot, fields));
                    } else PacketDistributor.sendToPlayer(player, snapshot);
                } else {
                    PacketDistributor.sendToPlayer(player, bordered);
                }
            } else {
                PacketDistributor.sendToPlayer(player, map);
            }
        } catch (RuntimeException e) {
            ModServerCommands.LOGGER.warn("Could not send the map to " + player.getGameProfile().getName(), e);
        }
    }

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        // The client opens its own dispatcher: the screen must be forced on join.
        if (event.getEntity() instanceof ServerPlayer player) {
            sendMap(player);
        }
    }
}
