package com.modserver.command;

import com.modserver.ModServer;
import com.modserver.NationService;
import com.modserver.ServerLanguage;
import com.modserver.network.MapTerritoryInfo;
import com.modserver.network.MapTerritoryInfo.Offer;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.Optional;

public final class MapTerritoryCommand {
    private MapTerritoryCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> branch(String name, boolean english, boolean graphical) {
        var z = Commands.argument("z", IntegerArgumentType.integer()).executes(ctx -> select(ctx, graphical, null));
        var window = Commands.argument("window", LongArgumentType.longArg(0)).executes(ctx -> {
            var offer = new Offer(UuidArgument.getUuid(ctx, "challenger"), UuidArgument.getUuid(ctx, "defender"),
                    LongArgumentType.getLong(ctx, "price"), LongArgumentType.getLong(ctx, "window"));
            return select(ctx, graphical, offer);
        });
        var price = Commands.argument("price", LongArgumentType.longArg(0)).then(window);
        var rival = Commands.argument("defender", UuidArgument.uuid()).then(price);
        z.then(Commands.literal(english ? "confirm" : "confirmar").then(Commands.argument("challenger", UuidArgument.uuid()).then(rival)));
        return Commands.literal(name).then(Commands.argument("dimension", ResourceLocationArgument.id())
                .then(Commands.argument("x", IntegerArgumentType.integer()).then(z)));
    }

    private static int select(CommandContext<CommandSourceStack> ctx, boolean graphical, Offer expected) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ResourceLocation dimension = ResourceLocationArgument.getId(ctx, "dimension");
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        var service = ModServer.nations();
        var language = ServerLanguage.of(player);
        var view = inspect(service, player, dimension, x, z);
        if (expected != null && dimension.equals(player.level().dimension().location()))
            return com.modserver.military.Tactics.open(player, x, z);
        if (graphical && NetworkRegistry.hasChannel(player.connection, MapTerritoryInfo.TYPE.id())) {
            PacketDistributor.sendToPlayer(player, view);
        } else {
            player.sendSystemMessage(Component.literal("Chunk " + x + ", " + z + " · " + view.owner() + "\n" + view.detail()));
            if (dimension.equals(player.level().dimension().location()))
                player.sendSystemMessage(Component.literal(language.text("tac.title")).withStyle(s -> s.withColor(ChatFormatting.GOLD)
                        .withUnderlined(true).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/nation tactics " + x + " " + z))));
        }
        return 1;
    }

    private static MapTerritoryInfo inspect(NationService service, ServerPlayer player, ResourceLocation dimension, int x, int z) {
        var language = ServerLanguage.of(player);
        String dim = dimension.toString();
        if (!player.level().dimension().location().equals(dimension)) {
            return new MapTerritoryInfo(dimension, x, z, "", language.text("modserver.map.dimension"), 0, Optional.empty());
        }
        var claim = service.claims().claimAt(dim, x, z);
        var owner = claim == null ? null : service.nations().byId(claim.nationId()).orElse(null);
        String name = owner == null ? language.text("modserver.map.unclaimed") : owner.name();
        var battle = service.disputes().openAt(dim, x, z);
        if (battle != null && battle.assault() != null) {
            return new MapTerritoryInfo(dimension, x, z, name, language.text("tac.fieldReport",
                    service.nameOf(battle.challengerNationId()), battle.assault().attackers(), name, claim.armies()), 0, Optional.empty());
        }
        if (battle != null) {
            return new MapTerritoryInfo(dimension, x, z, name, language.text("modserver.map.active", service.nameOf(battle.challengerNationId()),
                    service.nameOf(battle.defenderNationId())), battle.deadline(), Optional.empty());
        }
        var mine = service.nationOf(player.getUUID()).orElse(null);
        String detail;
        if (owner == null) detail = language.text("modserver.map.free");
        else if (mine == null) detail = language.text("modserver.map.found");
        else if (mine.id().equals(owner.id())) detail = language.text("modserver.map.own");
        else if (mine.relationWith(owner.id()).blocksDisputes()) detail = language.text("modserver.map.agreement", name);
        else {
            detail = language.text("tac.inspect");
        }
        return new MapTerritoryInfo(dimension, x, z, name, detail, 0, Optional.empty());
    }
}
