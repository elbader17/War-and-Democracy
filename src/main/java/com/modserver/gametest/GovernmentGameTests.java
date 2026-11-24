package com.modserver.gametest;

import com.modserver.ModServer;
import com.modserver.claim.Claim;
import com.modserver.claim.ClaimIndex;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.common.util.FakePlayer;
import com.modserver.data.ModServerStore;
import com.modserver.dispute.DisputeIndex;
import com.modserver.government.CapitalChest;
import com.modserver.government.Commodity;
import com.modserver.nation.NationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.nio.file.Files;
import java.util.UUID;

@GameTestHolder("modserver")
@PrefixGameTestTemplate(false)
public final class GovernmentGameTests {
    @GameTest(template = "empty")
    public void reserveFollowsItsPhysicalChestAndClaim(GameTestHelper test) throws Exception {
        var level = test.getLevel();
        var service = ModServer.nations();
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "ReserveTester"));
        var origin = test.absolutePos(BlockPos.ZERO);
        int x = (origin.getX() >> 4) + 4, z = (origin.getZ() >> 4) + 4;
        var anchor = new BlockPos(x * 16 + 15, origin.getY() + 6, z * 16 + 8);
        var other = anchor.east();
        var dim = level.dimension().location().toString();
        var dispatcher = level.getServer().getCommands().getDispatcher();
        var source = player.createCommandSourceStack();
        player.moveTo(origin.getX(), origin.getY(), origin.getZ(), 0, 0);
        dispatcher.execute("nacion crear República Democrática", source);
        var nation = service.nationOf(player.getUUID()).orElseThrow();
        test.assertTrue(nation.name().equals("República Democrática"), "Create a multiword country from chat");
        var claim = new Claim(dim, x, z, nation.id(), 0);
        var neighbor = new Claim(dim, x + 1, z, nation.id(), 0);
        service.claims().claim(claim);
        try {
            var state = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
            int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
            level.setBlock(anchor, state.setValue(ChestBlock.TYPE, ChestType.LEFT), flags);
            level.setBlock(other, state.setValue(ChestBlock.TYPE, ChestType.RIGHT), flags);
            var stock = (ChestBlockEntity) level.getBlockEntity(other);
            stock.setItem(0, new ItemStack(Items.GOLD_INGOT, 7));
            player.moveTo(anchor.getX() + .5, anchor.getY() + 1, anchor.getZ() + .5, 0, 90);
            test.assertTrue(dispatcher.execute("nacion reserva", source) == 0, "Reject an unowned second half");
            service.claims().claim(neighbor);
            test.assertTrue(dispatcher.execute("nation reserve", source) == 1, "Bind a chest outside the capital");
            var access = CapitalChest.access(player, nation);
            test.assertTrue(CapitalChest.count(access.inventory(), Commodity.GOLD) == 7, "Read pre-existing stock from the second half");
            player.moveTo(other.getX() + .5, other.getY() + 1, other.getZ() + .5, 0, 90);
            dispatcher.execute("nacion reserva", source);
            test.assertTrue(access.seal().equals(CapitalChest.access(player, nation).seal()), "Either half identifies the same chest");
            var balance = nation.economy().balance();
            test.assertTrue(CapitalChest.sell(access.inventory(), nation, Commodity.GOLD, 3, Commodity.GOLD.price()), "Sell reserve stock");
            test.assertTrue(stock.getItem(0).getCount() == 4 && nation.economy().balance() == balance + 3 * Commodity.GOLD.price(), "Debit stock and credit coins");
            service.claims().release(dim, x + 1, z);
            test.assertTrue(CapitalChest.access(player, nation).inventory() == null, "Losing either claim stops access");
            level.removeBlock(anchor, false);
            level.setBlock(anchor, state, flags);
            test.assertTrue(CapitalChest.access(player, nation).inventory() == null, "Replacement chests need an explicit binding");
        } finally {
            service.claims().release(dim, x, z);
            service.claims().release(dim, x + 1, z);
            level.removeBlock(anchor, false);
            level.removeBlock(other, false);
            service.nations().remove(nation.id());
        }
        test.succeed();
    }

    @GameTest(template = "empty")
    public void sellingUsesOnlyPlainStockAtTheQuotedRate(GameTestHelper test) {
        var nations = new NationManager();
        var country = nations.create(UUID.randomUUID(), "Oak", UUID.randomUUID(), 0, 0);
        var chest = new SimpleContainer(4);
        var heirloom = new ItemStack(Items.IRON_INGOT, 8);
        heirloom.set(DataComponents.CUSTOM_NAME, Component.literal("Heirloom"));
        chest.setItem(0, heirloom);
        chest.setItem(1, new ItemStack(Items.IRON_INGOT, 2));
        chest.setItem(2, new ItemStack(Items.IRON_INGOT, 3));
        chest.setItem(3, new ItemStack(Items.GOLD_INGOT, 7));
        long price = Commodity.IRON.price();
        test.assertTrue(CapitalChest.count(chest, Commodity.IRON) == 5, "Only plain iron counts");
        test.assertFalse(CapitalChest.sell(chest, country, Commodity.IRON, 6, price), "Reject missing stock");
        test.assertFalse(CapitalChest.sell(chest, country, Commodity.IRON, 2, price + 1), "Reject stale prices");
        test.assertTrue(CapitalChest.sell(chest, country, Commodity.IRON, 4, price), "Sell across slots");
        test.assertTrue(country.economy().balance() == 4 * price, "Pay the quoted amount");
        test.assertTrue(chest.getItem(0).getCount() == 8 && chest.getItem(3).getCount() == 7, "Leave unrelated items alone");
        test.assertTrue(CapitalChest.count(chest, Commodity.IRON) == 1, "Remove exactly the sold quantity");
        country.economy().setBalance(Long.MAX_VALUE);
        test.assertFalse(CapitalChest.sell(chest, country, Commodity.IRON, 1, price), "Reject balance overflow");
        test.assertTrue(CapitalChest.count(chest, Commodity.IRON) == 1, "Rejected sales preserve the chest");
        test.succeed();
    }

    @GameTest(template = "empty")
    public void governmentSurvivesRenameAndReload(GameTestHelper test) throws Exception {
        var manager = new NationManager();
        var country = manager.create(UUID.randomUUID(), "Maple", UUID.randomUUID(), 0, 0);
        manager.create(UUID.randomUUID(), "Rival", UUID.randomUUID(), 0, 0);
        test.assertFalse(manager.rename(country, "rIVaL"), "Reject another nation's name");
        test.assertTrue(manager.rename(country, "República Democrática"), "Rename succeeds");
        test.assertTrue(manager.byName("Maple").isEmpty() && manager.byName("REPÚBLICA DEMOCRÁTICA").orElseThrow() == country, "Update both name lookups");
        country.setPreamble("We govern together.");
        country.setDoctrines("Keep a reserve.");
        country.setCapital("minecraft:overworld", -2, 3);
        country.setTreasuryChest("minecraft:the_nether", new BlockPos(-21, 70, 54).asLong());
        var directory = Files.createTempDirectory("modserver-government-test");
        try {
            var store = new ModServerStore(directory);
            store.save(manager, new ClaimIndex(), new DisputeIndex());
            var restored = new NationManager();
            store.load(restored, new ClaimIndex(), new DisputeIndex());
            var saved = restored.byId(country.id()).orElseThrow();
            test.assertTrue(saved.name().equals(country.name()) && saved.preamble().equals(country.preamble())
                    && saved.treasuryDimension().equals("minecraft:the_nether") && saved.doctrines().equals(country.doctrines()) && saved.treasuryChest().equals(country.treasuryChest()),
                    "Persist the government's identity, charter and chest binding");
        } finally {
            try (var files = Files.list(directory)) {
                for (var file : files.toList()) Files.delete(file);
            }
            Files.delete(directory);
        }
        test.succeed();
    }
}
