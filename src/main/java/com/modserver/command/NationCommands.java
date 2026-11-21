package com.modserver.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.modserver.NationService;
import com.modserver.ServerLanguage;
import com.modserver.ModServer;
import com.modserver.dispute.Dispute;
import com.modserver.economy.EconomyRules;
import com.modserver.economy.EconomyService;
import com.modserver.economy.NationEconomy;
import com.modserver.nation.Nation;
import com.modserver.nation.NationRelation;
import com.modserver.nation.NationRole;
import com.modserver.network.OpenMapPayload;
import com.modserver.npc.RivalNpc;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Locale;
import java.util.Optional;

/**
 * Nations commands: {@code /nation ...}
 *
 * <p>All the messages are prefixed with {@code [nation]} so that they can be told
 * apart from the normal chat and the bot can verify them.</p>
 */
public final class NationCommands {

    public static final String PREFIX = "[nation]";

    private NationCommands() {
    }

    /** The original spellings remain available to existing players and integrations. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(rootCommand("nation", true));
        for (String alias : new String[]{"nacion", "nation", "nac"}) {
            dispatcher.register(rootCommand(alias, false));
        }
    }

    /** Builds the whole command tree under the given root name. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
    rootCommand(String name, boolean english) {
        return Commands.literal(name)
                .requires(source -> source.hasPermission(0))
                .then(Commands.literal(english ? "create" : "crear")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> create(ctx, StringArgumentType.getString(ctx, "name"),
                                        0x55FFFF))
                                .then(Commands.argument("color", IntegerArgumentType.integer())
                                        .executes(ctx -> create(ctx,
                                                StringArgumentType.getString(ctx, "name"),
                                                IntegerArgumentType.getInteger(ctx, "color"))))))
                .then(Commands.literal("claim").executes(NationCommands::claim))
                .then(Commands.literal("unclaim").executes(NationCommands::unclaim))
                .then(Commands.literal(english ? "map" : "mapa").executes(NationCommands::openMap)
                        .then(MapTerritoryCommand.branch(english ? "territory" : "territorio", english, false))
                        .then(MapTerritoryCommand.branch(english ? "select" : "seleccionar", english, true)))
                .then(Commands.literal("info").executes(NationCommands::info))
                .then(Commands.literal(english ? "list" : "lista").executes(NationCommands::list))
                .then(Commands.literal(english ? "members" : "miembros")
                        .then(Commands.literal("add")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> addMember(ctx, StringArgumentType.getString(ctx, "player")))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> removeMember(ctx,
                                                StringArgumentType.getString(ctx, "player"))))))
                .then(Commands.literal("color")
                        .then(Commands.argument("color", IntegerArgumentType.integer(0, 0xFFFFFF))
                                .executes(ctx -> setColor(ctx, IntegerArgumentType.getInteger(ctx, "color")))))
                .then(Commands.literal(english ? "agreement" : "acuerdo")
                        .then(Commands.argument("nation", StringArgumentType.word())
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .executes(ctx -> agreement(ctx,
                                                StringArgumentType.getString(ctx, "nation"),
                                                StringArgumentType.getString(ctx, "type"))))))
                .then(Commands.literal(english ? "dispute" : "disputar")
                        .executes(ctx -> dispute(ctx, null, null))
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> dispute(ctx,
                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                IntegerArgumentType.getInteger(ctx, "z"))))))
                .then(Commands.literal(english ? "defend" : "defender")
                        .executes(ctx -> defend(ctx, null, null))
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> defend(ctx,
                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                IntegerArgumentType.getInteger(ctx, "z"))))))
                .then(Commands.literal(english ? "economy" : "economia")
                        .executes(NationCommands::economy)
                        .then(Commands.literal(english ? "invest" : "invertir")
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .executes(ctx -> invest(ctx,
                                                StringArgumentType.getString(ctx, "type"))))))
                .then(Commands.literal(english ? "defense" : "defensa")
                        .executes(NationCommands::defense)
                        .then(Commands.literal(english ? "pay" : "pagar").executes(NationCommands::payDefense)))
                .then(Commands.literal(english ? "disputes" : "disputas").executes(NationCommands::disputes))
                .then(Commands.literal(english ? "war" : "guerra")
                        .then(Commands.argument("nation", StringArgumentType.word())
                                .executes(ctx -> agreement(ctx, StringArgumentType.getString(ctx, "nation"),
                                        "war"))))
                .then(Commands.literal(english ? "peace" : "paz")
                        .then(Commands.argument("nation", StringArgumentType.word())
                                .executes(ctx -> agreement(ctx, StringArgumentType.getString(ctx, "nation"),
                                        "neutral"))))
                .then(Commands.literal(english ? "alliance" : "alianza")
                        .then(Commands.argument("nation", StringArgumentType.word())
                                .executes(ctx -> agreement(ctx, StringArgumentType.getString(ctx, "nation"),
                                        "ally"))))
                .then(Commands.literal(english ? "pact" : "pacto")
                        .then(Commands.argument("nation", StringArgumentType.word())
                                .executes(ctx -> agreement(ctx, StringArgumentType.getString(ctx, "nation"),
                                        "pact"))))
                .then(Commands.literal(english ? "attack" : "atacar")
                        .executes(ctx -> attack(ctx, null, null))
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> attack(ctx,
                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                IntegerArgumentType.getInteger(ctx, "z"))))))
                .then(RivalNpc.interactionCommands(english))
                .then(adminCommand());
    }

    /** Admin subcommand: {@code /nation admin resolve <id> keep|transfer}. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
    adminCommand() {
        var admin = Commands.literal("admin");
        admin.requires(source -> source.hasPermission(2));
        admin.then(RivalNpc.createCommand());

        var resolve = Commands.literal("resolve");
        var id = Commands.argument("id", IntegerArgumentType.integer(1));
        var decision = Commands.argument("decision", StringArgumentType.word());

        decision.executes(ctx -> resolve(ctx,
                IntegerArgumentType.getInteger(ctx, "id"),
                StringArgumentType.getString(ctx, "decision")));
        id.then(decision);
        resolve.then(id);
        admin.then(resolve);

        // /nation admin economy tick | balance <nation> <coins>
        var economy = Commands.literal("economy");
        var tick = Commands.literal("tick");
        tick.executes(NationCommands::adminEconomyTick);
        var balance = Commands.literal("balance");
        var nation = Commands.argument("nation", StringArgumentType.word());
        var amount = Commands.argument("coins", DoubleArgumentType.doubleArg(0));
        amount.executes(ctx -> adminBalance(ctx,
                StringArgumentType.getString(ctx, "nation"),
                DoubleArgumentType.getDouble(ctx, "coins")));
        nation.then(amount);
        balance.then(nation);
        economy.then(tick);
        economy.then(balance);
        admin.then(economy);

        return admin;
    }

    /** Forces the economic day close. */
    private static int adminEconomyTick(CommandContext<CommandSourceStack> ctx) {
        NationService.Result result = ModServer.nations().adminEconomyTick();
        send(ctx, result.message(), result.success());
        return result.success() ? 1 : 0;
    }

    /** Adjusts the balance of a nation. */
    private static int adminBalance(CommandContext<CommandSourceStack> ctx, String nationName,
                                     double coins) {
        NationService.Result result = ModServer.nations().adminSetBalance(nationName, coins);
        send(ctx, result.message(), result.success());
        return result.success() ? 1 : 0;
    }

    // ----------------------------------------------------------------- actions
    private static int create(CommandContext<CommandSourceStack> ctx, String name, int color) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx, "Only a player can use this command", false);
            return 0;
        }
        NationService.Result result = ModServer.nations().createNation(player, name, color);
        send(ctx, result.message(), result.success());
        return result.success() ? 1 : 0;
    }

    private static int claim(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx, "Only a player can use this command", false);
            return 0;
        }
        NationService.Result result = ModServer.nations().claimChunk(player);
        send(ctx, result.message(), result.success());
        return result.success() ? 1 : 0;
    }

    private static int unclaim(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx, "Only a player can use this command", false);
            return 0;
        }
        NationService.Result result = ModServer.nations().unclaimChunk(player);
        send(ctx, result.message(), result.success());
        return result.success() ? 1 : 0;
    }

    /**
     * Sends the map data to the player and asks it to open the screen.
     *
     * <p>The command belongs to the server on purpose: if it were registered as a
     * client command, the client dispatcher would swallow the whole {@code /nation}
     * and the server subcommands (create, claim...) would stop working.</p>
     */
    private static int openMap(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx, "Only a player can use this command", false);
            return 0;
        }
        if (!ModServer.supportsMapPayload(player) || !ModServer.supportsOpenMapPayload(player)) {
            player.sendSystemMessage(TerritoryChatMap.render(ModServer.nations(),
                    NationService.dimensionId(player), player.chunkPosition(), ServerLanguage.of(player)));
            return 1;
        }
        ModServer.sendMap(player);
        PacketDistributor.sendToPlayer(player, OpenMapPayload.instance());
        send(ctx, "Opening the nations map", true);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx, "Only a player can use this command", false);
            return 0;
        }
        Optional<Nation> nation = ModServer.nations().actingNation(player.getUUID());
        if (nation.isEmpty()) {
            send(ctx, "You do not have a nation. Use /nation create <name>", false);
            return 0;
        }
        Nation owned = nation.get();
        int chunks = ModServer.nations().claims().chunkCount(owned.id());
        send(ctx, "Nation " + owned.name()
                + " | color #" + String.format("%06X", owned.color())
                + " | chunks " + chunks
                + " | members " + owned.memberCount()
                + " | capital " + owned.capitalX() + ", " + owned.capitalZ(), true);
        String relations = describeRelations(owned);
        if (!relations.isEmpty()) {
            send(ctx, "Relations: " + relations, true);
        }
        return 1;
    }

    /** Summary of the diplomatic relations of a nation. */
    private static String describeRelations(Nation nation) {
        StringBuilder text = new StringBuilder();
        for (var entry : nation.relations().entrySet()) {
            Nation other = ModServer.nations().nations().byId(entry.getKey()).orElse(null);
            if (other == null) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append(other.name()).append(" ")
                    .append(NationService.describeRelation(entry.getValue()));
        }
        return text.toString();
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        var nations = ModServer.nations().allNations();
        if (nations.isEmpty()) {
            send(ctx, "There are no nations yet", true);
            return 1;
        }
        send(ctx, "Nations (" + nations.size() + "):", true);
        for (Nation nation : nations) {
            int chunks = ModServer.nations().claims().chunkCount(nation.id());
            send(ctx, "- " + nation.name() + " #" + String.format("%06X", nation.color())
                    + " (" + chunks + " chunks, " + nation.memberCount() + " members)"
                    + relationsSuffix(nation), true);
        }
        return 1;
    }

    /** Suffix with the wars of a nation (only the relevant ones are shown). */
    private static String relationsSuffix(Nation nation) {
        StringBuilder wars = new StringBuilder();
        for (var entry : nation.relations().entrySet()) {
            if (entry.getValue() != NationRelation.WAR) {
                continue;
            }
            Nation other = ModServer.nations().nations().byId(entry.getKey()).orElse(null);
            if (other == null) {
                continue;
            }
            wars.append(wars.isEmpty() ? " | at war with " : ", ").append(other.name());
        }
        return wars.toString();
    }

    private static int addMember(CommandContext<CommandSourceStack> ctx, String playerName) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx, "Only a player can use this command", false);
            return 0;
        }
        Optional<Nation> nation = ModServer.nations().nationOf(player.getUUID());
        if (nation.isEmpty()) {
            send(ctx, "You do not have a nation", false);
            return 0;
        }
        Nation owned = nation.get();
        if (!owned.roleOf(player.getUUID()).canManageMembers()) {
            send(ctx, "Only the owner or an officer can add members", false);
            return 0;
        }
        ServerPlayer target = ctx.getSource().getServer().getPlayerList().getPlayerByName(playerName);
        if (target == null) {
            send(ctx, "The player " + playerName + " is not connected", false);
            return 0;
        }
        if (owned.isMember(target.getUUID())) {
            send(ctx, target.getGameProfile().getName() + " is already a member of " + owned.name(), false);
            return 0;
        }
        owned.setRole(target.getUUID(), NationRole.MEMBER);
        ModServer.nations().markDirty();
        send(ctx, target.getGameProfile().getName() + " is now a member of " + owned.name(), true);
        return 1;
    }

    private static int removeMember(CommandContext<CommandSourceStack> ctx, String playerName) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx, "Only a player can use this command", false);
            return 0;
        }
        Optional<Nation> nation = ModServer.nations().nationOf(player.getUUID());
        if (nation.isEmpty()) {
            send(ctx, "You do not have a nation", false);
            return 0;
        }
        Nation owned = nation.get();
        if (!owned.roleOf(player.getUUID()).canManageMembers()) {
            send(ctx, "Only the owner or an officer can remove members", false);
            return 0;
        }
        ServerPlayer target = ctx.getSource().getServer().getPlayerList().getPlayerByName(playerName);
        if (target == null) {
            send(ctx, "The player " + playerName + " is not connected", false);
            return 0;
        }
        NationRole removed = owned.removeMember(target.getUUID());
        if (removed == null) {
            send(ctx, target.getGameProfile().getName() + " is not a member of " + owned.name(), false);
            return 0;
        }
        ModServer.nations().markDirty();
        send(ctx, target.getGameProfile().getName() + " is no longer a member of " + owned.name(), true);
        return 1;
    }

    private static int setColor(CommandContext<CommandSourceStack> ctx, int color) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx, "Only a player can use this command", false);
            return 0;
        }
        Optional<Nation> nation = ModServer.nations().nationOf(player.getUUID());
        if (nation.isEmpty()) {
            send(ctx, "You do not have a nation", false);
            return 0;
        }
        Nation owned = nation.get();
        owned.setColor(color & 0xFFFFFF);
        ModServer.nations().markDirty();
        send(ctx, "Color of " + owned.name() + " is now #" + String.format("%06X", owned.color()), true);
        return 1;
    }

    private static int agreement(CommandContext<CommandSourceStack> ctx, String otherName, String type) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx, "Only a player can use this command", false);
            return 0;
        }
        NationRelation relation;
        try {
            relation = NationRelation.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            send(ctx, "Unknown agreement type: " + type + " (pact, ally, war, neutral)", false);
            return 0;
        }
        NationService.Result result = ModServer.nations().setRelation(player, otherName, relation);
        send(ctx, result.message(), result.success());
        return result.success() ? 1 : 0;
    }

    // ---------------------------------------------------------------- disputes
    private static int dispute(CommandContext<CommandSourceStack> ctx, Integer chunkX, Integer chunkZ) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx, "Only a player can use this command", false);
            return 0;
        }
        NationService.Result result = ModServer.nations().openDispute(player, chunkX, chunkZ);
        send(ctx, result.message(), result.success());
        return result.success() ? 1 : 0;
    }

    private static int defend(CommandContext<CommandSourceStack> ctx, Integer chunkX, Integer chunkZ) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx, "Only a player can use this command", false);
            return 0;
        }
        NationService.Result result = ModServer.nations().defendDispute(player, chunkX, chunkZ);
        send(ctx, result.message(), result.success());
        return result.success() ? 1 : 0;
    }

    private static int disputes(CommandContext<CommandSourceStack> ctx) {
        var all = ModServer.nations().allDisputes();
        if (all.isEmpty()) {
            send(ctx, "There are no disputes", true);
            return 1;
        }
        send(ctx, "Disputes (" + all.size() + "):", true);
        for (Dispute dispute : all) {
            String retadora = nationName(dispute.challengerNationId());
            String defensora = nationName(dispute.defenderNationId());
            StringBuilder line = new StringBuilder("- #").append(dispute.id())
                    .append(" chunk ").append(dispute.chunkX()).append(", ").append(dispute.chunkZ())
                    .append(" | ").append(retadora).append(" -> ").append(defensora)
                    .append(" | ").append(dispute.status());
            if (dispute.status().isOpen()) {
                line.append(" | ").append(dispute.remainingSeconds(ctx.getSource().getServer().overworld().getGameTime()))
                        .append(" s left");
            }
            send(ctx, line.toString(), true);
        }
        return 1;
    }

    private static int resolve(CommandContext<CommandSourceStack> ctx, int id, String decision) {
        boolean keep;
        if (decision.equalsIgnoreCase("keep")) {
            keep = true;
        } else if (decision.equalsIgnoreCase("transfer")) {
            keep = false;
        } else {
            send(ctx, "Unknown decision: " + decision + " (keep or transfer)", false);
            return 0;
        }
        NationService.Result result = ModServer.nations().resolveDispute(id, keep);
        send(ctx, result.message(), result.success());
        return result.success() ? 1 : 0;
    }

    private static String nationName(java.util.UUID id) {
        return ModServer.nations().nations().byId(id)
                .map(Nation::name)
                .orElse("unknown");
    }

    // --------------------------------------------------------------- diplomacy
    private static int attack(CommandContext<CommandSourceStack> ctx, Integer chunkX, Integer chunkZ) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx, "Only a player can use this command", false);
            return 0;
        }
        NationService.Result result = ModServer.nations().attack(player, chunkX, chunkZ);
        send(ctx, result.message(), result.success());
        return result.success() ? 1 : 0;
    }

    // ----------------------------------------------------------------- economy
    private static int economy(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx, "Only a player can use this command", false);
            return 0;
        }
        Optional<Nation> nation = ModServer.nations().nationOf(player.getUUID());
        if (nation.isEmpty()) {
            send(ctx, "You do not have a nation", false);
            return 0;
        }
        Nation owned = nation.get();
        NationEconomy eco = owned.economy();
        int chunks = ModServer.nations().claims().chunkCount(owned.id());
        EconomyRules rules = ModServer.nations().economyRules();

        long income = rules.dailyIncome(chunks, eco);
        long upkeep = rules.dailyUpkeep(chunks, eco);
        long defense = rules.defenseCost(chunks);

        send(ctx, "Economy of " + owned.name() + ":", true);
        send(ctx, "- Balance: " + EconomyService.coins(eco.balance()) + " coins", true);
        send(ctx, "- Income: " + EconomyService.coins(income) + "/day ("
                + chunks + " chunks)", true);
        send(ctx, "- Upkeep: " + EconomyService.coins(upkeep) + "/day", true);
        send(ctx, "- Defense: " + EconomyService.coins(defense) + "/day"
                + (eco.defenseDebt() > 0 ? " + debt " + EconomyService.coins(eco.defenseDebt()) : ""), true);
        send(ctx, "- Status: " + (eco.isDefended() ? "PROTECTED" : "UNPROTECTED")
                + " | garrison: " + eco.garrisonPoints() + " points", true);
        send(ctx, "- Investments: farm " + eco.farmLevel() + " | garrison "
                + eco.garrisonLevel() + " | treasury " + eco.treasuryLevel(), true);
        send(ctx, "- Attacking costs " + EconomyService.coins(rules.attackCost(false))
                + " (" + EconomyService.coins(rules.attackCost(true)) + " at war)", true);
        return 1;
    }

    private static int invest(CommandContext<CommandSourceStack> ctx, String type) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx, "Only a player can use this command", false);
            return 0;
        }
        NationService.Result result = ModServer.nations().invest(player, type);
        send(ctx, result.message(), result.success());
        return result.success() ? 1 : 0;
    }

    private static int defense(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx, "Only a player can use this command", false);
            return 0;
        }
        Optional<Nation> nation = ModServer.nations().nationOf(player.getUUID());
        if (nation.isEmpty()) {
            send(ctx, "You do not have a nation", false);
            return 0;
        }
        Nation owned = nation.get();
        long pending = ModServer.nations().economy().pendingDefenseCost(owned,
                ModServer.nations().economyRules());
        send(ctx, "Defense of " + owned.name() + ": " + pending
                + " (" + EconomyService.coins(pending) + " coins) | status: "
                + (owned.economy().isDefended() ? "PROTECTED" : "UNPROTECTED")
                + ". Pay it with /nation defense pay", true);
        return 1;
    }

    private static int payDefense(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx, "Only a player can use this command", false);
            return 0;
        }
        NationService.Result result = ModServer.nations().payDefense(player);
        send(ctx, result.message(), result.success());
        return result.success() ? 1 : 0;
    }

    private static void send(CommandContext<CommandSourceStack> ctx, String message, boolean success) {
        Component component = Component.literal(PREFIX + " " + message);
        if (success) {
            ctx.getSource().sendSuccess(() -> component, false);
        } else {
            ctx.getSource().sendFailure(component);
        }
    }
}
