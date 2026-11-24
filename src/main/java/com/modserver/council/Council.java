package com.modserver.council;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.modserver.ModServer;
import com.modserver.NationService;
import com.modserver.ServerLanguage;
import com.modserver.claim.Claim;
import com.modserver.council.CouncilPost.Kind;
import com.modserver.government.CapitalChest;
import com.modserver.government.Commodity;
import com.modserver.military.Tactics;
import com.modserver.military.TroopTransfer;
import com.modserver.nation.Nation;
import com.modserver.network.CouncilView;
import com.modserver.petroleum.OilFields;
import com.modserver.petroleum.Petroleum;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.*;

public final class Council {
    public static LiteralArgumentBuilder<CommandSourceStack> commands(String name) {
        var root = Commands.literal(name).executes(c -> run(c, "open"));
        root.then(Commands.literal("page").then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(c -> run(c, "page"))));
        for (String action : List.of("visit", "pact", "ally", "peace", "convoy", "trade", "letter", "circular", "accept", "decline", "withdraw")) {
            var peer = Commands.argument("nation", StringArgumentType.string());
            switch (action) {
                case "letter", "circular" -> peer.then(Commands.argument("text", StringArgumentType.greedyString()).executes(c -> run(c, action)));
                case "trade" -> peer.then(Commands.argument("item", StringArgumentType.word())
                        .then(Commands.argument("quantity", IntegerArgumentType.integer(1,3456))
                                .then(Commands.argument("coins", IntegerArgumentType.integer(1,1_000_000_000)).executes(c -> run(c, action)))));
                case "accept", "decline", "withdraw" -> peer.then(Commands.argument("id", UuidArgument.uuid()).executes(c -> run(c, action)));
                default -> peer.executes(c -> run(c, action));
            }
            root.then(Commands.literal(action).then(peer));
        }
        return root;
    }

    private static int run(CommandContext<CommandSourceStack> c, String action) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        var service = ModServer.nations();
        var mine = service.nations().ownedBy(player.getUUID()).orElse(null);
        var lang = ServerLanguage.of(player);
        if (mine == null || player.isSpectator()) {
            player.sendSystemMessage(Component.literal(lang.text("gov.owner")));
            return 0;
        }
        int page = 0;
        Nation peer = null;
        String notice = "";
        if (action.equals("page")) page = IntegerArgumentType.getInteger(c, "page") - 1;
        else if (!action.equals("open")) {
            String key = StringArgumentType.getString(c, "nation");
            try { peer = service.nations().byId(UUID.fromString(key)).orElse(null); }
            catch (IllegalArgumentException ignored) { peer = service.nationByName(key).orElse(null); }
            if (peer == null || peer == mine) notice = "invalid";
            else if (action.equals("convoy")) {
                var destination = convoyDestination(service, mine, peer, NationService.dimensionId(player));
                if (destination != null) return Tactics.open(player, destination.chunkX(), destination.chunkZ());
                notice = "noRoute";
            } else if (!action.equals("visit")) notice = act(c, action, player, mine, peer);
        }
        var view = snapshot(player, mine, peer, page, action.equals("open"), notice);
        if (player.connection.isAcceptingMessages() && NetworkRegistry.hasChannel(player.connection, CouncilView.TYPE.id()))
            PacketDistributor.sendToPlayer(player, view);
        else showChat(player, view);
        return notice.isEmpty() || Set.of("proposed","ratified","settled","declined","withdrawn","sent","broadcast").contains(notice) ? 1 : 0;
    }

    public static Claim convoyDestination(NationService service, Nation mine, Nation peer, String dimension) {
        if (!TroopTransfer.allied(mine, peer)) return null;
        return service.claims().claimsOf(peer.id()).stream().filter(c -> c.dimension().equals(dimension)
                && service.disputes().openAt(dimension,c.chunkX(),c.chunkZ()) == null)
                .min(Comparator.comparingLong(c -> Math.abs((long)c.chunkX()-peer.capitalX()) + Math.abs((long)c.chunkZ()-peer.capitalZ()))).orElse(null);
    }

    private static String act(CommandContext<CommandSourceStack> c, String action, ServerPlayer player, Nation mine, Nation peer) {
        var service = ModServer.nations();
        String result;
        switch (action) {
            case "ally", "pact", "peace" -> result = CouncilDesk.propose(service, mine, peer,
                    CouncilPost.note(mine.id(), peer.id(), Kind.valueOf(action.toUpperCase(Locale.ROOT)), ""));
            case "trade" -> {
                if (peer.isEnvoy()) return "envoyOnly";
                Commodity item;
                try { item = Commodity.valueOf(StringArgumentType.getString(c, "item").toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException badItem) { return "invalid"; }
                var source = CapitalChest.access(player, mine);
                int quantity = IntegerArgumentType.getInteger(c,"quantity");
                if (source.inventory() == null) return "chests";
                if (CapitalChest.count(source.inventory(),item) < quantity) return "stock";
                result = CouncilDesk.propose(service, mine, peer, new CouncilPost(UUID.randomUUID(), mine.id(), peer.id(),
                        Kind.TRADE, item, quantity, IntegerArgumentType.getInteger(c,"coins"), source.seal(), ""));
            }
            case "accept", "decline", "withdraw" -> {
                UUID id = UuidArgument.getUuid(c,"id");
                boolean sale = action.equals("accept") && mine.register().stream().anyMatch(p -> p.id().equals(id)
                        && p.from().equals(peer.id()) && p.kind() == Kind.TRADE);
                result = CouncilDesk.decide(service, mine, peer, id, action, sale ? CapitalChest.access(player,peer) : null,
                        sale ? CapitalChest.access(player,mine) : null);
            }
            case "letter", "circular" -> {
                String msg = StringArgumentType.getString(c,"text").strip();
                if (msg.isEmpty() || msg.length() > 160 || msg.chars().anyMatch(ch -> Character.isISOControl(ch) || ch == '§')) return "invalidText";
                List<Nation> recipients = action.equals("circular") ? service.allNations().stream()
                        .filter(n -> TroopTransfer.allied(mine,n) && !n.isEnvoy()).toList() : List.of(peer);
                if (recipients.isEmpty()) return "noAllies";
                if (action.equals("letter") && peer.isEnvoy()) return "envoyOnly";
                for (var n : recipients) {
                    CouncilDesk.correspond(mine, n, action.equals("letter") ? Kind.LETTER : Kind.CIRCULAR, msg);
                    service.notifyNation(n, "council.alert", mine.name());
                }
                service.markDirty();
                return action.equals("letter") ? "sent" : "broadcast";
            }
            default -> { return "invalid"; }
        }
        if (Set.of("proposed","ratified","settled","declined","withdrawn").contains(result))
            service.notifyNation(peer,"council.alert",mine.name());
        return result;
    }

    private static CouncilView snapshot(ServerPlayer player, Nation mine, Nation peer, int requestedPage, boolean open, String notice) {
        var service = ModServer.nations();
        Map<UUID, Integer> pendingBySender = new HashMap<>();
        for (var post : mine.register()) if (post.kind().offer()) pendingBySender.merge(post.from(), 1, Integer::sum);
        var countries = service.allNations().stream().filter(n -> n != mine)
                .sorted(Comparator.comparing((Nation n) -> !pendingBySender.containsKey(n.id()))
                        .thenComparing(Nation::name, String.CASE_INSENSITIVE_ORDER)).toList();
        int pages = Math.max(1, (countries.size() + CouncilView.PAGE_SIZE - 1) / CouncilView.PAGE_SIZE);
        int page = peer == null ? Math.clamp(requestedPage,0,pages-1) : Math.max(0,countries.indexOf(peer))/CouncilView.PAGE_SIZE;
        var slice = countries.subList(page * CouncilView.PAGE_SIZE, Math.min(countries.size(), (page+1)*CouncilView.PAGE_SIZE));
        Nation selected = peer == null || peer == mine ? (slice.isEmpty() ? null : slice.getFirst()) : peer;
        var directory = slice.stream().map(n -> new CouncilView.Peer(n.id(),n.name(),n.color(),mine.relationWith(n.id()),
                pendingBySender.getOrDefault(n.id(),0),n.isEnvoy())).toList();
        List<CouncilPost> offers = new ArrayList<>();
        List<CouncilPost> letters = List.of();
        if (selected != null) {
            mine.register().stream().filter(p -> p.kind().offer() && p.from().equals(selected.id())).forEach(offers::add);
            selected.register().stream().filter(p -> p.kind().offer() && p.from().equals(mine.id())).forEach(offers::add);
            letters = mine.register().reversed().stream().filter(p -> !p.kind().offer() && (p.from().equals(selected.id()) || p.to().equals(selected.id())))
                    .limit(16).toList();
        }
        var holdings = service.claims().claimsOf(mine.id());
        var eco = mine.economy();
        var chest = CapitalChest.access(player,mine);
        var stocks = Arrays.stream(Commodity.values()).map(item -> CapitalChest.count(chest.inventory(),item)).toList();
        int fronts = (int)service.disputes().open().stream().filter(d -> d.challengerNationId().equals(mine.id()) || d.defenderNationId().equals(mine.id())).count();
        return new CouncilView(open, notice, mine.id(),mine.name(),mine.color(),mine.hasCapital() ? mine.capitalX()+", "+mine.capitalZ() : "—",
                holdings.size(),eco.balance(),service.economyRules().dailyIncome(holdings.size(),eco),eco.farmLevel(),eco.garrisonLevel(),eco.treasuryLevel(),
                eco.garrisonPoints(),holdings.stream().mapToLong(Claim::armies).sum(),fronts,mine.transfers().size(),
                (int)countries.stream().filter(n -> TroopTransfer.allied(mine,n)).count(),(int)mine.register().stream().filter(p -> p.kind().offer()).count(),
                stocks,chest.inventory()!=null,Petroleum.enabled() ? OilFields.nationalReserves(player.server,service.claims(),mine.id()) : null,
                page,pages,directory,selected==null ? null : selected.id(),offers.stream().map(CouncilView.Entry::of).toList(),
                letters.stream().map(CouncilView.Entry::of).toList());
    }

    private static void showChat(ServerPlayer player, CouncilView v) {
        var lang = ServerLanguage.of(player);
        player.sendSystemMessage(Component.literal(v.name()+" · "+lang.text("gov.wallet",lang.coins(v.balance()))+" · "+lang.text("gov.land",v.land(),v.capital())));
        for (var n : v.directory()) player.sendSystemMessage(Component.literal(n.name()+" · "+lang.text("council.rel."+n.relation().name())));
        for (var post : v.offers()) player.sendSystemMessage(Component.literal(lang.text("council."+post.kind().name())
                + (post.kind()==Kind.TRADE ? " "+post.quantity()+" × "+lang.text("gov."+post.item().id())+" / "+post.coins() : "")+" · "+post.id()));
        for (var post : v.letters().stream().limit(4).toList())
            player.sendSystemMessage(Component.literal(lang.text("council."+post.kind().name())+" · "+post.text()));
        if (!v.notice().isEmpty()) player.sendSystemMessage(Component.literal(lang.text("council."+v.notice())));
        player.sendSystemMessage(Component.literal(lang.text("council.chatHelp")));
    }

    private Council() {}
}
