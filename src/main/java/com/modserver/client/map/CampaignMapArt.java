package com.modserver.client.map;

import com.modserver.military.TroopBranch;
import com.modserver.ModServer;
import com.modserver.client.CampaignStyle;
import com.modserver.claim.Claim;
import com.modserver.network.TacticalBoard;
import com.modserver.military.TroopFormation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.core.Direction;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

final class CampaignMapArt {
    private static final int INK = 0xFF3D372B, PAPER = 0xFFEBD7A8;
    private static final ResourceLocation PIECES = ModServer.id("textures/gui/campaign/pieces.png");

    private static final List<Direction> SIDES = List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST);
    private record Nameplate(UUID owner, int x, int z, int length, Direction side) {}
    private boolean hasOil;
    boolean hasOil() { return hasOil; }

    private List<Nameplate> names = List.of();

    void setBoard(TacticalBoard board) {
        // Record runs of exposed border. At draw time a name can use another edge if its usual one is off-screen.
        hasOil = board.cells().values().stream().anyMatch(TacticalBoard.Cell::oilField);
        var edges = new ArrayList<Nameplate>();
        for (var entry : board.cells().entrySet()) {
            int x = Claim.unpackX(entry.getKey()), z = Claim.unpackZ(entry.getKey());
            UUID owner = entry.getValue().owner();
            for (Direction side : SIDES) {
                int dx = Math.abs(side.getStepZ()), dz = Math.abs(side.getStepX());
                if (Math.abs(x + side.getStepX() - board.x()) > TacticalBoard.RADIUS
                        || Math.abs(z + side.getStepZ() - board.z()) > TacticalBoard.RADIUS) continue;
                if (!exposed(board, owner, x, z, side) || exposed(board, owner, x - dx, z - dz, side)) continue;
                int length = 1;
                while (exposed(board, owner, x + length * dx, z + length * dz, side)) length++;
                edges.add(new Nameplate(owner, x, z, length, side));
            }
        }
        edges.sort(Comparator.comparingInt(Nameplate::length).reversed().thenComparing(Nameplate::side)
                .thenComparingInt(Nameplate::z).thenComparingInt(Nameplate::x));
        names = edges;
    }

    private static boolean exposed(TacticalBoard board, UUID owner, int x, int z, Direction side) {
        var c = board.at(x, z);
        return c != null && c.owner().equals(owner) && board.at(x + side.getStepX(), z + side.getStepZ()) == null;
    }

    static void paper(GuiGraphics g, int top, int bottom, int width) {
        CampaignStyle.paper(g, 0, top, width, bottom - top);
    }

    private static int ink(int color) {
        return FastColor.ARGB32.lerp(.66f, 0xFF000000 | color, INK);
    }

    private static boolean border(TacticalBoard board, UUID owner, int x, int z) {
        if (Math.abs(x - board.x()) > TacticalBoard.RADIUS || Math.abs(z - board.z()) > TacticalBoard.RADIUS)
            return false;
        var neighbor = board.at(x, z);
        return neighbor == null || !owner.equals(neighbor.owner());
    }

    static void zone(GuiGraphics g, TacticalBoard board, TacticalBoard.Cell c, int x, int z, int px, int py, int size) {
        int color = board.countries().get(c.owner()).color();
        int right = px + size, bottom = py + size, edge = ink(color);
        g.fill(px, py, right, bottom, 0x82000000 | (color & 0xFFFFFF));
        g.fill(px, py, right, py + 1, 0x3041392E);
        g.fill(px, py, px + 1, bottom, 0x3041392E);
        if (c.oilField()) {
            g.fill(px + 2, py + 2, right - 2, bottom - 2, 0x55F2BD57);
            g.renderOutline(px + 1, py + 1, size - 2, size - 2, OilMapMarker.INK);
            g.renderOutline(px + 2, py + 2, size - 4, size - 4, OilMapMarker.GOLD);
        }
        if (c.disputed()) {
            g.fill(px, py, right, bottom, 0x45A84832);
            for (int d = 0; d < size * 2; d += 7) {
                for (int i = Math.max(0, d - size + 1); i <= Math.min(size - 1, d); i += 2) {
                    int j = d - i;
                    g.fill(px + i, py + j, Math.min(right, px + i + 2), py + j + 1, 0x8E963D2F);
                }
            }
        }
        if (border(board, c.owner(), x, z - 1)) g.fill(px, py, right, py + 2, edge);
        if (border(board, c.owner(), x, z + 1)) g.fill(px, bottom - 2, right, bottom, edge);
        if (border(board, c.owner(), x - 1, z)) g.fill(px, py, px + 2, bottom, edge);
        if (border(board, c.owner(), x + 1, z)) g.fill(right - 2, py, right, bottom, edge);
    }

    private static void symbol(GuiGraphics g, int icon, int x, int y, int size) {
        g.blit(PIECES, x, y, size, size, icon * 16, 0, 16, 16, 224, 16);
    }

    static void garrison(GuiGraphics g, Font font, TacticalBoard.Cell c, int px, int py, int size, int color) {
        if (c.oilField()) {
            int height = c.armies() == 0 ? Math.min(size - 6, 28) : Math.clamp(size / 2, 10, 22);
            int center = c.armies() == 0 ? px + size / 2 : px + size - Math.round(height * .42f) - 2;
            OilMapMarker.draw(g, center, py + (size - height) / 2, height);
            if (c.armies() > 0) {
                g.pose().pushPose();
                g.pose().translate(px + 1, py + size * .24f, 0);
                g.pose().scale(.65f, .65f, 1);
                troops(g, font, c, 0, 0, size, color);
                g.pose().popPose();
            } else {
                badge(g, font, "0", px + 5, py + size - 7, 8, color, .65f);
            }
        } else troops(g, font, c, px, py, size, color);
        int mark = Math.clamp(size / 3, 6, 11);
        if (c.disputed()) symbol(g, 2, px + 2, py + 1, mark);
        if (c.nuclearMissiles() > 0) g.fill(px + size - mark - 3, py + 1, px + size - 1, py + mark + 2, 0xFFE38A48);
        if (c.missiles() > 0 || c.nuclearMissiles() > 0) symbol(g, 1, px + size - mark - 2, py + 1, mark);
    }

    private static void troops(GuiGraphics g, Font font, TacticalBoard.Cell c, int px, int py, int size, int color) {
        var branch = c.forces().largestBranch();
        int troops = c.forces().count(branch);
        boolean mixed = troops < c.armies();
        var formation = TroopFormation.largest(troops);
        int mx = px + size / 2, my = py + size / 2;
        boolean hasStatus = c.disputed() || c.missiles() > 0 || c.nuclearMissiles() > 0;
        if (formation == TroopFormation.SOLDIER) {
            if (size >= 25 && troops > 0) {
                int soldier = Math.min(17, size / 2);
                int x = mx - (soldier + 5) / 2, y = my - soldier / 2 + (hasStatus ? 2 : 0);
                if (troops > 1) branchSymbol(g, branch, formation, x + 3, y - 2, soldier - 2);
                branchSymbol(g, branch, formation, x, y, soldier);
                g.drawString(font, troops + (mixed ? "+" : ""), x + soldier, y + soldier / 2, INK, false);
            } else {
                if (troops > 0) branchSymbol(g, branch, formation, mx - 4, py + 1, 9);
                badge(g, font, troops + (mixed ? "+" : ""), mx, py + size - 7, size, color, .75f);
            }
        } else {
            int pieces = formation.pieces(troops);
            String count = "×" + pieces;
            boolean more = mixed || troops % formation.value != 0;
            // Keep the multiplier readable even for the largest possible server-side garrison.
            if (font.width(count) > size - 4 && pieces >= 1000) {
                int divisor = pieces >= 1_000_000 ? 1_000_000 : 1_000;
                count = "×" + pieces / divisor + (divisor == 1000 ? "k" : "M");
                more |= pieces % divisor != 0;
            }
            if (more) count += "+";
            int icon = Math.clamp(size - 12, 8, 23);
            int y = py + Math.max(1, (size - icon - 9) / 2);
            branchSymbol(g, branch, formation, mx - icon / 2, y, icon);
            badge(g, font, count, mx, py + size - (size < 25 ? 8 : 11), size, color, size < 25 ? .75f : 1f);
        }
    }

    private static void formationSymbol(GuiGraphics g, TroopFormation formation, int x, int y, int size) {
        int icon = switch (formation) {
            case SOLDIER -> 0;
            case SQUAD -> 3;
            case COMPANY -> 4;
            case BATTALION -> 5;
        };
        symbol(g, icon, x, y, size);
    }

    private static void branchSymbol(GuiGraphics g, TroopBranch branch, TroopFormation tier, int x, int y, int size) {
        if (branch == TroopBranch.GROUND) formationSymbol(g, tier, x, y, size);
        else symbol(g, (branch == TroopBranch.AIR ? 6 : 10) + tier.ordinal(), x, y, size);
    }

    private static void badge(GuiGraphics g, Font font, String count, int center, int y, int size, int color, float maxScale) {
        float scale = Math.min(maxScale, (size - 4f) / font.width(count));
        int width = (int)Math.ceil(font.width(count) * scale) + 2;
        int left = center - width / 2, bottom = y + (int)Math.ceil(8 * scale);
        g.fill(left, y - 1, left + width, bottom, INK);
        g.fill(left, bottom, left + width, bottom + 1, 0xFF000000 | color);
        g.pose().pushPose();
        g.pose().translate(left + 1, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, count, 0, 0, 0xFFFFF3D4, false);
        g.pose().popPose();
    }

    private static void pennant(GuiGraphics g, int x, int y, int color) {
        g.fill(x, y, x + 1, y + 10, INK);
        g.fill(x + 1, y + 1, x + 8, y + 5, ink(color));
        g.fill(x + 1, y + 1, x + 7, y + 4, 0xFF000000 | color);
        g.fill(x + 1, y + 5, x + 5, y + 6, ink(color));
    }

    void names(GuiGraphics g, Font font, TacticalBoard board, int cx, int cz, int midX, int midY,
               int size, int width, int top, int bottom) {
        var placed = new ArrayList<ScreenRectangle>();
        var labeled = new HashSet<UUID>();
        for (var name : names) {
            if (labeled.contains(name.owner)) continue;
            int dx = name.side.getStepX(), dz = name.side.getStepZ();
            int edgeX = midX + (name.x - cx) * size + (Math.abs(dz) * (name.length - 1) + dx) * size / 2;
            int edgeY = midY + (name.z - cz) * size + (Math.abs(dx) * (name.length - 1) + dz) * size / 2;
            if (edgeX < 0 || edgeX >= width || edgeY < top || edgeY >= bottom) continue;
            var country = board.countries().get(name.owner);
            var nameText = Component.literal(country.name()).withStyle(ChatFormatting.BOLD);
            var text = font.substrByWidth(nameText, Math.min(114, width / 2));
            int w = font.width(text) + 19, h = 14;
            int x = dx < 0 ? edgeX - w - 3 : dx > 0 ? edgeX + 3 : edgeX - w / 2;
            int y = dz < 0 ? edgeY - h - 3 : dz > 0 ? edgeY + 3 : edgeY - h / 2;
            if (x < 2 || x + w > width - 2 || y < top + 1 || y + h > bottom - 1) continue;
            var rect = new ScreenRectangle(x - 1, y - 1, w + 2, h + 2);
            if (placed.stream().anyMatch(rect::overlaps)) continue;
            int firstX = cx + Math.floorDiv(x - midX + size / 2, size);
            int lastX = cx + Math.floorDiv(x + w - 1 - midX + size / 2, size);
            int firstZ = cz + Math.floorDiv(y - midY + size / 2, size);
            int lastZ = cz + Math.floorDiv(y + h - 1 - midY + size / 2, size);
            boolean clear = true;
            for (int z = firstZ; z <= lastZ && clear; z++) {
                for (int i = firstX; i <= lastX; i++) {
                    if (board.at(i, z) != null) { clear = false; break; }
                }
            }
            if (!clear) continue;
            placed.add(rect);
            labeled.add(name.owner);
            int ink = ink(country.color());
            g.fill(Math.min(edgeX, edgeX + 4 * dx), Math.min(edgeY, edgeY + 4 * dz),
                    Math.max(edgeX, edgeX + 4 * dx) + 1, Math.max(edgeY, edgeY + 4 * dz) + 1, ink);
            g.fill(x + 1, y + 2, x + w + 1, y + h + 1, 0x65443829);
            g.fill(x, y, x + w, y + h, ink);
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, PAPER);
            pennant(g, x + 4, y + 2, country.color());
            g.drawString(font, net.minecraft.locale.Language.getInstance().getVisualOrder(text), x + 15, y + 3, INK, false);
        }
    }

    /** Returns whether the pointer is on the formation key. */
    static boolean legend(GuiGraphics g, Font font, int width, int y, int cell, int mx, int my, boolean hasOil) {
        g.fill(0, y, width, y + 16, 0xFFDAC69B);
        g.fill(0, y, width, y + 1, 0xFF786644);
        int x = 5;
        if (hasOil) {
            var oil = Component.translatable("map.modserver.oil");
            OilMapMarker.draw(g, x + 6, y + 1, 14);
            g.drawString(font, oil, x + 15, y + 4, INK, false);
            x += 22 + font.width(oil);
        }
        int keyStart = x;
        int room = width - x;
        if (room >= 270) {
            for (var tier : TroopFormation.LARGEST_FIRST.reversed()) {
                formationSymbol(g, tier, x, y + 2, 12);
                String value = Integer.toString(tier.value);
                g.drawString(font, value, x + 13, y + 4, INK, false);
                x += font.width(value) + 20;
            }
        } else {
            Component label = Component.translatable("tac.legend.armies");
            formationSymbol(g, TroopFormation.SOLDIER, x, y + 2, 12);
            if (room >= 140) g.drawString(font, label, x + 13, y + 4, INK, false);
            x += room >= 140 ? font.width(label) + 20 : 18;
        }
        boolean overKey = mx >= keyStart && mx < x && my >= y && my < y + 16;
        for (int icon = 1; icon <= 2; icon++) {
            Component label = Component.translatable(icon == 1 ? "tac.legend.missiles" : "tac.legend.disputes");
            int w = room < 195 ? 19 : font.width(label) + 18;
            symbol(g, icon, x, y + 2, 12);
            if (room >= 195) g.drawString(font, label, x + 13, y + 4, INK, false);
            else if (mx >= x && mx < x + w && my >= y && my < y + 16) g.renderTooltip(font, label, mx, my);
            x += w;
        }
        Component scale = Component.translatable("tac.scale", 16);
        int start = width - font.width(scale) - cell - 13;
        if (start > x) {
            g.fill(start, y + 8, start + cell, y + 10, INK);
            g.fill(start, y + 5, start + 1, y + 11, INK);
            g.fill(start + cell - 1, y + 5, start + cell, y + 11, INK);
            g.drawString(font, scale, start + cell + 4, y + 4, INK, false);
        }
        return overKey;
    }

    private static void formationKey(GuiGraphics g, Font font, NumberFormat numbers, int mx, int my, int width, int height) {
        int numberWidth = font.width(numbers.format(1000)) + 10;
        int[] widths = new int[3];
        int w = numberWidth + 8;
        for (var arm : TroopBranch.ALL) {
            int longest = font.width(Component.translatable("tac.branch." + arm.id));
            for (var tier : TroopFormation.LARGEST_FIRST)
                longest = Math.max(longest, font.width(Component.translatable(tier.key(arm))));
            widths[arm.ordinal()] = longest + 19;
            w += widths[arm.ordinal()];
        }
        var rule = Component.translatable("tac.branch.rules");
        int h = 91 + font.wordWrapHeight(rule, w - 12);
        int x = Math.clamp(mx + 12, 1, Math.max(1, width - w - 1));
        int y = Math.clamp(my - h - 3, 1, Math.max(1, height - h - 1));
        g.fill(x, y, x + w, y + h, PAPER);
        g.renderOutline(x, y, w, h, INK);
        g.fill(x + 1, y + 1, x + w - 1, y + 15, CampaignStyle.CLOTH);
        g.drawString(font, Component.translatable("tac.formation.key"), x + 5, y + 4, CampaignStyle.GOLD, false);
        int col = x + numberWidth;
        for (var arm : TroopBranch.ALL) {
            g.drawString(font, Component.translatable("tac.branch." + arm.id), col, y + 18, INK, false);
            int rowY = y + 30;
            for (var tier : TroopFormation.LARGEST_FIRST.reversed()) {
                if (arm == TroopBranch.GROUND) g.drawString(font, numbers.format(tier.value), x + 5, rowY + 2, INK, false);
                branchSymbol(g, arm, tier, col, rowY, 12);
                g.drawString(font, Component.translatable(tier.key(arm)), col + 14, rowY + 2, INK, false);
                rowY += 13;
            }
            col += widths[arm.ordinal()];
        }
        g.fill(x + 5, y + 83, x + w - 5, y + 84, 0xFFB8A27B);
        g.drawWordWrap(font, rule, x + 5, y + 88, w - 12, INK);
    }

    /** A field-strength card with one row per branch. A null cell shows the legend instead. */
    static void formationCard(GuiGraphics g, Font font, TacticalBoard.Cell cell, NumberFormat numbers,
                              int mx, int my, int screenWidth, int screenHeight) {
        record Row(TroopBranch arm, TroopFormation icon, Component text) {}
        var rows = new ArrayList<Row>();
        if (cell == null) {
            formationKey(g, font, numbers, mx, my, screenWidth, screenHeight);
            return;
        }
        boolean battle = cell.attackers() > 0;
        for (int side = 0; side < (battle ? 2 : 1); side++) {
            var force = side == 0 ? cell.forces() : cell.invading();
            String heading = !battle ? "total" : side == 0 ? "defense" : "attack";
            rows.add(new Row(null, null, Component.translatable("tac.formation." + heading, numbers.format(force.total()))));
            for (var arm : TroopBranch.ALL) {
                int troops = force.count(arm);
                if (troops == 0) continue;
                var line = Component.translatable("tac.branch." + arm.id).append("  " + numbers.format(troops));
                var groups = new java.util.StringJoiner(" + ");
                for (var tier : TroopFormation.LARGEST_FIRST) {
                    int pieces = tier.pieces(troops);
                    if (pieces > 0) groups.add(numbers.format(pieces) + "×" + numbers.format(tier.value));
                }
                if (troops >= 10) line.append(Component.literal("   " + groups).withColor(0x645943));
                rows.add(new Row(arm, TroopFormation.largest(troops), line));
            }
        }
        int w = 0;
        for (var row : rows) w = Math.max(w, font.width(row.text) + (row.icon == null ? 12 : 27));
        int h = rows.size() * 13 + 6;
        int x = mx + 12 + w <= screenWidth ? mx + 12 : Math.max(1, mx - w - 8);
        int y = Math.clamp(my - 8, 1, Math.max(1, screenHeight - h - 1));
        g.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x70352D20);
        g.fill(x, y, x + w, y + h, PAPER);
        g.renderOutline(x, y, w, h, INK);
        int top = y + 3;
        for (var row : rows) {
            if (row.icon == null) {
                g.fill(x + 1, top - 1, x + w - 1, top + 11, CampaignStyle.CLOTH);
                g.drawString(font, row.text, x + 5, top + 1, CampaignStyle.GOLD, false);
            } else {
                branchSymbol(g, row.arm, row.icon, x + 5, top - 1, 12);
                g.drawString(font, row.text, x + 20, top + 2, INK, false);
            }
            top += 13;
        }
    }
}
