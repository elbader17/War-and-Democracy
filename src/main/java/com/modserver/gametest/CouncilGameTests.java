package com.modserver.gametest;

import com.modserver.NationService;
import com.modserver.ModServer;
import com.mojang.authlib.GameProfile;
import com.modserver.claim.Claim;
import com.modserver.council.Council;
import com.modserver.council.CouncilDesk;
import com.modserver.council.CouncilPost;
import com.modserver.council.CouncilPost.Kind;
import com.modserver.data.ModServerStore;
import com.modserver.government.CapitalChest;
import com.modserver.government.Commodity;
import com.modserver.military.ArmyCombat;
import com.modserver.military.TroopTransfer;
import com.modserver.nation.Nation;
import com.modserver.nation.NationRelation;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.nio.file.Files;
import java.util.UUID;

@GameTestHolder("modserver")
@PrefixGameTestTemplate(false)
public class CouncilGameTests {
    private final NationService service = new NationService();
    private final Nation sender = nation("North"), recipient = nation("South"), outsider = nation("Islands");
    private Nation nation(String name) {
        return service.nations().create(UUID.randomUUID(),name,UUID.randomUUID(),0,0);
    }

    @GameTest(template="empty")
    public void ratificationUnlocksRealTimedReinforcements(GameTestHelper h) {
        String dim="minecraft:overworld";
        var origin=new Claim(dim,0,0,sender.id(),0);
        origin.setArmies(8);
        var destination=new Claim(dim,3,0,recipient.id(),0);
        service.claims().claim(origin); service.claims().claim(destination);
        var proposal=CouncilPost.note(sender.id(),recipient.id(),Kind.ALLY,"");
        CouncilDesk.propose(service,sender,recipient,proposal);
        h.assertTrue(Council.convoyDestination(service,sender,recipient,dim)==null,"An unsigned alliance grants no help route");
        h.assertTrue(CouncilDesk.decide(service,outsider,sender,proposal.id(),"accept",null,null).equals("gone"),"Only the named recipient can sign");
        h.assertTrue(CouncilDesk.decide(service,recipient,sender,proposal.id(),"accept",null,null).equals("ratified"),"Both parties sign");
        h.assertTrue(TroopTransfer.allied(sender,recipient) && destination==Council.convoyDestination(service,sender,recipient,dim),"The council selects an allied destination");
        h.assertTrue(ArmyCombat.execute(service,sender,dim,ArmyCombat.Order.SEND,0,0,3,0,4,0).success(),"Existing convoy execution accepts the new treaty");
        h.assertTrue(destination.armies()==1 && sender.transfers().iterator().next().dueAt()==36_000,"Three chunks take thirty minutes");
        h.assertTrue(CouncilDesk.decide(service,recipient,sender,proposal.id(),"accept",null,null).equals("gone"),"A signature cannot be replayed");
        h.succeed();
    }

    @GameTest(template="empty")
    public void tradeRechecksBothChestsAndSettlesOnlyOnce(GameTestHelper h) {
        var source=new SimpleContainer(2);
        var target=new SimpleContainer(1);
        source.setItem(0,new ItemStack(Items.IRON_INGOT,20));
        target.setItem(0,new ItemStack(Items.IRON_INGOT,60));
        var seller=new CapitalChest.Access(source,UUID.randomUUID(),"ready");
        var buyer=new CapitalChest.Access(target,UUID.randomUUID(),"ready");
        var offer=new CouncilPost(UUID.randomUUID(),sender.id(),recipient.id(),Kind.TRADE,Commodity.IRON,8,17,seller.seal(),"");
        CouncilDesk.propose(service,sender,recipient,offer);
        h.assertTrue(CouncilDesk.decide(service,recipient,sender,offer.id(),"accept",seller,buyer).equals("funds"),"Insufficient funds leave the offer open");
        recipient.economy().setBalance(2500);
        h.assertTrue(CouncilDesk.decide(service,recipient,sender,offer.id(),"accept",seller,buyer).equals("space"),"A full buyer chest refuses the whole sale");
        h.assertTrue(sender.economy().balance()==0 && source.getItem(0).getCount()==20 && target.getItem(0).getCount()==60,"Failed sale has no partial effects");
        target.setItem(0,new ItemStack(Items.IRON_INGOT,51));
        h.assertTrue(CouncilDesk.decide(service,recipient,sender,offer.id(),"accept",new CapitalChest.Access(source,UUID.randomUUID(),"ready"),buyer).equals("chests"),"Rebinding invalidates the offered chest");
        h.assertTrue(CouncilDesk.decide(service,recipient,sender,offer.id(),"accept",seller,buyer).equals("settled"),"A valid sale completes");
        h.assertTrue(CouncilDesk.decide(service,recipient,sender,offer.id(),"accept",seller,buyer).equals("gone"),"Repeated acceptance is harmless");
        h.assertTrue(sender.economy().balance()==1700 && recipient.economy().balance()==800 && source.getItem(0).getCount()==12 && target.getItem(0).getCount()==59,"Goods and coins change hands exactly once");
        h.succeed();
    }

    @GameTest(template="empty")
    public void peaceRecallsOnlyTheSignatoriesArmies(GameTestHelper h) {
        String dim="minecraft:overworld";
        var base=new Claim(dim,0,0,sender.id(),0);
        var target=new Claim(dim,1,0,recipient.id(),0);
        var third=new Claim(dim,0,1,outsider.id(),0);
        base.setArmies(12);
        for(var c:java.util.List.of(base,target,third)) service.claims().claim(c);
        ArmyCombat.execute(service,sender,dim,ArmyCombat.Order.ROLL,0,0,1,0,4,0);
        ArmyCombat.execute(service,sender,dim,ArmyCombat.Order.ROLL,0,0,0,1,3,0);
        var peace=CouncilPost.note(sender.id(),recipient.id(),Kind.PEACE,"");
        CouncilDesk.propose(service,sender,recipient,peace);
        h.assertTrue(service.disputes().open().size()==2,"Negotiations do not interrupt combat");
        CouncilDesk.decide(service,recipient,sender,peace.id(),"accept",null,null);
        h.assertTrue(service.disputes().openAt(dim,1,0)==null && base.armies()==9,"Signing recalls the surviving troops");
        h.assertTrue(service.disputes().openAt(dim,0,1)!=null && sender.relationWith(outsider.id())==NationRelation.WAR,"Other nations remain at war");
        h.succeed();
    }

    @GameTest(template="empty")
    public void councilCommandsDeliverBetweenPhysicalReserves(GameTestHelper h) throws Exception {
        var server = ModServer.nations();
        var level = h.getLevel();
        var author = new FakePlayer(level, new GameProfile(UUID.randomUUID(),"CouncilSeller"));
        var buyer = new FakePlayer(level, new GameProfile(UUID.randomUUID(),"CouncilBuyer"));
        var sellerNation = server.nations().create(UUID.randomUUID(),"Merchant Coast",author.getUUID(),0,0);
        var buyerNation = server.nations().create(UUID.randomUUID(),"Copper Isles",buyer.getUUID(),0,0);
        var origin = h.absolutePos(BlockPos.ZERO);
        var a = new BlockPos(((origin.getX() >> 4)+9)*16+8,origin.getY()+4,((origin.getZ() >> 4)+8)*16+8);
        var b = a.east(16);
        var dim = level.dimension().location().toString();
        var commands = level.getServer().getCommands().getDispatcher();
        try {
            for (var p : java.util.List.of(author,buyer)) {
                boolean selling = p == author;
                var n = selling ? sellerNation : buyerNation;
                var pos = selling ? a : b;
                server.claims().claim(new Claim(dim,pos.getX()>>4,pos.getZ()>>4,n.id(),0));
                level.setBlockAndUpdate(pos,Blocks.CHEST.defaultBlockState());
                p.moveTo(pos.getX()+.5,pos.getY()+1,pos.getZ()+.5,0,90);
                h.assertTrue(commands.execute("nation reserve",p.createCommandSourceStack())==1,"Each owner binds a real reserve chest");
            }
            CapitalChest.access(author,sellerNation).inventory().setItem(0,new ItemStack(Items.GOLD_INGOT,8));
            buyerNation.economy().setBalance(2000);
            h.assertTrue(commands.execute("nation council trade \"Copper Isles\" gold 5 13",author.createCommandSourceStack())==1,"Seller posts terms from the council");
            UUID offer = buyerNation.register().stream().filter(p -> p.kind()==Kind.TRADE).findFirst().orElseThrow().id();
            h.assertTrue(commands.execute("nacion consejo accept \"Merchant Coast\" "+offer,buyer.createCommandSourceStack())==1,"Buyer ratifies the named offer");
            h.assertTrue(CapitalChest.count(CapitalChest.access(author,sellerNation).inventory(),Commodity.GOLD)==3
                    && CapitalChest.count(CapitalChest.access(buyer,buyerNation).inventory(),Commodity.GOLD)==5
                    && sellerNation.economy().balance()==1300 && buyerNation.economy().balance()==700,"The ordinary chests and treasuries reflect the sale");
            commands.execute("nation council letter \"Copper Isles\" Goods received?",author.createCommandSourceStack());
            h.assertTrue(buyerNation.register().stream().anyMatch(p -> p.text().equals("Goods received?")), "Private mail reaches the correct country");
            commands.execute("nation council ally \"Copper Isles\"",author.createCommandSourceStack());
            commands.execute("nation alliance \"Merchant Coast\"",buyer.createCommandSourceStack());
            commands.execute("nation council circular \"Copper Isles\" Rally at dawn",author.createCommandSourceStack());
            h.assertTrue(buyerNation.register().stream().anyMatch(p -> p.kind()==Kind.CIRCULAR && p.text().equals("Rally at dawn")), "Allies receive the circular after signing");
        } finally {
            for(var pos:java.util.List.of(a,b)) {
                level.removeBlock(pos,false);
                server.claims().release(dim,pos.getX()>>4,pos.getZ()>>4);
            }
            server.nations().remove(sellerNation.id());
            server.nations().remove(buyerNation.id());
        }
        h.succeed();
    }

    @GameTest(template="empty")
    public void offlineCorrespondenceAndOffersSurviveRestart(GameTestHelper h) throws Exception {
        var proposal=CouncilPost.note(sender.id(),recipient.id(),Kind.PACT,"");
        CouncilDesk.propose(service,sender,recipient,proposal);
        CouncilDesk.correspond(recipient,sender,Kind.LETTER,"Meet at the harbor");
        var dir=Files.createTempDirectory("council-mail");
        try {
            var store=new ModServerStore(dir);
            store.save(service.nations(),service.claims(),service.disputes());
            var copy=new NationService();
            store.load(copy.nations(),copy.claims(),copy.disputes());
            var north=copy.nations().byId(sender.id()).orElseThrow();
            var south=copy.nations().byId(recipient.id()).orElseThrow();
            h.assertTrue(north.register().size()==1 && south.register().size()==2 && copy.nations().byId(outsider.id()).orElseThrow().register().isEmpty(),"Mail is private and durable");
            h.assertTrue(CouncilDesk.decide(copy,north,south,proposal.id(),"withdraw",null,null).equals("withdrawn"),"Sender can withdraw an outstanding offer after restart");
            h.assertTrue(south.relationWith(north.id())==NationRelation.NEUTRAL && south.register().stream().noneMatch(p->p.kind().offer()),"Withdrawing changes no treaty");
        } finally {
            try(var files=Files.list(dir)) { for(var f:files.toList()) Files.delete(f); }
            Files.delete(dir);
        }
        h.succeed();
    }
}
