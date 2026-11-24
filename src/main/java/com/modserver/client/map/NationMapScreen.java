package com.modserver.client.map;

import com.modserver.network.NationMapPayload;
import com.modserver.client.CampaignStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    private static final int BORDER_SHADOW = 0xFF24272C;

    private double centerChunkX;
    private double centerChunkZ;
    private int cellSize = 14;

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
        graphics.fill(0, 0, width, TOP_BAR, CampaignStyle.CLOTH);
        graphics.drawString(font, getTitle(), 6, 5, CampaignStyle.GOLD);
        Component info = Component.translatable("screen.modserver.nation_map.counts", nations.length, claims.length);
        graphics.drawString(font, info, width - font.width(info) - 6, 5, CampaignStyle.IVORY);

        Component hint = Component.translatable("screen.modserver.nation_map.controls");
        graphics.fill(0, height - 12, width, height, CampaignStyle.CLOTH);
        graphics.drawString(font, hint, 6, height - 10, CampaignStyle.IVORY);
    }

    private void drawMapArea(GuiGraphics graphics, NationMapPayload.NationView[] nations,
                             NationMapPayload.ClaimView[] claims) {
        int left = PANEL_WIDTH;
        int top = TOP_BAR;
        int right = width;
        int bottom = height - 12;

        CampaignStyle.paper(graphics, left, top, right - left, bottom - top);

        if (nations.length == 0 && NationMapData.battles().isEmpty() && NationMapData.oilFields().isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.modserver.nation_map.empty"), (left + right) / 2,
                    (top + bottom) / 2 - 6, CampaignStyle.INK);
            return;
        }

        double originX = left + (right - left) / 2.0 - centerChunkX * cellSize;
        double originZ = top + (bottom - top) / 2.0 - centerChunkZ * cellSize;

        graphics.enableScissor(left, top, right, bottom);

        // Grid every 8 chunks.
        int gridColor = 0x30453522;
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
        }

        // Paint all casings first so adjoining segments meet without dark seams.
        drawBorders(graphics, originX, originZ, true);
        drawBorders(graphics, originX, originZ, false);

        for (var battle : NationMapData.battles()) {
            int x = (int) Math.floor(originX + battle.x() * cellSize);
            int y = (int) Math.floor(originZ + battle.z() * cellSize);
            if (x + cellSize < left || x > right || y + cellSize < top || y > bottom) continue;
            graphics.fill(x, y, x + cellSize, y + cellSize, 0xFFE23E4D);
            for (int k = 1; k < cellSize - 1; k++) {
                graphics.fill(x + k, y + k, x + k + 1, y + k + 1, 0xFFFFF1DA);
                graphics.fill(x + cellSize - 1 - k, y + k, x + cellSize - k, y + k + 1, 0xFFFFF1DA);
            }
        }

        for (long key : NationMapData.oilFields()) {
            int px = (int)Math.floor(originX + com.modserver.claim.Claim.unpackX(key) * cellSize);
            int py = (int)Math.floor(originZ + com.modserver.claim.Claim.unpackZ(key) * cellSize);
            if (px + cellSize < left || px > right || py + cellSize < top || py > bottom) continue;
            graphics.renderOutline(px, py, cellSize, cellSize, OilMapMarker.GOLD);
            int h = Math.clamp(cellSize, 9, 18);
            OilMapMarker.draw(graphics, px + cellSize / 2, py + cellSize / 2 - h, h);
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
        graphics.disableScissor();
    }

    private void drawBorders(GuiGraphics graphics, double originX, double originZ, boolean casing) {
        for (var edge : NationMapData.borders()) {
            int x = (int) Math.floor(originX + edge.x() * cellSize);
            int y = (int) Math.floor(originZ + edge.z() * cellSize);
            int stroke = cellSize >= 10 ? 2 : 1;
            int before = stroke / 2;
            int after = stroke - before;
            if (casing && cellSize >= 6) {
                before++;
                after++;
            }
            int color = casing ? BORDER_SHADOW : edge.color();
            int length = edge.length() * cellSize;
            if (edge.horizontal()) {
                graphics.fill(x - before, y - before, x + length + after, y + after, color);
            } else {
                graphics.fill(x - before, y - before, x + after, y + length + after, color);
            }
        }
    }

    private void drawPanel(GuiGraphics graphics, NationMapPayload.NationView[] nations,
                           int mouseX, int mouseY) {
        graphics.fill(0, TOP_BAR, PANEL_WIDTH, height - 12, CampaignStyle.OLIVE);
        graphics.fill(PANEL_WIDTH - 1, TOP_BAR, PANEL_WIDTH, height - 12, CampaignStyle.BRASS);

        List<NationMapPayload.NationView> sorted = new ArrayList<>(List.of(nations));
        sorted.sort(Comparator.comparingInt(NationMapPayload.NationView::chunkCount).reversed());

        int y = TOP_BAR + 6;
        for (NationMapPayload.NationView nation : sorted) {
            if (y > height - (NationMapData.oilFields().isEmpty() ? 62 : 75)) {
                break;
            }
            graphics.fill(6, y, 14, y + 8, 0xFF000000 | (nation.color() & 0xFFFFFF));
            graphics.drawString(font, nation.name(), 20, y, 0xFFFFFF);
            String chunks = NationMapData.chunkCount(indexOf(nations, nation)) + "";
            graphics.drawString(font, chunks, PANEL_WIDTH - 6 - font.width(chunks), y, CampaignStyle.GOLD);

            if (mouseX < PANEL_WIDTH && mouseY >= y && mouseY <= y + 9
                    && mouseX >= 6 && mouseX <= 14) {
                graphics.fill(6, y, 14, y + 8, 0x80FFFFFF);
            }
            y += 11;
        }
        if (!NationMapData.oilFields().isEmpty()) {
            OilMapMarker.draw(graphics, 12, height - 68, 14);
            graphics.drawString(font, Component.translatable("map.modserver.oil"), 20, height - 65, CampaignStyle.IVORY, false);
        }
        drawLegendEntry(graphics, height - 52, 0xFFE23E4D, "battle");
        drawLegendEntry(graphics, height - 39, NationMapData.Border.INTERNATIONAL_COLOR, "shared");
        drawLegendEntry(graphics, height - 26, NationMapData.Border.OUTER_COLOR, "outer");
    }

    private static int indexOf(NationMapPayload.NationView[] nations, NationMapPayload.NationView nation) {
        for (int i = 0; i < nations.length; i++) {
            if (nations[i] == nation) {
                return i;
            }
        }
        return -1;
    }

    private void drawLegendEntry(GuiGraphics graphics, int y, int color, String key) {
        graphics.fill(6, y + 2, 17, y + 6, BORDER_SHADOW);
        graphics.fill(6, y + 3, 17, y + 5, color);
        graphics.drawString(font, Component.translatable("screen.modserver.nation_map." + key), 21, y, 0xFFFFFF);
    }

    // ------------------------------------------------------------------ events
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1 && mouseX >= PANEL_WIDTH && mouseY >= TOP_BAR && mouseY < height - 12 && TerritoryScreen.supported()) {
            int x = (int) Math.floor(centerChunkX + (mouseX - PANEL_WIDTH - (width - PANEL_WIDTH) / 2.0) / cellSize);
            int z = (int) Math.floor(centerChunkZ + (mouseY - TOP_BAR - (height - 12 - TOP_BAR) / 2.0) / cellSize);
            TerritoryScreen.open(NationMapData.dimension().location(), x, z);
            return true;
        }
        if (button == 0 && mouseX < PANEL_WIDTH && mouseY >= TOP_BAR + 6 && mouseY < height - 44) {
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
