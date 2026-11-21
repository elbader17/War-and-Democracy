package com.modserver.client.map;

import com.modserver.network.MapTerritoryInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.math.BigDecimal;
import java.util.Locale;

/** Inspecting a cell never spends money. Only the explicit dispute button submits the server's offer. */
public final class TerritoryScreen extends Screen {
    private final ResourceLocation dimension;
    private final int x, z;
    private MapTerritoryInfo info;
    private boolean waiting;
    private int left, top, panelWidth;

    private TerritoryScreen(ResourceLocation dimension, int x, int z) {
        super(Component.literal("Chunk " + x + ", " + z));
        this.dimension = dimension;
        this.x = x;
        this.z = z;
    }

    static boolean supported() {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && NetworkRegistry.hasChannel(connection, MapTerritoryInfo.TYPE.id());
    }

    static void open(ResourceLocation dimension, int x, int z) {
        if (!supported()) return;
        var screen = new TerritoryScreen(dimension, x, z);
        ClientHooks.pushGuiLayer(Minecraft.getInstance(), screen);
        screen.request("");
    }

    public static void accept(MapTerritoryInfo reply) {
        if (Minecraft.getInstance().screen instanceof TerritoryScreen s
                && s.x == reply.x() && s.z == reply.z() && s.dimension.equals(reply.dimension())) {
            s.info = reply;
            s.waiting = false;
            s.rebuildWidgets();
        }
    }

    static void refreshIfOpen() {
        if (Minecraft.getInstance().screen instanceof TerritoryScreen s && !s.waiting) s.request("");
    }

    private void request(String confirmation) {
        var connection = minecraft.getConnection();
        if (connection == null || waiting) return;
        waiting = true;
        rebuildWidgets();
        connection.sendCommand("nation map select " + dimension + " " + x + " " + z + confirmation);
    }

    @Override
    protected void init() {
        panelWidth = Math.min(296, width - 12);
        left = (width - panelWidth) / 2;
        top = (height - 156) / 2;
        int bottom = top + 124;
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                .bounds(left + 12, bottom, 78, 20).build());
        if (info != null) info.offer().ifPresent(offer -> {
            Button submit = addRenderableWidget(Button.builder(Component.translatable("map.modserver.dispute_button",
                            String.format(minecraft.getLanguageManager().getJavaLocale(), "%.2f", BigDecimal.valueOf(offer.price(), 2))),
                    b -> request(" confirm " + offer.arguments()))
                    .bounds(left + 96, bottom, panelWidth - 108, 20).build());
            submit.active = !waiting;
        });
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0x80000000);
        g.fill(left, top, left + panelWidth, top + 156, 0xFF20262B);
        g.fill(left, top, left + panelWidth, top + 2, info != null && info.deadline() > 0 ? 0xFFFF565E : 0xFFFFCF71);
        int tx = left + 13;
        g.drawString(font, getTitle(), tx, top + 13, 0xFFFFFF);
        if (info == null) {
            g.drawString(font, Component.translatable("map.modserver.loading"), tx, top + 37, 0xCBD0D2);
            return;
        }
        g.drawString(font, info.owner(), tx, top + 33, 0xFFDC86);
        g.drawWordWrap(font, Component.literal(info.detail()), tx, top + 53, panelWidth - 26, 0xF0F1F2);
        if (info.deadline() > 0 && minecraft.level != null) {
            long seconds = Math.max(0, info.deadline() - minecraft.level.getGameTime() + 19) / 20;
            g.drawString(font, Component.translatable("map.modserver.remaining", String.format(Locale.ROOT,
                    "%d:%02d", seconds / 60, seconds % 60)), tx, top + 99, 0xFFAAA5);
        }
    }

    @Override public void onClose() { ClientHooks.popGuiLayer(minecraft); }
    @Override public boolean isPauseScreen() { return false; }
}
