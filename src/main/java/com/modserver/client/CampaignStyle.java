package com.modserver.client;

import com.modserver.ModServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/** The campaign folio: a cloth binding, brass rules, and an engraved paper insert. */
public final class CampaignStyle {
    public static final int CLOTH = 0xFF272D26, OLIVE = 0xFF384236, BRASS = 0xFFA68D5D;
    public static final int GOLD = 0xFFF4CB75, IVORY = 0xFFFFECCC, INK = 0xFF352F24, SEPIA = 0xFF644623;
    private static final ResourceLocation PAPER = ModServer.id("textures/gui/campaign/paper.png");

    public static void paper(GuiGraphics g, int x, int y, int w, int h) {
        g.blit(PAPER, x, y, w, h, 0, 0, 768, 512, 768, 512);
    }

    public static void folio(GuiGraphics g, int x, int y, int w, int h, int ribbon) {
        g.fill(x + 3, y + 4, x + w + 4, y + h + 4, 0x80000000);
        paper(g, x, y, w, h);
        g.renderOutline(x, y, w, h, BRASS);
        g.fill(x + 1, y + 1, x + w - 1, y + 28, CLOTH);
        g.fill(x + 1, y + 27, x + w - 1, y + 28, BRASS);
        g.fill(x + 6, y + 1, x + 12, y + 21, ribbon);
        g.fill(x + 6, y + 21, x + 8, y + 23, ribbon);
        g.fill(x + 10, y + 21, x + 12, y + 23, ribbon);
    }

    public static Button button(int x, int y, int w, int h, Component label, Button.OnPress press) {
        return button(x, y, w, h, label, press, false);
    }
    public static Button button(int x, int y, int w, int h, Component label, Button.OnPress press, boolean onPaper) {
        return Button.builder(label, press).bounds(x, y, w, h).build(builder -> new Button(builder) {
            @Override protected void renderWidget(GuiGraphics g, int mx, int my, float dt) {
                int l = getX(), t = getY(), r = l + getWidth(), b = t + getHeight();
                boolean focus = active && isHoveredOrFocused();
                if (onPaper) {
                    g.fill(l, t, r, b, focus ? 0xffe9d5a5 : 0x45f8e2b7);
                    g.renderOutline(l, t, getWidth(), getHeight(), focus ? INK : BRASS);
                    var font = Minecraft.getInstance().font;
                    int textWidth = font.width(getMessage());
                    if (textWidth <= getWidth() - 6)
                        g.drawString(font, getMessage(), (l+r-textWidth)/2, (t+b-font.lineHeight)/2+1, active ? INK : SEPIA, false);
                    else renderScrollingString(g, font, getMessage(), l+3, t, r-3, b, active ? INK : SEPIA);
                    return;
                }
                g.fill(l, t, r, b, CLOTH);
                g.fill(l + 1, t + 1, r - 1, b - 1, !active ? 0xFF514D40 : focus ? 0xFF56634A : OLIVE);
                g.fill(l + 2, t + 1, r - 2, t + 2, focus ? GOLD : BRASS);
                if (isFocused()) g.renderOutline(l, t, getWidth(), getHeight(), GOLD);
                renderString(g, Minecraft.getInstance().font, !active ? 0xFFD1C5AE : IVORY);
            }
        });
    }

    public static EditBox entry(Font font, int x, int y, int w, int h, Component name) {
        var box = new EditBox(font, x, y, w, h, name) {
            @Override public void renderWidget(GuiGraphics g, int mx, int my, float dt) {
                super.renderWidget(g, mx, my, dt);
                g.renderOutline(getX(), getY(), getWidth(), getHeight(), isFocused() ? GOLD : BRASS);
            }
        };
        box.setTextColor(IVORY);
        return box;
    }

    /** The brass bookmark follows the open section of the folio. */
    public static Button indexTab(int x, int y, int w, int h, Component name, Item icon,
                                  boolean selected, Button.OnPress press) {
        var stack = icon.getDefaultInstance();
        return Button.builder(name, press).bounds(x, y, w, h).build(builder -> new Button(builder) {
            @Override protected void renderWidget(GuiGraphics g, int mx, int my, float dt) {
                int l = getX(), t = getY();
                var font = Minecraft.getInstance().font;
                boolean showIcon = font.width(getMessage()) + 24 <= getWidth();
                g.fill(l, t, l + getWidth(), t + getHeight(), selected ? 0xFFE5C895 : isHoveredOrFocused() ? 0xFF505D49 : OLIVE);
                if (selected) g.fill(l, t, l + 3, t + getHeight(), BRASS);
                if (showIcon) g.renderItem(stack, l + 5, t + (getHeight() - 16) / 2);
                int start = l + (showIcon ? 24 : 4);
                int color = selected ? INK : IVORY;
                if (showIcon) g.drawString(font, getMessage(), start, t + (getHeight() - font.lineHeight) / 2 + 1, color, false);
                else renderScrollingString(g, font, getMessage(), start, t, l + getWidth() - 3, t + getHeight(), color);
                if (isFocused()) g.renderOutline(l, t, getWidth(), getHeight(), GOLD);
            }
        });
    }

    private CampaignStyle() {}
}
