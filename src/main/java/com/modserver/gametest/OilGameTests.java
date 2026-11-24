package com.modserver.gametest;

import com.mojang.authlib.GameProfile;
import com.modserver.ModServer;
import com.modserver.claim.Claim;
import com.modserver.claim.ClaimIndex;
import com.modserver.network.GovernmentView;
import com.modserver.network.NationMapPayload;
import java.util.List;
import com.modserver.config.ModServerConfig;
import com.modserver.dispute.Dispute;
import com.modserver.petroleum.OilFields;
import com.modserver.petroleum.Petroleum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder("modserver")
@PrefixGameTestTemplate(false)
public final class OilGameTests {
    @GameTest(template="empty")
    public void nationalReservesFollowSurveysAndCurrentOwnership(GameTestHelper test) {
        var server = test.getLevel().getServer();
        var surface = server.overworld();
        var nether = server.getLevel(net.minecraft.world.level.Level.NETHER);
        var surfaceOil = OilFields.of(surface);
        var netherOil = OilFields.of(nether);
        var claims = new ClaimIndex();
        var owner = UUID.randomUUID();
        var rival = UUID.randomUUID();
        var first = new Claim(surface.dimension().location().toString(), -721, 420, owner, 0);
        var second = new Claim(nether.dimension().location().toString(), -721, 420, owner, 0);
        var unsurveyed = new Claim(first.dimension(), -720, 420, owner, 0);
        for (var claim : List.of(first, second, unsurveyed)) claims.claim(claim);

        surfaceOil.survey(first.chunkKey(), 1_500_000_001);
        surfaceOil.survey(first.chunkKey(), 1_500_000_001);
        netherOil.survey(second.chunkKey(), 1_500_000_002);
        netherOil.survey(unsurveyed.chunkKey(), 500); // Only the overworld chunk is claimed.
        test.assertTrue(OilFields.nationalReserves(server, claims, owner).equals(new GovernmentView.Reserves(3_000_000_003L, 2, false)),
                "Each owned field contributes its exact reading once, even across dimensions and above the integer limit");
        surfaceOil.survey(first.chunkKey(), 96_000);
        netherOil.survey(second.chunkKey(), Integer.MAX_VALUE);
        test.assertTrue(OilFields.nationalReserves(server, claims, owner).equals(new GovernmentView.Reserves(96_000, 2, true)),
                "Resurveying replaces the reading and a bottomless field marks the country's reserves as unlimited");
        claims.transfer(second, rival);
        test.assertTrue(OilFields.nationalReserves(server, claims, owner).equals(new GovernmentView.Reserves(96_000, 1, false))
                        && OilFields.nationalReserves(server, claims, rival).bottomless(),
                "A captured deposit counts for its current owner");
        surfaceOil.survey(first.chunkKey(), 0);
        test.assertTrue(OilFields.nationalReserves(server, claims, owner).equals(new GovernmentView.Reserves(0, 1, false)),
                "A depleted known field contributes zero");
        claims.release(first.dimension(), first.chunkX(), first.chunkZ());
        test.assertTrue(OilFields.nationalReserves(server, claims, owner).fields() == 0, "Released fields no longer count");
        test.succeed();
    }

    @GameTest(template="empty")
    public void terrainMapShowsRegisteredFieldsAndKeepsDistantDomesticConcessions(GameTestHelper test) {
        var fields = new OilFields();
        var claims = new ClaimIndex();
        var owner = UUID.randomUUID();
        var other = UUID.randomUUID();
        String dimension = "minecraft:overworld";
        var nearby = new Claim(dimension, -1, 2, other, 0);
        var distant = new Claim(dimension, 90, -70, owner, 0);
        var foreign = new Claim(dimension, 90, -71, other, 0);
        var unknown = new Claim(dimension, 0, 2, owner, 0);
        for (var claim : List.of(nearby, distant, foreign, unknown)) claims.claim(claim);
        for (var claim : List.of(nearby, distant, foreign)) fields.survey(claim.chunkKey(), 1000);
        var map = new NationMapPayload(List.of(), List.of(new NationMapPayload.ClaimView(0, -1, 2),
                new NationMapPayload.ClaimView(0, 0, 2)), 70);
        test.assertTrue(fields.visible(map, claims, dimension, owner).equals(List.of(nearby.chunkKey(), distant.chunkKey())),
                "Show only surveyed local claims and remote claims of the viewer");
        claims.transfer(distant, other);
        test.assertTrue(fields.visible(map, claims, dimension, owner).equals(List.of(nearby.chunkKey())),
                "Remote visibility follows current ownership");
        test.succeed();
    }

    @GameTest(template="empty")
    public void depletedConcessionRetainsItsQuotaAcrossSurveys(GameTestHelper test) {
        long key = ChunkPos.asLong(7, -11);
        int limit = ModServerConfig.OIL_EXPORT_QUOTA.get();
        var entry = new CompoundTag();
        entry.putLong("Chunk", key);
        entry.putInt("Reading", 1500);
        entry.putLong("Day", 4);
        entry.putInt("Exported", limit);
        var list = new net.minecraft.nbt.ListTag();
        list.add(entry);
        var data = new CompoundTag();
        data.put("Fields", list);
        var fields = OilFields.load(data, test.getLevel().registryAccess());
        fields.survey(key, 0);
        test.assertTrue(fields.discovered(key) && fields.reading(key) == 0, "Previously discovered fields can run dry");
        test.assertTrue(fields.remaining(key, 4) == 0 && fields.remaining(key, 5) == limit, "Resurveying cannot renew today's quota");
        var tank = new Petroleum.Terminal("ready", 0, fields.remaining(key, 4), BlockPos.ZERO, Petroleum.Grade.CRUDE, 1000, null);
        test.assertTrue(tank.problem(1).equals("quota"), "A full tank cannot bypass an exhausted quota");
        test.succeed();
    }

    @GameTest(template="empty")
    public void nativeScannerRegistersWhereTheReadingFinishes(GameTestHelper test) throws Exception {
        if (!Petroleum.enabled()) {
            test.succeed();
            return;
        }
        var level = test.getLevel();
        var service = ModServer.nations();
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "ScannerUser"));
        var nation = service.nations().create(UUID.randomUUID(), "Meridian", player.getUUID(), 0, 0);
        var start = new ChunkPos(test.absolutePos(BlockPos.ZERO));
        var origin = new ChunkPos(start.x + 6, start.z + 6);
        var destination = new ChunkPos(origin.x + 1, origin.z);
        String dim = level.dimension().location().toString();
        var claim = new Claim(dim, destination.x, destination.z, nation.id(), 0);
        service.claims().claim(claim);
        try {
            var fields = OilFields.of(level);
            var scanner = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("createdieselgenerators:oil_scanner")));
            player.setItemInHand(InteractionHand.OFF_HAND, scanner);
            player.moveTo(destination.getMiddleBlockX(), level.getMinBuildHeight() + 5, destination.getMiddleBlockZ());
            var commands = level.getServer().getCommands().getDispatcher();
            var source = player.createCommandSourceStack().withPermission(4);
            commands.execute("cdg oil set 4001784", source);
            player.moveTo(origin.getMiddleBlockX(), player.getY(), origin.getMiddleBlockZ());
            scanner.use(level, player, InteractionHand.OFF_HAND);
            for (int tick = 0; tick < 20; tick++) scanner.inventoryTick(level, player, 40, false);
            test.assertFalse(fields.discovered(origin.toLong()), "Starting a scan cannot register the origin");
            player.moveTo(destination.getMiddleBlockX(), player.getY(), destination.getMiddleBlockZ());
            scanner.inventoryTick(level, player, 40, false);
            test.assertTrue(fields.reading(destination.toLong()) == 4001784, "The native offhand reading registers the destination's exact mB");
            commands.execute("cdg oil set 0", source);
            scanner.inventoryTick(level, player, 40, false);
            test.assertTrue(fields.reading(destination.toLong()) == 4001784, "Carrying a completed scanner cannot resurvey the terrain");
            scanner.use(level, player, InteractionHand.OFF_HAND);
            for (int tick = 0; tick <= 20; tick++) scanner.inventoryTick(level, player, 40, false);
            test.assertTrue(fields.discovered(destination.toLong()) && fields.reading(destination.toLong()) == 0,
                    "A completed dry resurvey correctly updates a depleted concession");
        } finally {
            service.claims().release(dim, destination.x, destination.z);
            service.nations().remove(nation.id());
        }
        test.succeed();
    }

    @GameTest(template="empty")
    public void petroleumConcessionUsesTheAddonsOilAndTank(GameTestHelper test) throws Exception {
        var level = test.getLevel();
        var service = ModServer.nations();
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "OilSurveyor"));
        var nation = service.nations().create(UUID.randomUUID(), "Petrolia", player.getUUID(), 0, 0);
        var start = test.absolutePos(BlockPos.ZERO);
        var chunk = new ChunkPos((start.getX() >> 4) + 3, (start.getZ() >> 4) + 1);
        var pos = new BlockPos(chunk.getMiddleBlockX(), start.getY() + 4, chunk.getMiddleBlockZ());
        var dim = level.dimension().location().toString();
        var claim = new Claim(dim, chunk.x, chunk.z, nation.id(), 0);
        service.claims().claim(claim);
        try {
            player.moveTo(pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5, 0, 90);
            if (!Petroleum.enabled()) {
                test.assertTrue(Petroleum.survey(player, nation).contains("Create"), "Base game offers the optional integration");
                test.succeed();
                return;
            }
            var fields = OilFields.of(level);
            Petroleum.survey(player, nation);
            test.assertFalse(fields.discovered(chunk.toLong()), "A survey requires an actual oil scanner");
            var scanner = BuiltInRegistries.ITEM.get(ResourceLocation.parse("createdieselgenerators:oil_scanner"));
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(scanner));
            var dispatcher = level.getServer().getCommands().getDispatcher();
            var source = player.createCommandSourceStack().withPermission(4);
            dispatcher.execute("cdg oil set 0", source);
            Petroleum.survey(player, nation);
            test.assertFalse(fields.discovered(chunk.toLong()), "A dry survey cannot open an export terminal");
            dispatcher.execute("cdg oil set 12500", source);
            player.getCooldowns().removeCooldown(scanner);
            Petroleum.survey(player, nation);
            test.assertTrue(fields.reading(chunk.toLong()) == 12500, "Reserve comes directly from Diesel Generators");
            int limit = ModServerConfig.OIL_EXPORT_QUOTA.get();
            for (var vessel : new String[]{"create:fluid_tank", "createdieselgenerators:oil_barrel"}) {
                var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(vessel));
                level.setBlockAndUpdate(pos, block.defaultBlockState());
                var handler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
                var atTank = Petroleum.inspect(player, nation);
                test.assertTrue(atTank.status().equals("empty"), vessel + " is a supported physical terminal: " + atTank.status());
                for (var grade : Petroleum.Grade.values()) {
                    var fluid = BuiltInRegistries.FLUID.get(ResourceLocation.parse("createdieselgenerators:" + grade.id));
                    handler.fill(new FluidStack(fluid, 2500), FluidAction.EXECUTE);
                    var tank = Petroleum.inspect(player, nation);
                    test.assertTrue(tank.grade() == grade && !tank.problem(3).isEmpty(), "Fractional buckets cannot be oversold");
                    var quote = Petroleum.quote(player, tank, 2);
                    handler.drain(1000, FluidAction.EXECUTE);
                    test.assertFalse(Petroleum.confirm(player, nation, quote), "Another pipe can invalidate the quote");
                    handler.fill(new FluidStack(fluid, 1000), FluidAction.EXECUTE);
                    long before = nation.economy().balance();
                    test.assertTrue(Petroleum.confirm(player, nation, quote) && nation.economy().balance() == before + 2 * grade.price(),
                            "Credit exactly the accepted export");
                    test.assertTrue(handler.getFluidInTank(0).getAmount() == 500, "Keep the physical remainder");
                    handler.drain(500, FluidAction.EXECUTE);
                }
                level.removeBlock(pos, false);
            }
            long day = level.getServer().overworld().getGameTime() / 24000;
            var saved = OilFields.load(fields.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
            test.assertTrue(saved.reading(chunk.toLong()) == 12500 && saved.remaining(chunk.toLong(), day) == limit - 12
                    && saved.remaining(chunk.toLong(), day + 1) == limit, "Survey and quota survive saving and renew next day");
            service.claims().transfer(claim, UUID.randomUUID());
            test.assertTrue(Petroleum.inspect(player, nation).status().equals("land"), "A former owner cannot keep exporting");
            service.claims().transfer(claim, nation.id());
            service.disputes().add(new Dispute(service.disputes().nextId(), dim, chunk.x, chunk.z, UUID.randomUUID(), nation.id(), 0, 1000));
            test.assertTrue(Petroleum.inspect(player, nation).status().equals("disputed"), "A dispute suspends exports");
            service.disputeService().cancelOpenAt(dim, chunk.x, chunk.z, 0, "test");
            test.assertTrue(fields.remaining(chunk.toLong(), day) == limit - 12, "Changing hands does not refresh the allowance");
        } finally {
            level.removeBlock(pos, false);
            service.claims().release(dim, chunk.x, chunk.z);
            service.nations().remove(nation.id());
        }
        test.succeed();
    }
}
