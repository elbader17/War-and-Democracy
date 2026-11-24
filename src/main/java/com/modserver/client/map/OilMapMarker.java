package com.modserver.client.map;

import com.modserver.ModServer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** The same cartographic symbol is used on the terrain map, campaign board and map keys. */
final class OilMapMarker {
    static final ResourceLocation ICON = ModServer.id("textures/gui/campaign/oil_pin.png");
    static final int WIDTH = 20, HEIGHT = 24;
    static final int GOLD = 0xFFFFC04E, INK = 0xFF483520;

    static void draw(GuiGraphics g, int centerX, int top, int height) {
        int width = Math.max(1, Math.round((float) height * WIDTH / HEIGHT));
        g.blit(ICON, centerX - width / 2, top, width, height, 0, 0, WIDTH, HEIGHT, WIDTH, HEIGHT);
    }

    private OilMapMarker() {}
}
