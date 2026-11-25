package com.modserver.client;

import com.modserver.defense.DefenseDesk.*;
import com.modserver.defense.DefenseHardware;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

public final class DefenseScreen extends Screen {
    private final Screen parent;
    private View view;
    private final Set<UUID> selected = new LinkedHashSet<>();
    private boolean all;
    private String subject = "";
    private int left, top, w, roster, line;
    private boolean pending;

    private DefenseScreen(Screen parent, View view) {
        super(text("Territorial defense"));
        this.parent = parent;
        this.view = view;
    }

    static Component text(String s) {
        return Component.literal(s);
    }

    public static void open() {
        PacketDistributor.sendToServer(new Request(Order.VIEW, false, List.of(), "", 0));
    }

    public static void accept(View view) {
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof DefenseScreen screen) {
            screen.pending = false;
            screen.view = view;
            screen.rebuildWidgets();
        } else if (mc.screen instanceof com.modserver.client.map.TacticalScreen)
            mc.setScreen(new DefenseScreen(mc.screen, view));
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private Button button(int x, int y, int width, String s, Runnable action) {
        return addRenderableWidget(
                CampaignStyle.button(x, y, width, 16, text(s), b -> action.run()));
    }

    private void order(Order action, int page) {
        if (pending) return;
        pending = true;
        PacketDistributor.sendToServer(
                new Request(action, all, List.copyOf(selected), subject, page));
    }

    @Override
    protected void init() {
        w = Math.min(530, width - 12);
        int h = Math.min(245, height - 8);
        top = (height - h) / 2;
        left = (width - w) / 2;
        roster = w * 48 / 100;
        line = Math.min(23, (h - 89) / 6);
        int x = left + roster + 10, rw = w - roster - 18, y = top + 48;
        button(left + w - 85, top + 6, 75, "Campaign map", this::onClose);
        button(
                left + 8,
                top + 30,
                roster - 13,
                all ? "✓ All installations" : "Select all installations",
                () -> {
                    all = !all;
                    selected.clear();
                    rebuildWidgets();
                });
        for (int i = 0; i < view.rows().size(); i++) {
            var row = view.rows().get(i);
            String flag = (all || selected.contains(row.id())) ? "✓ " : "";
            String label =
                    flag + row.tier().title + "  " + row.pos().getX() + ", " + row.pos().getZ();
            var b =
                    button(
                            left + 25,
                            top + 50 + i * line,
                            roster - 30,
                            label,
                            () -> {
                                if (all) {
                                    all = false;
                                    selected.clear();
                                }
                                if (!selected.remove(row.id()) && selected.size() < 128)
                                    selected.add(row.id());
                                rebuildWidgets();
                            });
            b.setTooltip(
                    Tooltip.create(
                            text(
                                    row.dim()
                                            + " • Y "
                                            + row.pos().getY()
                                            + "\n"
                                            + row.status()
                                            + " • "
                                            + (row.radar() ? "Radar" : "Local")
                                            + "\nProtected: "
                                            + (row.protect().isEmpty() ? "—" : row.protect())
                                            + "\nEngage: "
                                            + (row.engage().isEmpty() ? "—" : row.engage()))));
        }
        int pageY = top + 50 + 6 * line;
        button(left + 8, pageY, 22, "‹", () -> order(Order.VIEW, view.page() - 1)).active =
                view.page() > 0;
        button(
                left + 33,
                pageY,
                roster - 64,
                "Refresh  " + (view.page() + 1) + " / " + view.pages(),
                () -> order(Order.VIEW, view.page()));
        button(left + roster - 27, pageY, 22, "›", () -> order(Order.VIEW, view.page() + 1))
                        .active =
                view.page() + 1 < view.pages();
        int half = (rw - 4) / 2;
        addOrder(x, y, half, "Arm", Order.ARM);
        addOrder(x + half + 4, y, half, "Hold fire", Order.HOLD);
        addOrder(x, y + 32, half, "Local sensor", Order.LOCAL);
        addOrder(x + half + 4, y + 32, half, "Linked radar", Order.RADAR);
        var input =
                addRenderableWidget(
                        CampaignStyle.entry(
                                font, x + 1, y + 66, rw - 2, 16, text("Nation, player or UUID")));
        input.setMaxLength(80);
        input.setValue(subject);
        input.setResponder(s -> subject = s);
        addOrder(x, y + 86, half, "Protect", Order.PROTECT);
        addOrder(x + half + 4, y + 86, half, "Engage", Order.ENGAGE);
        addOrder(x, y + 105, rw, "Remove list entry", Order.CLEAR);
    }

    private void addOrder(int x, int y, int w, String name, Order action) {
        button(x, y, w, name, () -> order(action, view.page())).active =
                all ? view.total() > 0 : !selected.isEmpty();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
        g.fill(0, 0, width, height, 0xcf1d241e);
        int h = Math.min(245, height - 8), x = left + roster + 10;
        CampaignStyle.folio(g, left, top, w, h, 0xffbb8645);
        g.drawString(font, getTitle(), left + 19, top + 11, CampaignStyle.GOLD, false);
        g.fill(left + roster + 3, top + 29, left + w - 4, top + h - 4, CampaignStyle.CLOTH);
        for (int i = 0; i < view.rows().size(); i++) {
            var row = view.rows().get(i);
            g.renderItem(
                    DefenseHardware.TURRETS.get(row.tier()).asItem().getDefaultInstance(),
                    left + 8,
                    top + 50 + i * line);
        }
        g.drawString(
                font,
                all ? "Entire territory" : selected.size() + " selected",
                x,
                top + 34,
                CampaignStyle.GOLD,
                false);
        g.drawString(font, "Contact source", x, top + 69, CampaignStyle.IVORY, false);
        g.drawString(font, "Nation, player or UUID", x, top + 101, CampaignStyle.IVORY, false);
        if (!all && selected.size() == 1 && h >= 220) {
            var selectedRow =
                    view.rows().stream().filter(row -> selected.contains(row.id())).findFirst();
            selectedRow.ifPresent(
                    row -> {
                        g.drawString(
                                font,
                                row.tier().title + " • " + (row.radar() ? "Radar" : "Local sensor"),
                                x,
                                top + 197,
                                CampaignStyle.GOLD,
                                false);
                        g.drawWordWrap(
                                font,
                                text(row.status()),
                                x,
                                top + 210,
                                w - roster - 20,
                                CampaignStyle.IVORY);
                    });
        }
        if (view.total() == 0)
            g.drawWordWrap(
                    font,
                    text("Place a defense installation inside your territory."),
                    left + 12,
                    top + 60,
                    roster - 24,
                    CampaignStyle.INK);
        if (!view.notice().isEmpty())
            g.drawWordWrap(
                    font, text(view.notice()), x, top + 174, w - roster - 20, CampaignStyle.IVORY);
    }
}
