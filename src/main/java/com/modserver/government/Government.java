package com.modserver.government;

import com.modserver.ModServer;
import com.modserver.NationService;
import com.modserver.ServerLanguage;
import com.modserver.economy.Investment;
import com.modserver.nation.Nation;
import com.modserver.network.GovernmentView;
import com.modserver.petroleum.Petroleum;
import com.modserver.petroleum.OilFields;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class Government {
    public static final int TEXT_LIMIT = 180;
    private record Order(UUID token, UUID nation, String action, Commodity item, int quantity,
                         long unitPrice, int level, UUID seal, long deadline, String label, String location, Petroleum.Quote export) {}
    private static final Map<UUID, Order> orders = new HashMap<>();
    private static final Map<UUID, Integer> lastRefresh = new HashMap<>();
    private static void forget(UUID player) { orders.remove(player); }
    public static void disconnect(UUID player) { forget(player); lastRefresh.remove(player); ReserveAppearance.forget(player); }

    public static void request(ServerPlayer player, String arguments) {
        if (arguments.equals("refresh")) {
            int now = player.server.getTickCount();
            Integer previous = lastRefresh.get(player.getUUID());
            if (previous != null && now - previous < 20) return;
            lastRefresh.put(player.getUUID(), now);
        }
        player.server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "nation panel " + arguments);
    }

    public static LiteralArgumentBuilder<CommandSourceStack> oilCommands(String name) {
        return Commands.literal(name).executes(c -> run(c, "oil", ""))
                .then(Commands.literal("survey").executes(c -> run(c, "survey", "")))
                .then(Commands.literal("export").then(Commands.argument("buckets", IntegerArgumentType.integer(1, 1000000))
                        .executes(c -> run(c, "export", String.valueOf(IntegerArgumentType.getInteger(c, "buckets"))))));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> commands() {
        var panel = Commands.literal("panel").executes(c -> run(c, "open", ""));
        for (String action : new String[]{"refresh", "bind", "cancel", "garrison", "claim", "release"})
            panel.then(Commands.literal(action).executes(c -> run(c, action, "")));
        for (String field : new String[]{"name", "preamble", "doctrines"}) {
            panel.then(Commands.literal(field).executes(c -> run(c, field, ""))
                    .then(Commands.argument("text", StringArgumentType.greedyString()).executes(c ->
                            run(c, field, StringArgumentType.getString(c, "text")))));
        }
        var edit = Commands.literal("edit");
        for (String field : new String[]{"name", "preamble", "doctrines"})
            edit.then(Commands.literal(field).executes(c -> run(c, "edit", field)));
        panel.then(edit);
        var sell = Commands.literal("sell");
        for (Commodity item : Commodity.values()) {
            sell.then(Commands.literal(item.id()).executes(c -> run(c, "sell", item.id()))
                    .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 3456)).executes(c ->
                            run(c, "sell", item.id() + " " + IntegerArgumentType.getInteger(c, "quantity")))));
        }
        panel.then(sell);
        panel.then(oilCommands("oil"));
        panel.then(Commands.literal("confirm").then(Commands.argument("token", UuidArgument.uuid())
                .executes(c -> run(c, "confirm", UuidArgument.getUuid(c, "token").toString()))));
        return panel;
    }

    /** A direct chat command leaves the player in the world looking at the newly marked chest. */
    public static int bindReserve(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var player = ctx.getSource().getPlayerOrException();
        var country = ModServer.nations().nationOf(player.getUUID()).orElse(null);
        var lang = ServerLanguage.of(player);
        if (country == null || player.isSpectator()) {
            ctx.getSource().sendFailure(Component.literal(lang.text("gov.owner")));
            return 0;
        }
        var chest = CapitalChest.bindLookedAt(player, country);
        if (chest.inventory() == null) {
            ctx.getSource().sendFailure(Component.literal(lang.text("gov.chest." + chest.status())));
            return 0;
        }
        forget(player.getUUID());
        ReserveAppearance.update(player.server);
        player.sendSystemMessage(Component.literal(lang.text("gov.bound")));
        return 1;
    }

    private static int run(CommandContext<CommandSourceStack> c, String action, String arg) throws CommandSyntaxException {
        var player = c.getSource().getPlayerOrException();
        var service = ModServer.nations();
        var country = service.nationOf(player.getUUID()).orElse(null);
        var lang = ServerLanguage.of(player);
        if (country == null || player.isSpectator()) {
            player.sendSystemMessage(Component.literal(lang.text("gov.owner")));
            return 0;
        }
        var chest = action.equals("bind") ? CapitalChest.bindLookedAt(player, country) : CapitalChest.access(player, country);
        var eco = country.economy();
        var rules = service.economyRules();
        var now = player.server.overworld().getGameTime();
        var id = player.getUUID();
        var pending = orders.get(id);
        if (pending != null && (now > pending.deadline() || !country.id().equals(pending.nation()))) forget(id);
        String notice = "";
        String location = NationService.dimensionId(player) + " " + player.chunkPosition().x + " " + player.chunkPosition().z;
        switch (action) {
            case "open", "oil", "cancel" -> {
                forget(id);
                if (action.equals("oil") && !Petroleum.enabled()) {
                    player.sendSystemMessage(Component.literal(lang.text("gov.oil.off")));
                    return 0;
                }
            }
            case "survey" -> notice = Petroleum.survey(player, country);
            case "export" -> {
                forget(id);
                if (!Petroleum.enabled()) notice = lang.text("gov.oil.off");
                else {
                    int buckets = Integer.parseInt(arg);
                    var tank = Petroleum.inspect(player, country);
                    String problem = tank.problem(buckets);
                    if (!problem.isEmpty()) notice = lang.text("gov.oil." + problem);
                    else {
                        var quote = Petroleum.quote(player, tank, buckets);
                        String label = lang.text("gov.oil.offer", buckets, lang.text("gov.oil." + tank.grade().id), lang.coins(quote.value()));
                        orders.put(id, new Order(UUID.randomUUID(), country.id(), action, null, buckets, quote.unitPrice(),
                                0, null, now + 1200, label, "", quote));
                    }
                }
            }
            case "refresh" -> {}
            case "edit" -> {
                String current = switch (arg) { case "name" -> country.name(); case "preamble" -> country.preamble(); default -> country.doctrines(); };
                player.sendSystemMessage(Component.literal(lang.text("gov.type", lang.text("gov." + arg)))
                        .withStyle(s -> s.withUnderlined(true).withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                                "/nation panel " + arg + " " + current))));
                return 1;
            }
            case "name", "preamble", "doctrines" -> {
                String value = arg.strip();
                if (value.length() > TEXT_LIMIT || value.chars().anyMatch(ch -> Character.isISOControl(ch) || ch == '§'))
                    notice = lang.text("gov.invalidText", TEXT_LIMIT);
                else if (action.equals("name") && !service.nations().rename(country, value)) notice = lang.text("gov.invalidName");
                else {
                    if (action.equals("doctrines")) country.setDoctrines(value);
                    if (action.equals("preamble")) country.setPreamble(value);
                    service.markDirty();
                    if (action.equals("name")) service.markMapDirty();
                    notice = lang.text("gov.saved");
                }
            }
            case "bind" -> {
                forget(id);
                notice = lang.text(chest.inventory() == null ? "gov.chest." + chest.status() : "gov.bound");
                if (chest.inventory() == null) chest = CapitalChest.access(player, country);
            }
            case "claim" -> {
                var claimed = service.claimChunk(player);
                notice = claimed.success() ? lang.text("gov.claimed") : claimed.message();
            }
            case "release" -> {
                forget(id);
                var here = service.claims().claimAt(NationService.dimensionId(player), player.chunkPosition().x, player.chunkPosition().z);
                if (here == null || !here.nationId().equals(country.id())) notice = lang.text("gov.notOwned");
                else {
                    String label = lang.text("gov.releaseQuote", player.chunkPosition().x, player.chunkPosition().z);
                    orders.put(id, new Order(UUID.randomUUID(), country.id(), action, null, 0, 0, 0, null, now + 1200, label, location, null));
                }
            }
            case "sell" -> {
                forget(id);
                String[] parts = arg.split(" ");
                Commodity item = Commodity.valueOf(parts[0].toUpperCase(java.util.Locale.ROOT));
                int stock = CapitalChest.count(chest.inventory(), item);
                int count = parts.length == 1 ? stock : Integer.parseInt(parts[1]);
                if (chest.inventory() == null) notice = lang.text("gov.chest." + chest.status());
                else if (count <= 0 || count > stock) notice = lang.text("gov.short");
                else {
                    String label = lang.text("gov.sale", count, lang.text("gov." + item.id()), lang.coins(count * item.price()));
                    orders.put(id, new Order(UUID.randomUUID(), country.id(), action, item, count, item.price(), 0, chest.seal(), now + 1200, label, "", null));
                }
            }
            case "garrison" -> {
                forget(id);
                long cost = rules.purchaseCost(Investment.GARRISON, eco.garrisonLevel());
                if (eco.garrisonLevel() >= rules.maxLevel()) notice = lang.text("gov.max");
                else if (eco.balance() < cost) notice = lang.text("gov.poor");
                else {
                    String label = lang.text("gov.expense", lang.text("gov." + action), lang.coins(cost));
                    orders.put(id, new Order(UUID.randomUUID(), country.id(), action, null, 1, cost, eco.garrisonLevel(), null, now + 1200, label, "", null));
                }
            }
            case "confirm" -> notice = confirm(player, country, arg, location, chest);
            default -> throw new IllegalArgumentException(action);
        }
        if (action.equals("claim") || action.equals("confirm")) chest = CapitalChest.access(player, country);
        sendView(player, country, action, notice, chest);
        return 1;
    }

    private static String confirm(ServerPlayer player, Nation country, String token, String location, CapitalChest.Access chest) {
        var lang = ServerLanguage.of(player);
        var order = orders.get(player.getUUID());
        if (order == null || !order.token().toString().equals(token)) return lang.text("gov.changed");
        forget(player.getUUID());
        var service = ModServer.nations();
        var rules = service.economyRules();
        switch (order.action()) {
            case "export" -> {
                if (Petroleum.confirm(player, country, order.export()))
                    return lang.text("gov.sold", lang.coins(order.export().value()));
            }
            case "sell" -> {
                if (order.seal().equals(chest.seal()) && CapitalChest.sell(chest.inventory(), country,
                        order.item(), order.quantity(), order.unitPrice())) {
                    service.markDirty();
                    return lang.text("gov.sold", lang.coins(order.quantity() * order.unitPrice()));
                }
            }
            case "release" -> {
                if (order.location().equals(location)) {
                    var released = service.unclaimChunk(player);
                    return released.success() ? lang.text("gov.released") : released.message();
                }
            }
            case "garrison" -> {
                if (country.economy().garrisonLevel() == order.level()
                        && rules.purchaseCost(Investment.GARRISON, order.level()) == order.unitPrice())
                    return result(lang, service.invest(player, "garrison"));
            }
        }
        return lang.text("gov.changed");
    }

    private static void sendView(ServerPlayer player, Nation country, String action, String notice, CapitalChest.Access chest) {
        var service = ModServer.nations();
        var lang = ServerLanguage.of(player);
        var rules = service.economyRules();
        var eco = country.economy();
        int land = service.claims().chunkCount(country.id());
        var here = service.claims().claimAt(NationService.dimensionId(player), player.chunkPosition().x, player.chunkPosition().z);
        var rows = Arrays.stream(Commodity.values()).map(item -> new GovernmentView.Row(item.id(),
                CapitalChest.count(chest.inventory(), item), item.price())).toList();
        var order = orders.get(player.getUUID());
        var view = new GovernmentView(action, country.name(), country.preamble(), country.doctrines(), country.color(), land,
                country.capitalX() + ", " + country.capitalZ(), player.chunkPosition().x + ", " + player.chunkPosition().z,
                here == null ? lang.text("modserver.map.unclaimed") : service.nameOf(here.nationId()), eco.balance(), rules.dailyIncome(land, eco),
                eco.garrisonPoints(),
                eco.garrisonLevel(), rules.maxLevel(), rules.purchaseCost(Investment.GARRISON, eco.garrisonLevel()),
                rules.attackCost(false), rules.attackCost(true),
                country.treasuryChest() == null ? "" : BlockPos.of(country.treasuryChest()).toShortString() + " · " + country.treasuryDimension(),
                chest.status(), rows, notice, order == null ? "" : order.token().toString(), order == null ? "" : order.label(),
                Petroleum.enabled() ? Petroleum.inspect(player, country).view(
                        OilFields.nationalReserves(player.server, service.claims(), country.id())) : null);
        if (NetworkRegistry.hasChannel(player.connection, GovernmentView.TYPE.id())) PacketDistributor.sendToPlayer(player, view);
        else GovernmentBook.show(player, view);
    }
    private static String result(ServerLanguage language, NationService.Result result) {
        return result.success() ? language.text("gov.saved") : result.message();
    }
    private Government() {}
}
