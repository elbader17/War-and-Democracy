package com.modserver.client;

import com.modserver.council.CouncilPost;
import com.modserver.government.Commodity;
import com.modserver.nation.NationRelation;
import com.modserver.network.CouncilView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import static com.modserver.client.CampaignStyle.*;

public final class CouncilScreen extends Screen {
    private enum Desk {
        HOME("summary", Items.FILLED_MAP),
        OFFERS("offers", Items.WRITABLE_BOOK),
        TREATIES("treaties", Items.FEATHER),
        TRADE("sell", Items.GOLD_INGOT),
        CONVOY("sendTroops", Items.SHIELD),
        MAIL("mail", Items.PAPER);

        final String key;
        final Item icon;
        Desk(String key, Item icon) { this.key = key; this.icon = icon; }
    }

    private CouncilView view;
    private Desk desk = Desk.HOME;
    private boolean directory, index, chosen, composing, waiting, wide;
    private UUID returnPeer, draftOwner;
    private String notice = "", draft = "", qty = "1", price = "1";
    private Commodity item = Commodity.IRON;
    private int offerIndex, letterIndex;
    private int x, y, w, h, left, span, form, actionY;
    private Button submit, sendPrivate, sendCircular;

    private CouncilScreen(CouncilView view) {
        super(t("title"));
        this.view = view;
        this.notice = view.notice();
    }

    static boolean supported(Minecraft mc) {
        return mc.getConnection() != null && NetworkRegistry.hasChannel(mc.getConnection(), CouncilView.TYPE.id());
    }

    public static void accept(CouncilView next) {
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof CouncilScreen s && next.mine().equals(s.view.mine())) {
            if (!Objects.equals(next.selected(), s.view.selected())) s.offerIndex = s.letterIndex = 0;
            if (next.notice().equals("sent") || next.notice().equals("broadcast")) {
                s.draft = "";
                s.composing = false;
                s.letterIndex = 0;
            }
            s.view = next;
            s.notice = next.notice();
            s.waiting = false;
            s.rebuildWidgets();
        } else if (next.open()) mc.setScreen(new CouncilScreen(next));
        else if (mc.player != null && !next.notice().isEmpty()) mc.player.sendSystemMessage(t(next.notice()));
    }

    private static Component t(String key, Object... args) { return Component.translatable("council." + key, args); }
    private static Component gov(String key, Object... args) { return Component.translatable("gov." + key, args); }
    private String number(long n) { return NumberFormat.getIntegerInstance(minecraft.getLanguageManager().getJavaLocale()).format(n); }
    private String money(long cents) { return String.format(minecraft.getLanguageManager().getJavaLocale(), "%,.2f", BigDecimal.valueOf(cents, 2)); }
    private CouncilView.Peer peer() {
        return view.directory().stream().filter(p -> p.id().equals(view.selected())).findFirst().orElse(null);
    }
    private void command(String args) {
        if (waiting || minecraft.getConnection() == null) return;
        waiting = true;
        minecraft.getConnection().sendCommand("nation council " + args);
        children().forEach(child -> { if (child instanceof Button b) b.active = false; });
    }
    private void refresh() {
        command(directory || peer() == null ? "page " + (view.page() + 1) : "visit " + view.selected());
    }
    private Button button(int bx, int by, int width, Component label, Runnable click) {
        return addRenderableWidget(CampaignStyle.button(x + bx, y + by, width, 19, label, b -> click.run()));
    }
    private Button paperButton(int bx, int by, int width, Component label, Runnable click) {
        return addRenderableWidget(CampaignStyle.button(x + bx, y + by, width, 19, label, b -> click.run(), true));
    }
    private EditBox entry(int bx, int by, int width, String key, String value, int max, boolean digits, Consumer<String> save) {
        var box = CampaignStyle.entry(font, x + bx, y + by, width, 18, t(key));
        box.setMaxLength(max);
        if (digits) box.setFilter(s -> s.chars().allMatch(c -> c >= '0' && c <= '9'));
        box.setValue(value);
        box.setResponder(save);
        return addRenderableWidget(box);
    }
    private void open(Desk next) {
        if (waiting) return;
        boolean browsing = directory;
        index = false;
        directory = false;
        composing = false;
        notice = "";
        desk = next;
        if (next == Desk.OFFERS && view.pending() > 0) {
            chosen = true;
            offerIndex = 0;
            if (peer() == null || peer().incoming() == 0) command("page 1");
        } else if (next != Desk.HOME && !chosen && peer() != null) {
            if (browsing) { directory = true; rebuildWidgets(); }
            else chooseCountry();
            return;
        } else if (browsing && returnPeer != null && !returnPeer.equals(view.selected())) {
            command("visit " + returnPeer);
        }
        rebuildWidgets();
    }
    private void chooseCountry() {
        returnPeer = view.selected();
        directory = true;
        notice = "";
        rebuildWidgets();
    }
    private void select(CouncilView.Peer country) {
        directory = false;
        chosen = true;
        if (!country.id().equals(returnPeer)) composing = false;
        if (!country.id().equals(view.selected())) command("visit " + country.id());
        rebuildWidgets();
    }
    private void back() {
        if (waiting) return;
        if (directory) {
            directory = false;
            if (!chosen) desk = Desk.HOME;
            if (returnPeer != null && !returnPeer.equals(view.selected())) command("visit " + returnPeer);
        } else if (index) index = false;
        else if (composing) composing = false;
        else if (desk != Desk.HOME) desk = Desk.HOME;
        else { super.onClose(); return; }
        notice = "";
        rebuildWidgets();
    }
    @Override public void onClose() {
        // Escape can always release the player, even if the connection stalls.
        if (waiting) super.onClose(); else back();
    }
    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        w = Math.min(612, width - 10);
        h = Math.min(285, height - 8);
        x = (width - w) / 2; y = (height - h) / 2;
        wide = w >= 480;
        if (wide) index = false;
        left = wide ? 164 : 13;
        span = w - left - 13;
        form = 99;
        actionY = h - 49;
        submit = sendPrivate = sendCircular = null;

        if (!wide) button(6, 4, 65, t(index || directory ? "back" : "menu"), () -> {
            if (directory) back();
            else { index = !index; rebuildWidgets(); }
        });
        if (wide || index) buildIndex();
        if (!index) {
            if (directory) buildDirectory();
            else if (desk == Desk.HOME) {
                button(left, actionY, span, view.pending() > 0 ? t("review", view.pending()) : t("contact"),
                        () -> { if (view.pending() > 0) open(Desk.OFFERS); else open(Desk.TREATIES); })
                        .active = !view.directory().isEmpty();
            } else if (peer() != null) {
                paperButton(left + span - 94, 66, 89, t("changeCountry"), this::chooseCountry);
                buildDesk(peer());
            }
            if (!directory) paperButton(w - 84, h - 23, 74, t("refresh"), this::refresh);
        }
        if (waiting) children().forEach(child -> { if (child instanceof Button b) b.active = false; });
    }

    private void buildIndex() {
        int start = wide ? 8 : 13;
        int width = wide ? 137 : w - 26;
        int row = Math.min(26, (h - 91) / 6);
        int by = 36;
        for (Desk section : Desk.values()) {
            if (section == Desk.OFFERS) by += 16;
            Component label = section == Desk.OFFERS && view.pending() > 0 ? t("inboxCount", view.pending()) : t(section.key);
            addRenderableWidget(indexTab(x + start, y + by, width, row - 2, label, section.icon,
                    section == desk, b -> open(section)));
            by += row;
        }
        button(start, h - 28, width, t("manage"), () -> minecraft.getConnection().sendCommand("nation panel"));
    }

    private void buildDirectory() {
        if (wide) paperButton(left + span - 64, 35, 64, t("back"), this::back);
        int row = Math.min(27, (h - 98) / CouncilView.PAGE_SIZE);
        int by = 61;
        for (var country : view.directory()) {
            Component relation = relation(country);
            Component badge = country.incoming() == 0 ? relation : t("awaiting", country.incoming());
            int localY = by;
            var tooltip = Component.literal(country.name()).append(" · ").append(relation);
            var rowButton = Button.builder(tooltip, b -> select(country)).bounds(x + left, y + by, span, row - 2)
                    .build(builder -> new Button(builder) {
                        @Override protected void renderWidget(GuiGraphics g, int mx, int my, float dt) {
                            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                                    isHoveredOrFocused() ? 0x60a88442 : 0x29a88442);
                            if (isFocused()) g.renderOutline(getX(), getY(), getWidth(), getHeight(), SEPIA);
                            pennant(g, left + 6, localY + (row - 15) / 2, country.color());
                            int badgeWidth = font.width(badge);
                            int baseline = localY + (row - 10) / 2;
                            line(g, left + 22, baseline, span - badgeWidth - 38, Component.literal(country.name()), INK);
                            line(g, left + span - badgeWidth - 7, baseline, badgeWidth, badge, SEPIA);
                        }
                    });
            if (country.incoming() > 0) {
                rowButton.setTooltip(Tooltip.create(tooltip));
                rowButton.setTooltipDelay(java.time.Duration.ofMillis(600));
            }
            addRenderableWidget(rowButton);
            by += row;
        }
        if (view.pages() > 1) {
            button(left, h - 28, 80, t("previous"), () -> command("page " + view.page())).active = view.page() > 0;
            button(left + span - 80, h - 28, 80, t("next"), () -> command("page " + (view.page() + 2))).active = view.page() + 1 < view.pages();
        }
    }
    private static Component relation(CouncilView.Peer country) {
        var label = Component.empty().append(t("rel." + country.relation().name()));
        return country.envoy() ? label.append(" · NPC") : label;
    }

    private void buildDesk(CouncilView.Peer p) {
        String target = p.id().toString();
        int half = (span - 5) / 2;
        switch (desk) {
            case TREATIES -> {
                int by = form + 24;
                for (CouncilPost.Kind kind : TREATIES) {
                    String key = kind.name().toLowerCase(Locale.ROOT);
                    button(left, by, treatyButtonWidth(), t("propose." + key), () -> command(key + " " + target)).active =
                            p.relation() != kind.relation() && (kind != CouncilPost.Kind.PEACE || p.relation() == NationRelation.WAR);
                    by += treatyStep();
                }
            }
            case TRADE -> {
                for (Commodity c : Commodity.values()) {
                    int a = span * c.ordinal() / 4, b = span * (c.ordinal() + 1) / 4;
                    addRenderableWidget(indexTab(x + left + a, y + form, b - a - 2, 20, gov(c.id()), c.item,
                            item == c, ignored -> { item = c; rebuildWidgets(); }));
                }
                entry(left, form + 39, half, "quantity", qty, 4, true, s -> qty = s);
                entry(left + half + 5, form + 39, span - half - 5, "total", price, 10, true, s -> price = s);
                submit = button(left, actionY, span, t("offerTrade"), () -> command("trade " + target + " " + item.id() + " " + qty + " " + price));
            }
            case CONVOY -> {
                if (p.relation() == NationRelation.ALLY)
                    button(left, actionY, span, t("planConvoy"), () -> command("convoy " + target));
                else button(left, actionY, span, t("seeTreaties"), () -> open(Desk.TREATIES));
            }
            case MAIL -> {
                if (composing) {
                    var box = entry(left, form + 19, span, "message", draft, 160, false, s -> draft = s);
                    setInitialFocus(box);
                    sendPrivate = button(left, actionY, half, t("sendLetter"), () -> command("letter " + target + " " + draft.strip()));
                    sendCircular = button(left + half + 5, actionY, span - half - 5, t("sendAllies"), () -> command("circular " + target + " " + draft.strip()));
                    button(left + span - 65, form + 42, 65, gov("cancel"), this::back);
                } else {
                    paging(false);
                    if (!p.envoy() || view.allies() > 0) button(left, actionY, span, t("compose"), () -> {
                        if (!Objects.equals(draftOwner, p.id())) draft = "";
                        draftOwner = p.id(); composing = true; rebuildWidgets();
                    });
                }
            }
            case OFFERS -> {
                paging(true);
                if (view.offers().isEmpty()) {
                    if (view.pending() > 0) button(left, actionY, span, t("review", view.pending()), () -> open(Desk.OFFERS));
                    break;
                }
                var post = view.offers().get(offerIndex);
                if (post.to().equals(view.mine())) {
                    button(left, actionY, half, t("accept"), () -> command("accept " + target + " " + post.id()));
                    paperButton(left + half + 5, actionY, span - half - 5, t("decline"), () -> command("decline " + target + " " + post.id()));
                } else button(left, actionY, span, t("withdraw"), () -> command("withdraw " + target + " " + post.id()));
            }
            default -> {}
        }
        updateInputs();
    }
    private static final CouncilPost.Kind[] TREATIES = {CouncilPost.Kind.PACT, CouncilPost.Kind.ALLY, CouncilPost.Kind.PEACE};
    private int treatyButtonWidth() { return Math.min(123, span / 3); }
    private int treatyStep() { return Math.min(33, (h - form - 48) / 3); }
    private void paging(boolean offers) {
        int size = offers ? view.offers().size() : view.letters().size();
        int current = Math.clamp(offers ? offerIndex : letterIndex, 0, Math.max(0, size - 1));
        if (offers) offerIndex = current; else letterIndex = current;
        if (size <= 1) return;
        int right = left + span;
        for (int delta : new int[]{-1, 1}) {
            var b = button(right - (delta < 0 ? 52 : 24), form, 24, Component.literal(delta < 0 ? "‹" : "›"), () -> {
                if (offers) offerIndex += delta; else letterIndex += delta;
                rebuildWidgets();
            });
            b.setTooltip(Tooltip.create(t(delta < 0 ? "previous" : "next")));
            b.active = current + delta >= 0 && current + delta < size;
        }
    }
    private long amount(String value) {
        try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return 0; }
    }
    private Component tradeBlocker() {
        var p = peer();
        if (p.envoy()) return t("envoyOnly");
        if (p.relation() == NationRelation.WAR) return t("atWar");
        if (!view.chest()) return t("noChest");
        long n = amount(qty);
        if (n > view.stock().get(item.ordinal())) return t("shortStock", gov(item.id()), number(view.stock().get(item.ordinal())));
        if (n == 0 || n > 3456 || amount(price) == 0 || amount(price) > 1_000_000_000) return t("tradeLimits");
        return null;
    }
    private void updateInputs() {
        if (submit != null) submit.active = !waiting && tradeBlocker() == null;
        if (sendPrivate != null) {
            boolean ready = !waiting && !draft.isBlank();
            sendPrivate.active = ready && !peer().envoy();
            sendCircular.active = ready && view.allies() > 0;
        }
    }
    @Override public void tick() { updateInputs(); }

    private int mouseX, mouseY;
    private void line(GuiGraphics g, int bx, int by, int width, Component label, int color) {
        if (width <= 0) return;
        String text = label.getString();
        if (font.width(text) > width) {
            text = font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
            if (mouseX >= x + bx && mouseX < x + bx + width && mouseY >= y + by && mouseY < y + by + 10)
                setTooltipForNextRenderPass(label);
        }
        g.drawString(font, text, x + bx, y + by, color, false);
    }
    private void wrap(GuiGraphics g, int bx, int by, int width, Component label, int color) {
        for (var part : font.split(label, width)) {
            g.drawString(font, part, x + bx, y + by, color, false);
            by += 10;
        }
    }
    private void heading(GuiGraphics g, Component title) {
        g.pose().pushPose();
        g.pose().translate(x + left, y + 39, 0);
        g.pose().scale(1.25f, 1.25f, 1);
        g.drawString(font, title, 0, 0, INK, false);
        g.pose().popPose();
    }
    private void pennant(GuiGraphics g, int bx, int by, int color) {
        int l = x + bx, t = y + by;
        g.fill(l, t, l + 10, t + 9, 0xff000000 | color);
        for (int i = 0; i < 4; i++) g.fill(l + i + 1, t + 9 + i, l + 9 - i, t + 10 + i, 0xff000000 | color);
        g.fill(l - 1, t - 1, l + 11, t, BRASS);
    }
    @Override public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
        super.renderBackground(g, mx, my, dt);
        mouseX = mx; mouseY = my;
        CampaignStyle.folio(g, x, y, w, h, 0xff000000 | view.color());
        int headerX = wide ? 20 : 80;
        if (wide || w >= 405) line(g, headerX, 10, w / 2 - headerX, t("title"), IVORY);
        int nameWidth = Math.min(font.width(view.name()), w - (wide || w >= 405 ? w / 2 : headerX) - 28);
        pennant(g, w - nameWidth - 26, 7, view.color());
        line(g, w - nameWidth - 10, 10, nameWidth, Component.literal(view.name()), GOLD);
        if (wide || index) {
            int end = wide ? 151 : w - 1;
            g.fill(x + 1, y + 28, x + end, y + h - 1, CLOTH);
            g.fill(x + end, y + 28, x + end + 1, y + h - 1, BRASS);
            int row = Math.min(26, (h - 91) / 6);
            line(g, wide ? 12 : 17, 39 + row, end - 25, t("foreign"), GOLD);
        }
        if (index) return;
        if (directory) {
            heading(g, t("choose." + desk.key));
            if (view.pages() > 1) g.drawCenteredString(font, t("page", view.page() + 1, view.pages()), x + left + span / 2, y + h - 21, SEPIA);
            return;
        }
        heading(g, t(desk.key));
        if (desk == Desk.HOME) summary(g);
        else if (peer() == null) wrap(g, left, 68, span, t("noPeers"), SEPIA);
        else {
            var p = peer();
            g.fill(x + left, y + 58, x + left + span, y + 91, 0x32a88442);
            g.fill(x + left, y + 90, x + left + span, y + 91, BRASS);
            pennant(g, left + 8, 67, p.color());
            line(g, left + 25, 63, span - 125, Component.literal(p.name()), INK);
            line(g, left + 25, 77, span - 125, relation(p), SEPIA);
            renderDesk(g, p);
        }
        if (!notice.isEmpty()) wrap(g, left, h - 24, span - 83, t(notice), INK);
    }

    private void summary(GuiGraphics g) {
        int col = (span - 12) / 2;
        int right = left + col + 12;
        int row = Math.min(16, (h - 146) / 8);
        int top = 56, second = top + 18 + 2 * row, stock = actionY - 36;
        g.fill(x + left, y + top - 2, x + left + span, y + second - 5, 0x26887045);
        ledgerHeading(g, left + 3, top, col - 3, "coffers", Items.GOLD_NUGGET);
        ledgerHeading(g, right, top, col, "army", Items.IRON_HELMET);
        line(g, left + 5, top + 15, col - 5, t("coinAmount", money(view.balance())), INK);
        line(g, left + 5, top + 15 + row, col - 5, t("perDay", money(view.income())), SEPIA);
        line(g, right + 2, top + 15, col - 2, t("fieldCount", number(view.deployed())), INK);
        line(g, right + 2, top + 15 + row, col - 2, t("reserveCount", number(view.reserve())), SEPIA);
        ledgerHeading(g, left + 3, second, col - 3, "holdings", Items.MAP);
        ledgerHeading(g, right, second, col, "operations", Items.COMPASS);
        line(g, left + 5, second + 15, col - 5, t("land", view.land(), view.capital()), INK);
        line(g, left + 5, second + 15 + row, col - 5, t("levels", view.farm(), view.garrison(), view.treasury()), SEPIA);
        if (view.oil() != null) {
            var oil = view.oil();
            Component reserves = oil.fields() == 0 ? t("oilUncharted")
                    : t("oil", oil.bottomless() ? gov("oil.unlimited").getString() : number(oil.finiteMillibuckets()) + " mB");
            line(g, left + 5, second + 15 + 2 * row, col - 5, reserves, SEPIA);
        }
        line(g, right + 2, second + 15, col - 2, t("fronts", view.battles()), INK);
        line(g, right + 2, second + 15 + row, col - 2, t("inTransit", view.convoys()), SEPIA);
        line(g, right + 2, second + 15 + 2 * row, col - 2, t("alliedCount", view.allies()), SEPIA);
        g.fill(x + left, y + stock - 3, x + left + span, y + stock - 2, BRASS);
        line(g, left + 3, stock, span - 6, t("resources"), SEPIA);
        if (!view.chest()) line(g, left + 3, stock + 15, span - 6, t("noChest"), INK);
        else for (Commodity c : Commodity.values()) {
            int bx = left + span * c.ordinal() / 4;
            g.renderItem(c.item.getDefaultInstance(), x + bx, y + stock + 12);
            String count = number(view.stock().get(c.ordinal()));
            String named = gov(c.id()).getString() + " " + count;
            line(g, bx + 18, stock + 17, span / 4 - 20, Component.literal(font.width(named) <= span / 4 - 20 ? named : count), INK);
            if (mouseX >= x + bx && mouseX < x + bx + span / 4 && mouseY >= y + stock + 12 && mouseY < y + stock + 29)
                setTooltipForNextRenderPass(Component.literal(named));
        }
    }
    private void ledgerHeading(GuiGraphics g, int bx, int by, int width, String key, Item icon) {
        g.renderItem(icon.getDefaultInstance(), x + bx, y + by - 3);
        line(g, bx + 19, by + 1, width - 19, t(key), SEPIA);
    }
    private void renderDesk(GuiGraphics g, CouncilView.Peer p) {
        switch (desk) {
            case TREATIES -> {
                wrap(g, left, form, span, t(p.envoy() ? "envoyTreaties" : "ratifyHint"), SEPIA);
                int by = form + 25;
                int next = treatyButtonWidth() + 8;
                for (var kind : TREATIES) {
                    wrap(g, left + next, by, span - next, treatyHint(kind), INK);
                    by += treatyStep();
                }
            }
            case TRADE -> {
                int half = (span - 5) / 2;
                line(g, left, form + 27, half, t("unitsAvailable", number(view.stock().get(item.ordinal()))), SEPIA);
                line(g, left + half + 5, form + 27, span - half - 5, t("total"), SEPIA);
                Component blocker = tradeBlocker();
                Component detail = blocker == null ? t("salePreview", number(amount(qty)), gov(item.id()), number(amount(price))) : blocker;
                wrap(g, left, form + 63, span, detail, INK);
            }
            case CONVOY -> {
                ledgerHeading(g, left, form + 4, span, "militaryAid", Items.COMPASS);
                wrap(g, left + 2, form + 24, span - 4, t(p.relation() == NationRelation.ALLY ? "routeHint" : "needAlliance"), INK);
            }
            case MAIL -> {
                if (composing) {
                    line(g, left, form + 4, span, t("write", draft.length()), SEPIA);
                    if (p.envoy() || view.allies() == 0)
                        wrap(g, left, form + 45, span - 76, t(p.envoy() ? "envoyOnly" : "noAllies"), SEPIA);
                } else if (view.letters().isEmpty()) wrap(g, left, form + 8, span, t(p.envoy() ? "envoyOnly" : "noLetters"), INK);
                else {
                    var letter = view.letters().get(letterIndex);
                    var sender = letter.from().equals(view.mine()) ? view.name() : p.name();
                    line(g, left, form + 6, span - 57, t("file", letterIndex + 1, view.letters().size(), sender), SEPIA);
                    Component text = switch (letter.kind()) {
                        case LETTER -> Component.literal(letter.text());
                        case CIRCULAR -> Component.empty().append(t("circularPrefix")).append(Component.literal(letter.text()));
                        default -> t("receipt", t(letter.kind().name()), letter.item() == null ? t(letter.text()) :
                                t("terms", letter.quantity(), gov(letter.item().id()), number(letter.coins())));
                    };
                    wrap(g, left + 2, form + 27, span - 4, text, INK);
                }
            }
            case OFFERS -> renderOffer(g, p);
            default -> {}
        }
    }
    private static Component treatyHint(CouncilPost.Kind kind) {
        return t(kind.name().toLowerCase(Locale.ROOT) + "Hint");
    }
    private void renderOffer(GuiGraphics g, CouncilView.Peer p) {
        if (view.offers().isEmpty()) { wrap(g, left, form + 8, span, t("noOffers"), INK); return; }
        var post = view.offers().get(offerIndex);
        boolean incoming = post.to().equals(view.mine());
        line(g, left, form + 6, span - 57, t("file", offerIndex + 1, view.offers().size(),
                t(incoming ? "yourSignature" : "theirSignature", p.name())), SEPIA);
        int by = form + 25;
        g.fill(x + left, y + by, x + left + span, y + actionY - 6, 0x55fff0cf);
        g.fill(x + left, y + by, x + left + 2, y + actionY - 6, incoming ? 0xff864638 : BRASS);
        g.renderItem((post.item() == null ? Items.FEATHER : post.item().item).getDefaultInstance(), x + left + 7, y + by + 2);
        line(g, left + 28, by + 7, span - 36, t(post.kind().name()), INK);
        int textWidth = span - 18;
        if (span >= 275 && actionY - by >= 66) {
            // The seal carries the issuing nation's color and initial, including on outgoing offers.
            String issuer = incoming ? p.name() : view.name();
            seal(g, left + span - 27, actionY - 31, incoming ? p.color() : view.color(), issuer);
            textWidth -= 46;
        }
        if (post.kind() == CouncilPost.Kind.TRADE) {
            line(g, left + 9, by + 24, textWidth, t(incoming ? "youReceiveGoods" : "youSendGoods", number(post.quantity()), gov(post.item().id())), INK);
            line(g, left + 9, by + 37, textWidth, t(incoming ? "youPay" : "youEarn", number(post.coins())), INK);
        } else wrap(g, left + 9, by + 24, textWidth, treatyHint(post.kind()), INK);
    }

    private void seal(GuiGraphics g, int bx, int by, int color, String issuer) {
        int cx = x + bx, cy = y + by;
        g.fill(cx - 6, cy + 7, cx - 1, cy + 19, 0xff000000 | color);
        g.fill(cx + 1, cy + 7, cx + 6, cy + 16, 0xff000000 | color);
        // A pixel-cut wax seal with an inset rim.
        for (int dy = -9; dy <= 9; dy++) {
            int radius = (int)Math.sqrt(100 - dy * dy);
            g.fill(cx - radius, cy + dy, cx + radius + 1, cy + dy + 1, 0xff682f29);
            if (radius > 4 && Math.abs(dy) < 8)
                g.fill(cx - radius + 2, cy + dy, cx + radius - 1, cy + dy + 1, 0xffa45743);
        }
        if (!issuer.isEmpty()) {
            String initial = issuer.substring(0, issuer.offsetByCodePoints(0, 1));
            g.drawString(font, initial, cx - font.width(initial) / 2, cy - 3, IVORY, false);
        }
    }
}
