package com.modserver.government;

import com.modserver.ServerLanguage;
import com.modserver.network.GovernmentView;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import java.util.ArrayList;
import java.util.List;

/** Vanilla fallback. The packet sequence displays an immutable book without touching the real offhand. */
final class GovernmentBook {
    static void show(ServerPlayer player, GovernmentView v) {
        var lang = ServerLanguage.of(player);
        List<Filterable<Component>> pages = new ArrayList<>();
        if (!v.notice().isBlank()) player.sendSystemMessage(Component.literal(v.notice()));
        if (!v.token().isEmpty()) {
            var offer = heading(lang.text("gov.review")).append(v.offer()).append("\n\n");
            offer.append(link(lang.text("gov.confirm"), "confirm " + v.token())).append("\n").append(link(lang.text("gov.cancel"), "cancel"));
            pages.add(Filterable.passThrough(offer));
        }
        var home = heading(v.name()).append(lang.text("gov.land",v.land(),v.capital())).append("\n")
                .append(lang.text("gov.wallet",lang.coins(v.balance()))).append("\n")
                .append(lang.text("gov.stockValue",lang.coins(v.stockValue()))).append("\n\n");
        for (String key : new String[]{"name","preamble","doctrines"})
            home.append(link(lang.text("gov."+key),"edit "+key)).append("\n");
        home.append(command(lang.text("council.title"), "/nation council"));
        pages.add(Filterable.passThrough(home));
        var territory = heading(lang.text("gov.territory")).append(lang.text("gov.here", v.localChunk())).append("\n")
                .append(lang.text("gov.holder", v.localOwner())).append("\n\n")
                .append(link(lang.text("gov.claim"), "claim")).append("\n").append(link(lang.text("gov.release"), "release")).append("\n")
                .append(command(lang.text("gov.openMap"), "/nation map"));
        pages.add(Filterable.passThrough(territory));
        var market = heading(lang.text("gov.market"));
        for (var row : v.rows()) {
            market.append(lang.text("gov."+row.id())+" · "+row.count()+" × "+lang.coins(row.price())+"\n");
            market.append(link(lang.text("gov.one"),"sell "+row.id()+" 1")).append("  ")
                    .append(link(lang.text("gov.all"),"sell "+row.id())).append("\n");
        }
        pages.add(Filterable.passThrough(market));
        var chest = heading(lang.text("gov.market")).append(lang.text("gov.stockValue",lang.coins(v.stockValue()))).append("\n")
                .append(v.chestStatus().equals("ready") ? lang.text("gov.location",v.chest()) : lang.text("gov.chest."+v.chestStatus()));
        chest.append("\n\n").append(link(lang.text("gov.bind"),"bind")).append("\n").append(link(lang.text("gov.refresh"),"refresh"));
        pages.add(Filterable.passThrough(chest));
        var military = heading(lang.text("gov.military"));
        military.append(lang.text("gov.state",v.guardLevel(),v.guards()))
                .append("\n"+lang.text("gov.guardCost",lang.coins(v.guardCost()))+"\n").append(link(lang.text("gov.garrison"),"garrison"))
                .append("\n"+lang.text("tac.panel")+"\n")
                .append(command(lang.text("tac.title"),"/nation tactics"));
        pages.add(Filterable.passThrough(military));
        for (String field : new String[]{"preamble","doctrines"}) {
            String value = field.equals("preamble") ? v.preamble() : v.doctrines();
            if (value.isEmpty()) value = lang.text("gov.empty");
            for (int start=0;start<value.length();start+=100) {
                var page = heading(lang.text("gov."+field)).append(value.substring(start,Math.min(start+100,value.length())));
                pages.add(Filterable.passThrough(page));
            }
        }
        if (v.oil() != null) {
            var oil = v.oil();
            var surveys = heading(lang.text("gov.oil.title"));
            var reserves = oil.reserves();
            String amount = reserves.bottomless() ? lang.text("gov.oil.unlimited")
                    : lang.text("gov.oil.volume", lang.number(reserves.finiteMillibuckets()));
            surveys.append(reserves.fields() == 0 ? lang.text("gov.oil.nationalUncharted")
                    : lang.text("gov.oil.national", amount, reserves.fields())).append("\n\n");
            surveys.append(oil.reading() < 0 ? lang.text("gov.oil.uncharted") : oil.reading() == Integer.MAX_VALUE
                    ? lang.text("gov.oil.infinite") : lang.text("gov.oil.reading", lang.number(oil.reading())));
            var page = heading(lang.text("gov.oil.title")).append(lang.text("gov.oil.allowance", oil.remaining())).append("\n");
            page.append(oil.status().equals("ready") ? lang.text("gov.oil.tank", lang.text("gov.oil." + oil.grade()), oil.millibuckets())
                    : lang.text("gov.oil." + oil.status())).append("\n");
            page.append(link(lang.text("gov.oil.survey"), "oil survey")).append("\n");
            if (oil.status().equals("ready")) page.append(link(lang.text("gov.oil.exportOne"), "oil export 1"));
            int index = v.action().equals("oil") ? 0 : pages.size();
            pages.add(index, Filterable.passThrough(surveys));
            pages.add(index + 1, Filterable.passThrough(page));
        }
        var book = Items.WRITTEN_BOOK.getDefaultInstance();
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(v.name()),"War & Democracy",0,pages,true));
        player.connection.send(new ClientboundContainerSetSlotPacket(-2,0,Inventory.SLOT_OFFHAND,book));
        player.connection.send(new ClientboundOpenBookPacket(InteractionHand.OFF_HAND));
        player.connection.send(new ClientboundContainerSetSlotPacket(-2,0,Inventory.SLOT_OFFHAND,player.getOffhandItem()));
    }
    private static MutableComponent heading(String text) {
        return Component.empty().append(Component.literal(text+"\n\n").withStyle(ChatFormatting.BOLD));
    }
    private static Component link(String label,String args) { return command(label,"/nation panel "+args); }
    private static Component command(String label,String command) {
        return Component.literal(label).withStyle(s -> s.withColor(ChatFormatting.DARK_BLUE).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,command)));
    }
    private GovernmentBook() {}
}
