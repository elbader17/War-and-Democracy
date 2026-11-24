package com.modserver.npc;

import com.modserver.military.TroopBranch;
import com.modserver.ModServer;
import com.modserver.NationService;
import com.modserver.claim.Claim;
import com.modserver.ServerLanguage;
import com.modserver.nation.Nation;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.Comparator;
import net.minecraft.core.Direction;
import com.modserver.military.ArmyCombat;
import com.modserver.military.Tactics;

/** A persistent vanilla villager owns a country and offers real border challenges. */
public final class RivalNpc {
    private static final String TAG = "modserver.rival";

    private RivalNpc() {}

    public static LiteralArgumentBuilder<CommandSourceStack> createCommand() {
        return Commands.literal("rival").then(Commands.argument("name", StringArgumentType.string())
                .executes(ctx -> create(ctx.getSource(), StringArgumentType.getString(ctx, "name"))));
    }

    private static int create(CommandSourceStack source, String name) {
        var level = source.getLevel();
        Villager envoy = EntityType.VILLAGER.create(level);
        if (envoy == null) return 0;
        var pos = source.getPosition();
        envoy.moveTo(pos.x, pos.y, pos.z, source.getRotation().y, 0);
        envoy.addTag(TAG);
        envoy.setCustomName(Component.translatableWithFallback("modserver.npc.envoy", "Envoy of %s", name).withStyle(ChatFormatting.LIGHT_PURPLE));
        envoy.setCustomNameVisible(true);
        envoy.setNoAi(true);
        envoy.setInvulnerable(true);
        envoy.setPersistenceRequired();
        envoy.setVillagerData(envoy.getVillagerData().setProfession(VillagerProfession.CARTOGRAPHER));
        if (!level.addFreshEntity(envoy)) {
            source.sendFailure(Component.literal("Could not spawn the envoy."));
            return 0;
        }
        var result = ModServer.nations().createRivalNation(envoy.getUUID(), name, level.dimension().location().toString(),
                envoy.chunkPosition(), level.getGameTime());
        if (!result.success()) {
            envoy.discard();
            source.sendFailure(Component.literal(result.message()));
            return 0;
        }
        muster(envoy);
        source.sendSuccess(() -> Component.literal("Envoy of " + name + " spawned in chunk "
                + envoy.chunkPosition() + ". Right-click to speak to the envoy."), true);
        return 1;
    }

    /** Finance one initial defending detachment, including envoys in worlds created before armies existed. */
    public static void muster(Villager envoy) {
        if (!(envoy.level() instanceof net.minecraft.server.level.ServerLevel level) || !envoy.getTags().contains(TAG)) return;
        var tag=envoy.getPersistentData();
        var service=ModServer.nations();
        var rival=service.nationOf(envoy.getUUID()).orElse(null);
        if (rival==null) return;
        if (!rival.isEnvoy()) { rival.setEnvoy(true); service.markDirty(); }
        if (tag.getBoolean("modserver.mustered")) return;
        String dim=level.dimension().location().toString();
        if (!dim.equals(rival.capitalDimension())) return;
        var capital=service.claims().claimAt(dim,rival.capitalX(),rival.capitalZ());
        if (capital==null || !capital.nationId().equals(rival.id())) return;
        reinforce(service, rival, capital, level.getGameTime());
        tag.putBoolean("modserver.mustered",true);
    }

    private static void reinforce(NationService service, Nation rival, Claim capital, long now) {
        String dim = capital.dimension();
        int needed=Math.max(0,4-capital.armies());
        int purchase=(int)Math.min(Math.max(0,needed-rival.economy().garrisonPoints()),Math.max(0,rival.economy().balance())/ArmyCombat.price());
        for (int i = 0; i < purchase; i++) {
            var reserve = rival.economy().reserveForces();
            var arm = TroopBranch.GROUND;
            for (var candidate : TroopBranch.ALL)
                if (capital.forces().count(candidate) + (long)reserve.count(candidate) <
                        capital.forces().count(arm) + (long)reserve.count(arm)) arm = candidate;
            ArmyCombat.execute(service, rival, dim, ArmyCombat.Order.BUY, 0,0,0,0,1,now,arm);
        }
        int deploy=Math.min(needed,rival.economy().garrisonPoints());
        if (deploy>0) ArmyCombat.execute(service,rival,dim,ArmyCombat.Order.DEPLOY,capital.chunkX(),capital.chunkZ(),0,0,deploy,now);
    }

    public static void join(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof Villager envoy && envoy.getTags().contains(TAG)
                && event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            var server=level.getServer();
            server.tell(new net.minecraft.server.TickTask(server.getTickCount(),()->{
                if (!envoy.isRemoved()) muster(envoy);
            }));
        }
    }

    public static void interact(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getTarget() instanceof Villager envoy)
                || !envoy.getTags().contains(TAG)) return;
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        muster(envoy);
        var language = ServerLanguage.of(player);
        NationService service = ModServer.nations();
        Nation rival = service.nationOf(envoy.getUUID()).orElse(null);
        Nation mine = service.nationOf(player.getUUID()).orElse(null);
        if (rival == null) return;
        if (mine == null) {
            player.sendSystemMessage(Component.literal(language.text("modserver.npc.found")));
            return;
        }
        player.sendSystemMessage(Component.literal(language.text("modserver.npc.envoy", rival.name())).withColor(0xF4CB75).withStyle(ChatFormatting.BOLD));
        player.sendSystemMessage(Component.literal(language.text("modserver.npc.rules")));
        player.sendSystemMessage(Component.literal(language.text("modserver.npc.defense")));
        String dimension = NationService.dimensionId(player);
        offer(player, envoy, mine, rival, dimension, "attack", language.text("modserver.npc.attack", rival.name()), ChatFormatting.GOLD);
        offer(player, envoy, rival, mine, dimension, "receive", language.text("modserver.npc.receive", rival.name()), ChatFormatting.AQUA);
        player.sendSystemMessage(Component.literal(language.text("modserver.npc.actions")).withStyle(ChatFormatting.GRAY));
    }

    private static void offer(ServerPlayer player, Villager envoy, Nation attacker, Nation target, String dimension,
                              String action, String label, ChatFormatting color) {
        ChunkPos origin = envoy.chunkPosition();
        Claim chunk = ModServer.nations().claims().claimsOf(target.id()).stream()
                .filter(c -> dimension.equals(c.dimension()) && originFor(attacker, c) != null)
                .min(Comparator.<Claim>comparingDouble(c -> Math.hypot(c.chunkX() - origin.x, c.chunkZ() - origin.z))
                        .thenComparingInt(Claim::chunkX).thenComparingInt(Claim::chunkZ)).orElse(null);
        if (chunk == null) {
            player.sendSystemMessage(Component.literal(ServerLanguage.of(player).text("modserver.npc.unavailable", target.name())).withStyle(ChatFormatting.GRAY));
            return;
        }
        String coords = chunk.chunkX() + " " + chunk.chunkZ();
        String command = "/nation rival " + action + " " + envoy.getStringUUID() + " " + coords;
        player.sendSystemMessage(Component.literal("[" + label + " · chunk " + coords + "]").withStyle(style ->
                style.withColor(color).withUnderlined(true).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> interactionCommands(boolean english) {
        return Commands.literal("rival").then(action(english ? "attack" : "atacar", false)).then(action(english ? "receive" : "recibir", true));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> action(String name, boolean receive) {
        return Commands.literal(name).then(Commands.argument("envoy", UuidArgument.uuid())
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer()).executes(ctx -> challenge(ctx, receive)))));
    }

    private static int challenge(CommandContext<CommandSourceStack> ctx, boolean receive) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var entity = player.serverLevel().getEntity(UuidArgument.getUuid(ctx, "envoy"));
        if (!(entity instanceof Villager envoy) || !envoy.isAlive() || !envoy.getTags().contains(TAG)
                || player.distanceToSqr(envoy) > 64) {
            ctx.getSource().sendFailure(Component.literal(ServerLanguage.of(player).text("modserver.npc.approach")));
            return 0;
        }
        var language = ServerLanguage.of(player);
        NationService service = ModServer.nations();
        Nation mine = service.nationOf(player.getUUID()).orElse(null);
        Nation rival = service.nationOf(envoy.getUUID()).orElse(null);
        if (mine == null || rival == null) {
            ctx.getSource().sendFailure(Component.literal(language.text("modserver.npc.both")));
            return 0;
        }
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        String dimension = NationService.dimensionId(player);
        Claim target = service.claims().claimAt(dimension, x, z);
        Nation defender = receive ? mine : rival;
        if (target == null || !target.nationId().equals(defender.id())) {
            ctx.getSource().sendFailure(Component.literal(language.text("modserver.npc.changed", defender.name())));
            return 0;
        }
        if (!receive) return Tactics.open(player,x,z);
        Claim origin = originFor(rival, target);
        var engagement = service.disputes().openAt(dimension,x,z);
        if (player.isSpectator() || origin == null || rival.relationWith(mine.id()).blocksDisputes()
                || (engagement != null && (engagement.assault() != null || !rival.id().equals(engagement.challengerNationId())))) {
            ctx.getSource().sendFailure(Component.literal(language.text("tac.changed")));
            return 0;
        }
        // The envoy uses its own treasury and the same deployment rules as a player.
        reinforce(service, rival, origin, player.server.overworld().getGameTime());
        var result = Tactics.execute(player,rival,ArmyCombat.Order.ROLL,origin.chunkX(),origin.chunkZ(),x,z,origin.armies());
        player.sendSystemMessage(Component.literal(result.text(language)));
        return result.success()?1:0;
    }

    private static Claim originFor(Nation attacker, Claim target) {
        Claim strongest = null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            var c = ModServer.nations().claims().claimAt(target.dimension(),target.chunkX()+d.getStepX(),target.chunkZ()+d.getStepZ());
            if (c != null && attacker.id().equals(c.nationId()) && (strongest == null || c.armies()>strongest.armies())) strongest=c;
        }
        return strongest;
    }
}
