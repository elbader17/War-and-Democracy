package com.modserver.client;

import com.modserver.government.Commodity;
import com.modserver.network.GovernmentView;
import com.modserver.network.GovernmentRequest;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import java.math.BigDecimal;

/** Only the server can change a profile, debit the treasury or remove stock. */
public final class GovernmentScreen extends Screen {
    private enum Tab {
        COUNTRY("country", Items.WRITABLE_BOOK), LAND("territory", Items.MAP), MARKET("market", Items.GOLD_INGOT),
        MILITARY("oil.militaryTab", Items.SHIELD), OIL("oil.title", Items.BUCKET);
        final String key;
        final Item icon;
        Tab(String key, Item icon) { this.key = key; this.icon = icon; }
    }
    private GovernmentView data;
    private Tab tab = Tab.COUNTRY;
    private String field = "", notice = "";
    private int selected, left, top, w, ticks;
    private EditBox input;
    private String oilQuantity = "1";
    private Button export;
    private MultiLineEditBox paragraph;
    private boolean waiting;
    private String submitted = "";

    private GovernmentScreen(GovernmentView data) {
        super(tr("title"));
        this.data = data;
        if (data.action().equals("oil") && data.oil() != null) tab = Tab.OIL;
    }
    public static void accept(GovernmentView view) {
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof GovernmentScreen s) {
            boolean changed = !s.data.token().equals(view.token());
            if ((s.data.oil() == null) != (view.oil() == null)) changed = true;
            if (view.action().equals("oil") && view.oil() != null) { s.tab = Tab.OIL; s.field = ""; changed = true; }
            if (view.oil() == null && s.tab == Tab.OIL) s.tab = Tab.COUNTRY;
            s.data = view;
            if (!view.action().equals("refresh")) {
                s.waiting = false;
                s.notice = view.notice();
                if (!s.field.isEmpty() && s.field.equals(view.action()) && s.profileText(s.field).equals(s.submitted)) {
                    s.field = "";
                    changed = true;
                }
            }
            if (changed) s.rebuildWidgets();
        } else if (view.action().equals("open") || view.action().equals("oil")) {
            mc.setScreen(new GovernmentScreen(view));
        } else if (mc.player != null && !view.action().equals("refresh")) {
            if (!view.notice().isEmpty()) mc.player.sendSystemMessage(Component.literal(view.notice()));
            if (!view.token().isEmpty()) mc.player.sendSystemMessage(Component.literal(view.offer()).withStyle(style ->
                    style.withUnderlined(true).withClickEvent(new net.minecraft.network.chat.ClickEvent(
                            net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/nation panel confirm " + view.token()))));
        }
    }
    private static Component tr(String key, Object... args) { return Component.translatable("gov." + key, args); }
    private String volume(long mb) { return String.format(minecraft.getLanguageManager().getJavaLocale(), "%,d", mb); }
    private String coins(long n) { return String.format(minecraft.getLanguageManager().getJavaLocale(), "%.2f", BigDecimal.valueOf(n, 2)); }
    private void send(String command) {
        if (waiting || minecraft.getConnection() == null) return;
        waiting = true;
        PacketDistributor.sendToServer(new GovernmentRequest(command));
    }
    @Override public void tick() {
        if (tab == Tab.OIL && export != null && data.oil() != null) {
            var oil = data.oil();
            int qty = oilBuckets();
            export.active = !waiting && oil.status().equals("ready") && qty > 0 && qty <= oil.remaining() && qty <= oil.millibuckets() / 1000;
        }
        if (++ticks % 40 == 0 && !waiting && field.isEmpty() && minecraft.getConnection() != null)
            PacketDistributor.sendToServer(new GovernmentRequest("refresh"));
    }
    private int tabCount() { return data.oil() == null ? 4 : 5; }
    private int oilBuckets() {
        try { return Integer.parseInt(oilQuantity); } catch (NumberFormatException ignored) { return 0; }
    }
    private void go(Tab next) { tab = next; field = ""; notice = ""; rebuildWidgets(); }
    private void edit(String name) { field = name; notice = ""; rebuildWidgets(); }
    private Button button(int x, int y, int width, Component text, Runnable action) {
        return addRenderableWidget(CampaignStyle.button(left+x, top+y, width, 18, text, b -> action.run()));
    }
    private EditBox box(int x, int y, int width, String key, String value, int limit) {
        var box = CampaignStyle.entry(font, left+x, top+y, width, 18, tr(key));
        box.setMaxLength(limit); box.setValue(value); box.setHint(tr(key));
        return addRenderableWidget(box);
    }
    private String profileText(String name) {
        return switch (name) { case "name" -> data.name(); case "preamble" -> data.preamble(); default -> data.doctrines(); };
    }
    @Override protected void init() {
        w = Math.min(410, width - 10); left = (width-w)/2; top = (height-226)/2;
        int inner = w-20;
        if (!data.token().isEmpty()) {
            int half = (inner-4)/2;
            button(10, 185, half, tr("cancel"), () -> send("cancel"));
            button(14+half, 185, inner-4-half, tr("confirm"), () -> send("confirm " + data.token()));
            return;
        }
        int tw = inner/tabCount();
        for (Tab t : Tab.values()) {
            int i = t.ordinal();
            if (i >= tabCount()) continue;
            addRenderableWidget(CampaignStyle.indexTab(left+10+i*tw, top+33, tw-2, 20, tr(t.key), t.icon,
                    t == tab, b -> { if (t != tab) go(t); }));
        }
        if (!field.isEmpty()) {
            if (field.equals("name")) {
                input = box(10, 88, inner, field, profileText(field), 24); setInitialFocus(input);
            } else {
                paragraph = addRenderableWidget(new MultiLineEditBox(font, left+10, top+86, inner, 67, tr(field), tr(field)) {
                    @Override protected void renderBackground(GuiGraphics g) {
                        g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), CampaignStyle.CLOTH);
                        g.renderOutline(getX(), getY(), getWidth(), getHeight(), isFocused() ? CampaignStyle.GOLD : CampaignStyle.BRASS);
                    }
                });
                paragraph.setCharacterLimit(180); paragraph.setValue(profileText(field)); setInitialFocus(paragraph);
            }
            button(10, 182, inner/2-2, tr("cancel"), () -> edit(""));
            button(12+inner/2, 182, inner/2-2, tr("save"), () -> {
                String old = field;
                String value = (old.equals("name") ? input.getValue() : paragraph.getValue()).replace('\n', ' ').strip();
                submitted = value;
                send(old + (value.isEmpty() ? "" : " " + value));
            });
            return;
        }
        if (CouncilScreen.supported(minecraft)) {
            button(w - 117, 204, 107, Component.translatable("council.summary"), () -> minecraft.getConnection().sendCommand("nation council"));
        }
        if (tab == Tab.COUNTRY) {
            button(w-112, 63, 102, tr("rename"), () -> edit("name"));
            button(w-112, 108, 102, tr("editPreamble"), () -> edit("preamble"));
            button(w-112, 155, 102, tr("editDoctrines"), () -> edit("doctrines"));
        } else if (tab == Tab.OIL) {
            button(10, 181, inner / 3 - 2, tr("oil.survey"), () -> send("oil survey"));
            var quantity = box(12 + inner / 3, 181, inner / 4 - 4, "oil.buckets", oilQuantity, 7);
            quantity.setFilter(value -> value.chars().allMatch(Character::isDigit));
            quantity.setResponder(value -> oilQuantity = value);
            export = button(12 + inner / 3 + inner / 4, 181, inner - inner / 3 - inner / 4 - 2,
                    tr("oil.export"), () -> send("oil export " + oilBuckets()));
            export.active = false;
        } else if (tab == Tab.LAND) {
            button(10,145,inner/2-2,tr("claim"),() -> send("claim"));
            button(12+inner/2,145,inner/2-2,tr("release"),() -> send("release"));
            button(10,182,inner,tr("openMap"),() -> {
                if (!com.modserver.client.map.JourneyMapScreenOpener.open(minecraft)) minecraft.getConnection().sendCommand("nation map");
            });
        } else if (tab == Tab.MARKET) {
            input = box(10, 184, Math.min(83, inner/4), "qty", "", 4);
            input.setFilter(s -> s.matches("[0-9]{0,4}"));
            int start = 14 + input.getWidth(), sellWidth = (w-start-14)/2;
            button(start, 184, sellWidth, tr("quote"), () -> {
                String qty = input.getValue();
                if (!qty.isEmpty() && Integer.parseInt(qty) <= 0) { notice = tr("short").getString(); return; }
                if (!qty.isEmpty() && Integer.parseInt(qty) > data.rows().get(selected).count()) {notice = tr("short").getString();return;}
                send("sell " + data.rows().get(selected).id() + (qty.isEmpty() ? "" : " " + qty));
            });
            button(start+sellWidth+4, 184, sellWidth, tr("bind"), () -> send("bind"));
        } else {
            button(10, 101, inner, tr("garrison"), () -> send("garrison"));
            button(10,182,inner,Component.translatable("tac.title"),() -> minecraft.getConnection().sendCommand("nation tactics"));
        }
    }
    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && !field.isEmpty() && !waiting) {
            edit(""); return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button == 0 && tab == Tab.MARKET && data.token().isEmpty() && x >= left+10 && x < left+w-10
                && y >= top+75 && y < top+147) {
            selected = (int)((y-top-75)/18);
            notice = ""; return true;
        }
        return super.mouseClicked(x,y,button);
    }
    private void line(GuiGraphics g, int y, Component text, int color) {
        g.drawString(font, font.plainSubstrByWidth(text.getString(), w-20), left+10, top+y, color, false);
    }
    private void right(GuiGraphics g, String text, int end, int y, int color) {
        g.drawString(font, text, left+end-font.width(text), top+y, color, false);
    }
    @Override public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
        super.renderBackground(g,mx,my,dt);
        int bright = CampaignStyle.INK, muted = 0xFF4F4938, gold = CampaignStyle.SEPIA;
        CampaignStyle.folio(g, left, top, w, 226, 0xFF000000 | data.color());
        Component wallet = tr("wallet",coins(data.balance()));
        String title = font.plainSubstrByWidth(tr("title").getString(), Math.max(12, w - font.width(wallet) - 38));
        g.drawString(font, title, left+18,top+11,CampaignStyle.IVORY,false);
        right(g, wallet.getString(), w-10,11,CampaignStyle.GOLD);
        if (!data.token().isEmpty()) {
            line(g,60,tr("review"),gold);
            g.drawWordWrap(font,Component.literal(data.offer()),left+10,top+82,w-24,bright);
        } else if (!field.isEmpty()) {
            line(g,65,tr("editing",tr(field)),gold);
        } else if (tab == Tab.COUNTRY) {
            g.drawString(font, font.plainSubstrByWidth(data.name(), w-129), left+10, top+68, bright, false);
            line(g,86,tr("land",data.land(),data.capital()),muted);
            line(g,97,tr("daily",coins(data.income())),muted);
            line(g,112,tr("preamble"),gold);
            excerpt(g,data.preamble(),130,bright);
            line(g,160,tr("doctrines"),gold);
            excerpt(g,data.doctrines(),177,bright);
        } else if (tab == Tab.OIL && data.oil() != null) {
            var oil = data.oil();
            var reserves = oil.reserves();
            if (reserves != null) {
                Component amount = reserves.bottomless() ? tr("oil.unlimited") : tr("oil.volume", volume(reserves.finiteMillibuckets()));
                line(g, 58, reserves.fields() == 0 ? tr("oil.nationalUncharted") : tr("oil.national", amount, reserves.fields()), gold);
            }
            Component survey = oil.reading() < 0 ? tr("oil.uncharted") : oil.reading() == Integer.MAX_VALUE
                    ? tr("oil.infinite") : tr("oil.reading", volume(oil.reading()));
            line(g, 71, survey, bright);
            line(g, 83, tr("oil.allowance", oil.remaining()), muted);
            int column = (w - 22) / 3;
            String[] grades = {"crude_oil", "diesel", "gasoline"};
            for (int i = 0; i < grades.length; i++) {
                int x = left + 10 + i * column;
                g.fill(x, top + 96, x + column - 2, top + 122, 0x38A88C58);
                g.drawString(font, tr("oil." + grades[i]), x + 4, top + 99, bright, false);
                g.drawString(font, tr("oil.rate", coins(oil.prices().get(i))), x + 4, top + 111, gold, false);
            }
            if (oil.status().equals("ready")) {
                line(g, 131, tr("oil.tank", tr("oil." + oil.grade()), volume(oil.millibuckets())), bright);
                int qty = oilBuckets();
                if (qty > 0) {
                    int grade = java.util.List.of(grades).indexOf(oil.grade());
                    Component estimate = qty > oil.millibuckets() / 1000 ? tr("oil.stock") : qty > oil.remaining()
                            ? tr("oil.quota") : tr("oil.proceeds", coins(qty * oil.prices().get(grade)));
                    g.drawWordWrap(font, estimate, left + 10, top + 146, w - 20, gold);
                }
            } else g.drawWordWrap(font, tr("oil." + oil.status()), left + 10, top + 130, w - 20, muted);
            g.drawString(font, tr("oil.buckets"), left + 12 + (w - 20) / 3, top + 169, gold, false);
        } else if (tab == Tab.LAND) {
            line(g,65,tr("land",data.land(),data.capital()),gold);
            line(g,91,tr("here",data.localChunk()),bright);
            line(g,108,tr("holder",data.localOwner()),muted);
        } else if (tab == Tab.MARKET) {
            int qty=w*47/100, price=w*68/100, value=w-13;
            g.drawString(font,tr("material"),left+12,top+61,muted,false);
            right(g,tr("stock").getString(),qty,61,muted); right(g,tr("unit").getString(),price,61,muted); right(g,tr("value").getString(),value,61,muted);
            for (int i=0;i<data.rows().size();i++) {
                var row=data.rows().get(i); int y=75+i*18;
                g.fill(left+9,top+y,left+w-9,top+y+17,i==selected ? 0x68A87E42 : 0x28A88C58);
                if (i==selected) g.fill(left+9,top+y,left+11,top+y+17,gold);
                g.renderItem(Commodity.valueOf(row.id().toUpperCase(java.util.Locale.ROOT)).item.getDefaultInstance(),left+14,top+y);
                g.drawString(font,tr(row.id()),left+33,top+y+5,bright,false);
                right(g,""+row.count(),qty,y+5,bright); right(g,coins(row.price()),price,y+5,muted); right(g,coins(row.value()),value,y+5,gold);
            }
            line(g,152,tr("stockValue",coins(data.stockValue())),gold);
            line(g,167,data.chestStatus().equals("ready") ? tr("location",data.chest()) : tr("chest."+data.chestStatus()),muted);
        } else {
            line(g,64,tr("state",data.guardLevel(),data.guards()),gold);
            line(g,82,tr("guardCost",coins(data.guardCost())),bright);
            line(g,126,tr("reinforcements"),muted);
            line(g,149,Component.translatable("tac.panel"),gold);
            line(g,165,Component.translatable("tac.panelHint"),muted);
        }
        Component footer = notice.isEmpty() ? (tab==Tab.MARKET && data.token().isEmpty() ? tr("qtyHint") : Component.empty()) : Component.literal(notice);
        int footerWidth = data.token().isEmpty() && field.isEmpty() && CouncilScreen.supported(minecraft) ? w - 134 : w - 20;
        var lines = font.split(footer, footerWidth);
        for (int i=0; i<Math.min(2, lines.size()); i++) g.drawString(font,lines.get(i),left+10,top+204+i*10,muted,false);
    }
    private void excerpt(GuiGraphics g, String value, int y, int color) {
        var lines=font.split(value.isBlank()?tr("empty"):Component.literal(value),w-24);
        for (int i=0;i<Math.min(2,lines.size());i++) g.drawString(font,lines.get(i),left+10,top+y+i*10,color,false);
    }
    @Override public boolean isPauseScreen() { return false; }
}
