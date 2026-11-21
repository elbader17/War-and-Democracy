package com.modserver.client.map;

import com.modserver.network.NationMapPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Nation map of the mod: full screen showing the real territory.
 *
 * <p>This is the "Layer A" of the plan: it does not depend on any installed minimap, so
 * players see their lands with the exact color they chose. External minimaps receive
 * separate markers (Layer B).</p>
 *
 * <p>Controls: wheel to zoom, drag to move, click a nation in the panel
 * to go to its capital.</p>
 */
public class NationMapScreen extends Screen {

    /** Enables the map debug trace: {@code -Dmodserver.debugMap=true}. */
    public static final String DEBUG_MAP_PROPERTY = "modserver.debugMap";

    private static final int PANEL_WIDTH = 150;
    private static final int TOP_BAR = 18;
    private static final int MIN_CELL = 3;
    private static final int MAX_CELL = 24;

    private double centerChunkX;
    private double centerChunkZ;
    private int cellSize = 8;

    private double dragStartX;
    private double dragStartY;
    private double dragCenterX;
    private double dragCenterZ;
    private boolean dragging;
    private boolean debugLogged;

    public NationMapScreen() {
        super(Component.translatable("screen.modserver.nation_map"));
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            centerChunkX = minecraft.player.chunkPosition().x;
            centerChunkZ = minecraft.player.chunkPosition().z;
        }
        com.modserver.command.ModServerCommands.LOGGER.info("{} MAPSCREEN opened at chunk ({}, {})",
                com.modserver.command.ModServerCommands.SELFTEST_MARKER, (int) centerChunkX, (int) centerChunkZ);
    }

    /**
     * Map debug trace (once per screen).
     *
     * <p>Only enabled with {@code -Dmodserver.debugMap=true}: it describes the map origin,
     * the cell size and the coordinates of each claim, which is what is needed to
     * diagnose a territory that does not show up.</p>
     */
    private void logDebug(NationMapPayload.NationView[] nations, NationMapPayload.ClaimView[] claims) {
        if (debugLogged || !Boolean.getBoolean(DEBUG_MAP_PROPERTY)) {
            return;
        }
        debugLogged = true;
        StringBuilder sb = new StringBuilder();
        sb.append("nations=").append(nations.length).append(" claims=").append(claims.length)
                .append(" center=(").append((int) centerChunkX).append(",").append((int) centerChunkZ)
                .append(") cell=").append(cellSize).append(" screen=").append(width).append("x").append(height);
        for (int i = 0; i < nations.length; i++) {
            sb.append(" | nation[").append(i).append("] ").append(nations[i].name())
                    .append(" color=0x").append(Integer.toHexString(nations[i].color()));
        }
        double originX = PANEL_WIDTH + (width - PANEL_WIDTH) / 2.0 - centerChunkX * cellSize;
        double originZ = TOP_BAR + (height - 12 - TOP_BAR) / 2.0 - centerChunkZ * cellSize;
        sb.append(" origin=(").append((int) originX).append(",").append((int) originZ).append(")");
        for (int i = 0; i < Math.min(claims.length, 6); i++) {
            NationMapPayload.ClaimView claim = claims[i];
            sb.append(" | claim[").append(i).append("] n=").append(claim.nationIndex())
                    .append(" (").append(claim.chunkX()).append(",").append(claim.chunkZ()).append(")")
                    .append(" px=").append((int) (originX + claim.chunkX() * cellSize))
                    .append(",").append((int) (originZ + claim.chunkZ() * cellSize));
        }
        com.modserver.command.ModServerCommands.LOGGER.info("{} MAPDEBUG {}",
                com.modserver.command.ModServerCommands.SELFTEST_MARKER, sb);
    }

    @Override
    protected void init() {
        super.init();
    }

    /** Opens the map from the outside (for example, when joining the world in automatic mode). */
    public static void open() {
        Minecraft.getInstance().setScreen(new NationMapScreen());
    }

    // ------------------------------------------------------------------ render
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);

        NationMapPayload.NationView[] nations = NationMapData.nations();
        NationMapPayload.ClaimView[] claims = NationMapData.claims();

        logDebug(nations, claims);
        drawMapArea(graphics, nations, claims);
        drawPanel(graphics, nations, mouseX, mouseY);
        drawTopBar(graphics, nations, claims);
    }

    private void drawTopBar(GuiGraphics graphics, NationMapPayload.NationView[] nations,
                            NationMapPayload.ClaimView[] claims) {
        graphics.fill(0, 0, width, TOP_BAR, 0xC0000000);
        graphics.drawString(font, "Nation map", 6, 5, 0xFFFFFF);
        String info = nations.length + " nations | " + claims.length + " visible chunks";
        graphics.drawString(font, info, width - font.width(info) - 6, 5, 0xB0B0B0);

        String hint = "Wheel: zoom | Drag: move | Panel: click to go to capital | ESC: close";
        graphics.fill(0, height - 12, width, height, 0xC0000000);
        graphics.drawString(font, hint, 6, height - 10, 0x909090);
    }

    private void drawMapArea(GuiGraphics graphics, NationMapPayload.NationView[] nations,
                             NationMapPayload.ClaimView[] claims) {
        int left = PANEL_WIDTH;
        int top = TOP_BAR;
        int right = width;
        int bottom = height - 12;

        graphics.fill(left, top, right, bottom, 0xFF101418);

        if (nations.length == 0) {
            graphics.drawCenteredString(font, "Waiting for server data...", (left + right) / 2,
                    (top + bottom) / 2 - 6, 0x808080);
            return;
        }

        double originX = left + (right - left) / 2.0 - centerChunkX * cellSize;
        double originZ = top + (bottom - top) / 2.0 - centerChunkZ * cellSize;

        // Grid every 8 chunks.
        int gridColor = 0x20FFFFFF;
        int firstGridX = (int) Math.floor((left - originX) / cellSize / 8.0) * 8;
        for (int gx = firstGridX; originX + gx * cellSize < right; gx += 8) {
            int px = (int) (originX + gx * cellSize);
            graphics.fill(px, top, px + 1, bottom, gridColor);
        }
        int firstGridZ = (int) Math.floor((top - originZ) / cellSize / 8.0) * 8;
        for (int gz = firstGridZ; originZ + gz * cellSize < bottom; gz += 8) {
            int py = (int) (originZ + gz * cellSize);
            graphics.fill(left, py, right, py + 1, gridColor);
        }

        // Territories: every chunk is painted, and the border of the whole
        // territory is drawn on top, so the land is seen as one shape and not
        // as a cloud of isolated squares.
        Set<Long> owned = new HashSet<>();
        for (NationMapPayload.ClaimView claim : claims) {
            if (claim.nationIndex() < 0 || claim.nationIndex() >= nations.length) {
                continue;
            }
            NationMapPayload.NationView nation = nations[claim.nationIndex()];
            int px = (int) (originX + claim.chunkX() * cellSize);
            int py = (int) (originZ + claim.chunkZ() * cellSize);
            // 0xC0000000 = alpha 0xC0 (75% opaque). Careful: the alpha goes in the HIGH
            // byte (ARGB); writing it as 0xC00000 would put 0xC0 in the red channel and
            // leave the color completely transparent.
            int fillColor = (nation.color() & 0xFFFFFF) | 0xC0000000;
            if (px + cellSize < left || px > right || py + cellSize < top || py > bottom) {
                continue;
            }
            graphics.fill(px, py, px + cellSize, py + cellSize, fillColor);
            owned.add(claimKey(claim.nationIndex(), claim.chunkX(), claim.chunkZ()));
        }

        // Border of each territory: the edges that are not shared with another
        // chunk of the same nation.
        for (NationMapPayload.ClaimView claim : claims) {
            if (claim.nationIndex() < 0 || claim.nationIndex() >= nations.length) {
                continue;
            }
            NationMapPayload.NationView nation = nations[claim.nationIndex()];
            int px = (int) (originX + claim.chunkX() * cellSize);
            int py = (int) (originZ + claim.chunkZ() * cellSize);
            if (px + cellSize < left || px > right || py + cellSize < top || py > bottom) {
                continue;
            }
            int border = brighten(nation.color() & 0xFFFFFF);
            if (!owned.contains(claimKey(claim.nationIndex(), claim.chunkX() - 1, claim.chunkZ()))) {
                graphics.fill(px, py, px + cellSize, py + 2, border);
            }
            if (!owned.contains(claimKey(claim.nationIndex(), claim.chunkX() + 1, claim.chunkZ()))) {
                graphics.fill(px, py + cellSize - 2, px + cellSize, py + cellSize, border);
            }
            if (!owned.contains(claimKey(claim.nationIndex(), claim.chunkX(), claim.chunkZ() - 1))) {
                graphics.fill(px, py, px + 2, py + cellSize, border);
            }
            if (!owned.contains(claimKey(claim.nationIndex(), claim.chunkX(), claim.chunkZ() + 1))) {
                graphics.fill(px + cellSize - 2, py, px + cellSize, py + cellSize, border);
            }
        }

        // Capitals.
        for (NationMapPayload.NationView nation : nations) {
            int px = (int) (originX + nation.capitalX() * cellSize);
            int py = (int) (originZ + nation.capitalZ() * cellSize);
            if (px < left - 4 || px > right + 4 || py < top - 4 || py > bottom + 4) {
                continue;
            }
            graphics.fill(px - 2, py - 2, px + 3, py + 3, 0xFFFFFFFF);
            graphics.fill(px - 1, py - 1, px + 2, py + 2, (nation.color() & 0xFFFFFF) | 0xFF000000);
        }

        // Player.
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            int px = (int) (originX + minecraft.player.chunkPosition().x * cellSize);
            int py = (int) (originZ + minecraft.player.chunkPosition().z * cellSize);
            graphics.fill(px - 3, py - 1, px + 4, py + 2, 0xFFFF5555);
            graphics.fill(px - 1, py - 3, px + 2, py + 4, 0xFFFF5555);
        }
    }

    private void drawPanel(GuiGraphics graphics, NationMapPayload.NationView[] nations,
                           int mouseX, int mouseY) {
        graphics.fill(0, TOP_BAR, PANEL_WIDTH, height - 12, 0xE0101418);

        List<NationMapPayload.NationView> sorted = new ArrayList<>(List.of(nations));
        sorted.sort(Comparator.comparingInt(NationMapPayload.NationView::chunkCount).reversed());

        int y = TOP_BAR + 6;
        for (NationMapPayload.NationView nation : sorted) {
            if (y > height - 24) {
                break;
            }
            graphics.fill(6, y, 14, y + 8, nation.color() & 0xFFFFFF);
            graphics.drawString(font, nation.name(), 20, y, 0xFFFFFF);
            String chunks = NationMapData.chunkCount(indexOf(nations, nation)) + "";
            graphics.drawString(font, chunks, PANEL_WIDTH - 6 - font.width(chunks), y, 0x9A9A9A);

            if (mouseX < PANEL_WIDTH && mouseY >= y && mouseY <= y + 9
                    && mouseX >= 6 && mouseX <= 14) {
                graphics.fill(6, y, 14, y + 8, 0x80FFFFFF);
            }
            y += 11;
        }
    }

    private static int indexOf(NationMapPayload.NationView[] nations, NationMapPayload.NationView nation) {
        for (int i = 0; i < nations.length; i++) {
            if (nations[i] == nation) {
                return i;
            }
        }
        return -1;
    }

    /** Key of a chunk inside a nation: it identifies "this chunk of this nation". */
    private static long claimKey(int nationIndex, int chunkX, int chunkZ) {
        return ((long) nationIndex << 32)
                | ((chunkX & 0xFFFFL) << 16)
                | (chunkZ & 0xFFFFL);
    }

    /** Blends a color with white: used for the border of the territories. */
    private static int brighten(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        r = r + (255 - r) / 2;
        g = g + (255 - g) / 2;
        b = b + (255 - b) / 2;
        return (r << 16) | (g << 8) | b;
    }

    // ------------------------------------------------------------------ events
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX < PANEL_WIDTH) {
            NationMapPayload.NationView[] nations = NationMapData.nations();
            List<NationMapPayload.NationView> sorted = new ArrayList<>(List.of(nations));
            sorted.sort(Comparator.comparingInt(NationMapPayload.NationView::chunkCount).reversed());
            int index = (int) ((mouseY - TOP_BAR - 6) / 11);
            if (index >= 0 && index < sorted.size()) {
                NationMapPayload.NationView nation = sorted.get(index);
                centerChunkX = nation.capitalX();
                centerChunkZ = nation.capitalZ();
                return true;
            }
        }
        if (button == 0) {
            dragging = true;
            dragStartX = mouseX;
            dragStartY = mouseY;
            dragCenterX = centerChunkX;
            dragCenterZ = centerChunkZ;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging) {
            centerChunkX = dragCenterX - (mouseX - dragStartX) / cellSize;
            centerChunkZ = dragCenterZ - (mouseY - dragStartY) / cellSize;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY > 0) {
            cellSize = Math.min(MAX_CELL, cellSize + 1);
        } else if (scrollY < 0) {
            cellSize = Math.max(MIN_CELL, cellSize - 1);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 262) { // right arrow
            centerChunkX += 4;
            return true;
        }
        if (keyCode == 263) { // left arrow
            centerChunkX -= 4;
            return true;
        }
        if (keyCode == 264) { // down arrow
            centerChunkZ += 4;
            return true;
        }
        if (keyCode == 265) { // up arrow
            centerChunkZ -= 4;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
