package com.modserver.military;

import com.modserver.ModServer;
import com.modserver.NationService;
import com.modserver.ServerLanguage;
import com.modserver.claim.Claim;
import com.modserver.nation.Nation;
import com.modserver.network.TacticalBoard;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class Tactics {
    private static final Map<UUID,TacticalBoard> sessions = new HashMap<>();
    private static final int WORLD_LIMIT = 1875000;
    private static final Map<UUID,String> battleReports = new HashMap<>();
    public static void reportTo(ServerPlayer player, String report) {
        if (sessions.containsKey(player.getUUID())) battleReports.put(player.getUUID(), report);
    }
    public static void disconnect(UUID id) { sessions.remove(id); battleReports.remove(id); }

    public static LiteralArgumentBuilder<CommandSourceStack> commands(String name) {
        var root = Commands.literal(name).executes(c -> open(c.getSource().getPlayerOrException(), null, null));
        root.then(Commands.argument("x", coord()).then(Commands.argument("z", coord()).executes(c ->
                open(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c,"x"), IntegerArgumentType.getInteger(c,"z")))));
        root.then(Commands.literal("buy").then(quantity(ArmyCombat.Order.BUY)));
        root.then(Commands.literal("deploy").then(position("x","z", quantity(ArmyCombat.Order.DEPLOY))));
        root.then(Commands.literal("move").then(position("x","z",position("tx","tz", quantity(ArmyCombat.Order.MOVE)))));
        boolean spanish = name.equals("tactica");
        root.then(Commands.literal(spanish ? "enviar" : "send").then(position("x","z",position("tx","tz",quantity(ArmyCombat.Order.SEND)))));
        var transit = Commands.literal(spanish ? "envios" : "shipments");
        transit.executes(c -> listTransfers(c.getSource().getPlayerOrException(), 0));
        transit.then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(c ->
                listTransfers(c.getSource().getPlayerOrException(), arg(c,"page") - 1)));
        root.then(transit);
        root.then(Commands.literal("roll").then(position("x","z",position("tx","tz", quantity(ArmyCombat.Order.ROLL)))));
        root.then(Commands.literal("exchange").then(Commands.argument("x",coord()).then(Commands.argument("z",coord())
                .executes(c -> run(c,ArmyCombat.Order.EXCHANGE,arg(c,"x"),arg(c,"z"),0,0,1,null)))));
        root.then(Commands.literal("nuclear").then(Commands.literal("buy").then(Commands.argument("x",coord()).then(Commands.argument("z",coord())
                .executes(c -> run(c,ArmyCombat.Order.BUY_NUCLEAR,arg(c,"x"),arg(c,"z"),0,0,1,null))))));
        root.then(Commands.literal("nuclear").then(Commands.literal("fire").then(position("x","z",Commands.argument("tx",coord()).then(Commands.argument("tz",coord())
                .executes(c -> run(c,ArmyCombat.Order.NUKE,arg(c,"x"),arg(c,"z"),arg(c,"tx"),arg(c,"tz"),1,null)))))));
        root.then(Commands.literal("fire").then(position("x","z",Commands.argument("tx",coord()).then(Commands.argument("tz",coord())
                .executes(c -> run(c,ArmyCombat.Order.FIRE,arg(c,"x"),arg(c,"z"),arg(c,"tx"),arg(c,"tz"),1,null))))));
        return root;
    }

    private static int arg(CommandContext<CommandSourceStack> ctx,String name) {
        return IntegerArgumentType.getInteger(ctx,name);
    }
    private static RequiredArgumentBuilder<CommandSourceStack,Integer> quantity(ArmyCombat.Order order) {
        var amount = Commands.argument("quantity", IntegerArgumentType.integer(1, (order == ArmyCombat.Order.ROLL || order == ArmyCombat.Order.SEND) ? Integer.MAX_VALUE : 9999));
        amount.executes(c -> troopOrder(c, order, order == ArmyCombat.Order.BUY ? TroopBranch.GROUND : null));
        for (var arm : TroopBranch.ALL)
            amount.then(Commands.literal(arm.id).executes(c -> troopOrder(c, order, arm)));
        return amount;
    }

    private static int troopOrder(CommandContext<CommandSourceStack> c, ArmyCombat.Order order, TroopBranch branch)
            throws CommandSyntaxException {
        boolean origin = order != ArmyCombat.Order.BUY;
        boolean target = order == ArmyCombat.Order.MOVE || order == ArmyCombat.Order.ROLL || order == ArmyCombat.Order.SEND;
        return run(c, order, origin ? arg(c,"x") : 0, origin ? arg(c,"z") : 0,
                target ? arg(c,"tx") : 0, target ? arg(c,"tz") : 0, arg(c,"quantity"), branch);
    }
    private static RequiredArgumentBuilder<CommandSourceStack,Integer> position(String x,String z,ArgumentBuilder<CommandSourceStack,?> next) {
        return Commands.argument(x,coord()).then(Commands.argument(z,coord()).then(next));
    }
    private static int run(CommandContext<CommandSourceStack> ctx,ArmyCombat.Order action,int x,int z,int tx,int tz,int n,TroopBranch branch)
            throws CommandSyntaxException {
        var player=ctx.getSource().getPlayerOrException();
        var owner=ModServer.nations().nationOf(player.getUUID()).orElse(null);
        var result=execute(player,owner,action,x,z,tx,tz,n,branch);
        var lang = ServerLanguage.of(player);
        player.sendSystemMessage(Component.literal(feedback(lang, result, branch)));
        return result.success()?1:0;
    }
    private static IntegerArgumentType coord() { return IntegerArgumentType.integer(-WORLD_LIMIT,WORLD_LIMIT); }

    public static int open(ServerPlayer p, Integer x, Integer z) {
        int cx=x==null?p.chunkPosition().x:x, cz=z==null?p.chunkPosition().z:z;
        if (NetworkRegistry.hasChannel(p.connection,TacticalBoard.TYPE.id())) send(p,cx,cz,true,"",TacticalBoard.ViewContext.DEFAULT);
        else {
            var lang=ServerLanguage.of(p);
            var service=ModServer.nations();
            var here=service.claims().claimAt(NationService.dimensionId(p),cx,cz);
            if(here!=null) p.sendSystemMessage(Component.literal(lang.text("tac.cellInfo",cx,cz,service.nameOf(here.nationId()),here.armies(),here.missiles(),here.nuclearMissiles())));
            var mine=service.nationOf(p.getUUID()).orElse(null);
            if(mine!=null) p.sendSystemMessage(Component.literal(lang.text("tac.wallet",lang.coins(mine.economy().balance()),mine.economy().garrisonPoints())));
            if (here != null) p.sendSystemMessage(Component.literal(composition(lang, here.forces())));
            if (mine != null) p.sendSystemMessage(Component.literal(lang.text("tac.reserveMix", composition(lang, mine.economy().reserveForces()))));
            p.sendSystemMessage(Component.literal(lang.text("tac.branch.rules")));
            p.sendSystemMessage(Component.literal(lang.text("tac.convoy.commands")));
            p.sendSystemMessage(Component.literal(lang.text("tac.commands",lang.coins(ArmyCombat.price()))));
        }
        return 1;
    }

    private static String feedback(ServerLanguage lang, ArmyCombat.Result result, TroopBranch branch) {
        String text = result.text(lang);
        return result.success() && branch != null ? text + " · " + lang.text("tac.branch." + branch.id) : text;
    }

    private static String composition(ServerLanguage lang, TroopCounts forces) {
        return lang.text("tac.composition", forces.ground(), forces.air(), forces.naval());
    }

    public static void request(ServerPlayer p, TacticalBoard.Request req) {
        var old=sessions.get(p.getUUID());
        if(old==null || !old.token().equals(req.token())) return;
        var lang=ServerLanguage.of(p);
        if(!p.level().dimension().location().equals(old.dimension())) {
            send(p,p.chunkPosition().x,p.chunkPosition().z,false,lang.text("tac.changed"),TacticalBoard.ViewContext.DEFAULT); return;
        }
        if(req.action().equals("view")) {
            send(p,Math.clamp(req.x(),-WORLD_LIMIT,WORLD_LIMIT),Math.clamp(req.z(),-WORLD_LIMIT,WORLD_LIMIT),false,"",req.view()); return;
        }
        ArmyCombat.Order action;
        try { action=ArmyCombat.Order.valueOf(req.action()); } catch(IllegalArgumentException ignored) { return; }
        var service=ModServer.nations();
        var actor=service.nationOf(p.getUUID()).orElse(null);
        String dim=NationService.dimensionId(p);
        boolean valid = actor!=null && actor.id().equals(old.mine()) && !p.isSpectator();
        if(action==ArmyCombat.Order.BUY) valid &= old.price()==ArmyCombat.price();
        else valid &= matches(old.at(req.x(),req.z()),service.claims().claimAt(dim,req.x(),req.z()));
        if (action == ArmyCombat.Order.BUY_NUCLEAR) valid &= old.nuclearPrice() == ArmyCombat.nuclearPrice();
        if(action==ArmyCombat.Order.SEND || action==ArmyCombat.Order.MOVE || action.startsCombat())
            valid &= matches(old.at(req.tx(),req.tz()),service.claims().claimAt(dim,req.tx(),req.tz()));
        if (action == ArmyCombat.Order.DEPLOY && actor != null) valid &= old.reserveForces().equals(actor.economy().reserveForces());
        if (valid && action == ArmyCombat.Order.NUKE) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                int x = req.tx() + dx, z = req.tz() + dz;
                var shown = old.at(x, z);
                var actual = service.claims().claimAt(dim, x, z);
                if (shown == null && actual == null) continue;
                var battle = service.disputes().openAt(dim, x, z);
                var invading = battle == null || battle.assault() == null ? TroopCounts.EMPTY : battle.assault().forces();
                valid &= matches(shown, actual) && shown.invading().equals(invading);
            }
        }
        if (!valid) {
            send(p, old.x(), old.z(), false, lang.text("tac.changed"), req.view());
            return;
        }
        var result = execute(p, actor, action, req.x(), req.z(), req.tx(), req.tz(), req.quantity(), req.branch());
        var closeToken = result.success() && action.startsCombat() ? old.token() : null;
        send(p, old.x(), old.z(), false, feedback(lang, result, req.branch()), req.view(), closeToken);
    }

    private static boolean matches(TacticalBoard.Cell shown, Claim actual) {
        return shown!=null && actual!=null && shown.owner().equals(actual.nationId())
                && shown.forces().equals(actual.forces()) && shown.missiles()==actual.missiles() && shown.nuclearMissiles()==actual.nuclearMissiles();
    }
    public static ArmyCombat.Result execute(ServerPlayer player, Nation actor, ArmyCombat.Order order, int x, int z, int tx, int tz, int quantity) {
        return execute(player, actor, order, x, z, tx, tz, quantity, order == ArmyCombat.Order.BUY ? TroopBranch.GROUND : null);
    }

    public static ArmyCombat.Result execute(ServerPlayer player, Nation actor, ArmyCombat.Order order, int x, int z, int tx, int tz,
                                           int quantity, TroopBranch branch) {
        var service=ModServer.nations();
        var dim=NationService.dimensionId(player);
        var target=service.claims().claimAt(dim,tx,tz);
        var victim=target==null?null:service.nations().byId(target.nationId()).orElse(null);
        var result=ArmyCombat.execute(service,player.isSpectator()?null:actor,dim,order,x,z,tx,tz,quantity,
                player.server.overworld().getGameTime(), branch);
        if (result.success() && (order == ArmyCombat.Order.FIRE || order == ArmyCombat.Order.NUKE))
            service.missileEffects().launch(player, x, z, tx, tz, actor.color(), order == ArmyCombat.Order.NUKE);
        if(result.success() && victim!=null && (order==ArmyCombat.Order.SEND || order.startsCombat())) {
            for(var other:player.server.getPlayerList().getPlayers()) {
                if(other!=player && (victim.isMember(other.getUUID()) || actor.isMember(other.getUUID()))) {
                    String report = actor.name()+" → "+victim.name()+" · "+result.text(ServerLanguage.of(other));
                    other.sendSystemMessage(Component.literal(report));
                    reportTo(other, report);
                }
            }
        }
        return result;
    }

    private static int listTransfers(ServerPlayer player, int index) {
        var service = ModServer.nations();
        var viewer = service.nationOf(player.getUUID()).orElse(null);
        var lang = ServerLanguage.of(player);
        var page = transitPage(service, viewer, index, player.server.overworld().getGameTime());
        var trip = page.trip();
        String text = trip == null ? lang.text("tac.convoy.empty") : lang.text("tac.convoy.entry",
                page.index()+1, page.total(), trip.originName(), trip.destinationName(), composition(lang, trip.troops()),
                lang.text("tac.convoy." + trip.leg().name()), trip.leg() == TroopTransfer.Leg.WAITING ? "" : " · " + TroopTransfer.formatDuration((trip.remainingTicks()+19)/20));
        player.sendSystemMessage(Component.literal(text));
        return 1;
    }

    public static TacticalBoard.TransitPage transitPage(NationService service, Nation viewer, int page, long now) {
        if (viewer == null) return TacticalBoard.TransitPage.EMPTY;
        record Shipment(Nation sender, TroopTransfer trip) {}
        var visible = new ArrayList<Shipment>();
        for (var sender : service.allNations()) for (var trip : sender.transfers())
            if (viewer.id().equals(sender.id()) || viewer.id().equals(trip.recipient())) visible.add(new Shipment(sender, trip));
        if (visible.isEmpty()) return TacticalBoard.TransitPage.EMPTY;
        visible.sort(Comparator.comparingLong((Shipment s) -> s.trip.dueAt()).thenComparing(s -> s.sender.id()));
        int index = Math.clamp(page, 0, visible.size()-1);
        var entry = visible.get(index);
        var trip = entry.trip();
        return new TacticalBoard.TransitPage(visible.size(), index, new TacticalBoard.Transit(entry.sender().name(),
                service.nameOf(trip.recipient()), trip.dimension(), Claim.pack(trip.fromX(),trip.fromZ()),
                Claim.pack(trip.toX(),trip.toZ()), trip.troops(), trip.leg(), Math.max(0,trip.dueAt()-now), trip.legTicks()));
    }

    private static void send(ServerPlayer p,int x,int z,boolean open,String notice,TacticalBoard.ViewContext context) {
        send(p, x, z, open, notice, context, null);
    }

    private static void send(ServerPlayer p,int x,int z,boolean open,String notice,TacticalBoard.ViewContext context, UUID closeToken) {
        var service=ModServer.nations();
        var mine=service.nationOf(p.getUUID()).orElse(null);
        var dim=p.level().dimension().location();
        Map<UUID,TacticalBoard.Country> countries=new HashMap<>();
        Map<Long,TacticalBoard.Cell> cells=new HashMap<>();
        var claims = service.claims().claimsNear(dim.toString(),x,z,TacticalBoard.RADIUS);
        for (Long pin : new Long[]{context.origin(), context.target()}) {
            if (pin == null) continue;
            var extra = service.claims().claimAt(dim.toString(), Claim.unpackX(pin), Claim.unpackZ(pin));
            if (extra != null && !claims.contains(extra)) claims.add(extra);
        }
        if (context.target() != null) {
            int tx = Claim.unpackX(context.target()), tz = Claim.unpackZ(context.target());
            if (Math.abs((long)tx-x) >= TacticalBoard.RADIUS || Math.abs((long)tz-z) >= TacticalBoard.RADIUS) {
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    var extra = service.claims().claimAt(dim.toString(), tx+dx, tz+dz);
                    if (extra != null && !claims.contains(extra)) claims.add(extra);
                }
            }
        }
        var oilFields = com.modserver.petroleum.Petroleum.enabled() ? com.modserver.petroleum.OilFields.of(p.serverLevel()) : null;
        for(var claim:claims) {
            var nation=service.nations().byId(claim.nationId()).orElse(null);
            if(nation==null) continue;
            countries.computeIfAbsent(nation.id(),id->new TacticalBoard.Country(nation.name(),nation.color(),mine!=null && mine.relationWith(id).blocksDisputes(),TroopTransfer.allied(mine,nation)));
            var battle = service.disputes().openAt(dim.toString(), claim.chunkX(), claim.chunkZ());
            cells.put(claim.chunkKey(),new TacticalBoard.Cell(nation.id(),claim.forces(),claim.missiles(),claim.nuclearMissiles(),
                    battle != null, battle == null || battle.assault() == null ? TroopCounts.EMPTY : battle.assault().forces(),
                    oilFields != null && oilFields.discovered(claim.chunkKey())));
        }
        if (notice.isEmpty()) {
            String report = battleReports.remove(p.getUUID());
            if (report != null) notice=report;
        }
        var view=new TacticalBoard(UUID.randomUUID(),dim,x,z,open,closeToken,mine==null||p.isSpectator()?TacticalBoard.OBSERVER:mine.id(),
                mine==null?0:mine.economy().balance(),mine==null?TroopCounts.EMPTY:mine.economy().reserveForces(),ArmyCombat.price(),ArmyCombat.nuclearPrice(),countries,cells,
                transitPage(service, p.isSpectator() ? null : mine, context.page(), p.server.overworld().getGameTime()),notice);
        sessions.put(p.getUUID(),view);
        PacketDistributor.sendToPlayer(p,view);
    }
    private Tactics() {}
}
