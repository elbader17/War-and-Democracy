package com.modserver.command;

import com.modserver.ModServer;
import com.modserver.block.ModServerBlocks;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.internal.versions.neoforge.NeoForgeVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diagnostic commands of the mod. They are the main way for a bot or an external
 * script to check the state of the mod without relying on a human watching the screen.
 */
public final class ModServerCommands {

    public static final Logger LOGGER = LoggerFactory.getLogger("MODSERVER");
    /** Marker used by the smoke test scripts to parse the output. */
    public static final String SELFTEST_MARKER = "MODSERVER_SELFTEST";

    private ModServerCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("modserver")
                .requires(source -> source.hasPermission(0))
                .then(Commands.literal("info").executes(ModServerCommands::info))
                .then(Commands.literal("selftest").executes(ModServerCommands::selftest))
                .then(Commands.literal("spawncog").executes(ModServerCommands::spawnCog)));
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("[modserver] Minecraft "
                + net.minecraft.SharedConstants.getCurrentVersion().getName()), false);
        source.sendSuccess(() -> Component.literal("[modserver] NeoForge "
                + NeoForgeVersion.getVersion()), false);
        source.sendSuccess(() -> Component.literal("[modserver] Mod "
                + ModServer.MOD_ID + " loaded correctly"), false);
        return 1;
    }

    /**
     * Runs a battery of internal checks and returns an exit code of 0 (all OK)
     * or 1 (something failed), so that an agent can decide automatically.
     */
    private static int selftest(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int passed = 0;
        int total = 0;

        total++;
        if (ModServerBlocks.COG.isBound()) {
            passed++;
            LOGGER.info("{} CHECK block_registered OK", SELFTEST_MARKER);
        } else {
            LOGGER.error("{} CHECK block_registered FAIL", SELFTEST_MARKER);
        }

        total++;
        ResourceLocation cogId = ModServer.id("cog");
        if (BuiltInRegistries.BLOCK.containsKey(cogId)) {
            passed++;
            LOGGER.info("{} CHECK block_in_registry OK", SELFTEST_MARKER);
        } else {
            LOGGER.error("{} CHECK block_in_registry FAIL", SELFTEST_MARKER);
        }

        total++;
        Block block = BuiltInRegistries.BLOCK.get(cogId);
        if (block != null && !block.defaultBlockState().isAir()) {
            passed++;
            LOGGER.info("{} CHECK block_state_valid OK", SELFTEST_MARKER);
        } else {
            LOGGER.error("{} CHECK block_state_valid FAIL", SELFTEST_MARKER);
        }

        total++;
        if (source.getLevel() != null && !source.getLevel().isClientSide()) {
            passed++;
            LOGGER.info("{} CHECK server_side OK", SELFTEST_MARKER);
        } else {
            LOGGER.error("{} CHECK server_side FAIL", SELFTEST_MARKER);
        }

        String summary = SELFTEST_MARKER + " RESULT " + passed + "/" + total + (passed == total ? " PASS" : " FAIL");
        LOGGER.info(summary);
        source.sendSuccess(() -> Component.literal(summary), false);
        return passed == total ? 0 : 1;
    }

    /** Places a block of the mod on the ground in front of the player. Useful for automated tests. */
    private static int spawnCog(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        var level = source.getLevel();
        Entity entity = source.getEntity();
        if (level == null) {
            source.sendFailure(Component.literal("[modserver] No world available"));
            return 0;
        }

        BlockPos target = entity != null
                ? entity.blockPosition().relative(entity.getDirection())
                : BlockPos.containing(source.getPosition()).relative(net.minecraft.core.Direction.SOUTH);

        BlockState state = ModServerBlocks.COG.get().defaultBlockState();
        boolean alreadyThere = level.getBlockState(target).is(ModServerBlocks.COG.get());
        boolean placed = level.setBlockAndUpdate(target, state);
        boolean ok = placed || alreadyThere;
        String msg = "[modserver] spawncog " + (ok ? "OK" : "FAIL") + " at " + target.toShortString();
        LOGGER.info("{} SPAWNCOG {} {}", SELFTEST_MARKER, ok ? "OK" : "FAIL", target.toShortString());
        source.sendSuccess(() -> Component.literal(msg), true);
        return ok ? 1 : 0;
    }
}
