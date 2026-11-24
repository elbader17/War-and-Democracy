package com.modserver.client.map;

import com.modserver.ModServer;
import com.modserver.network.TacticalBoard;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.FastColor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.Arrays;

/** Surface inks sampled from chunks the client has received. Blank paper covers uncharted ground. */
final class CampaignTerrain {
    private static final int SIDE = TacticalBoard.RADIUS * 2 + 1;
    private static final int SAMPLES = 4, SIZE = SIDE * SAMPLES;
    private static final ResourceLocation SIGNS = ModServer.id("textures/gui/campaign/terrain_signs.png");
    private final byte[] signs = new byte[SIDE * SIDE];
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private DynamicTexture texture;
    private ResourceLocation id;
    private ClientLevel world;
    private int centerX, centerZ, row;

    void tick(ClientLevel level, TacticalBoard board) {
        if (level == null || !level.dimension().location().equals(board.dimension())) return;
        if (texture == null) {
            texture = new DynamicTexture(SIZE, SIZE, true);
            id = Minecraft.getInstance().getTextureManager().register("campaign_surface", texture);
        }
        NativeImage image = texture.getPixels();
        if (image == null) return;
        boolean dirty = false;
        if (world != level || Math.abs(board.x() - centerX) > 6 || Math.abs(board.z() - centerZ) > 6) {
            world = level;
            centerX = board.x();
            centerZ = board.z();
            image.fillRect(0, 0, SIZE, SIZE, 0);
            Arrays.fill(signs, (byte) 0);
            row = 0;
            dirty = true;
        }
        // Start with the center of the sheet, then survey rows on either side over successive ticks.
        for (int i = 0; i < 3; i++) {
            int rz = TacticalBoard.RADIUS + (row % 2 == 0 ? row / 2 : -(row + 1) / 2);
            row = (row + 1) % SIDE;
            int cz = centerZ - TacticalBoard.RADIUS + rz;
            for (int rx = 0; rx < SIDE; rx++) {
                int cx = centerX - TacticalBoard.RADIUS + rx;
                var chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                int grass = 0, forest = 0, water = 0, low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
                for (int sz = 0; sz < SAMPLES; sz++) {
                    for (int sx = 0; sx < SAMPLES; sx++) {
                        int x = (cx << 4) + 2 + sx * 4, z = (cz << 4) + 2 + sz * 4;
                        int h = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                        int ink = 0;
                        if (h >= level.getMinBuildHeight()) {
                            pos.set(x, h, z);
                            var state = chunk.getBlockState(pos);
                            MapColor map = state.getMapColor(level, pos);
                            boolean leaves = state.is(BlockTags.LEAVES);
                            if (map == MapColor.GRASS) grass++;
                            if (leaves) forest++;
                            if (map == MapColor.WATER) water++;
                            low = Math.min(low, h);
                            high = Math.max(high, h);
                            int slope = h - chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z - 2);
                            if (map != MapColor.NONE) ink = surfaceInk(map, leaves, slope);
                        }
                        int px = rx * SAMPLES + sx, py = rz * SAMPLES + sz;
                        if (image.getPixelRGBA(px, py) != ink) {
                            image.setPixelRGBA(px, py, ink);
                            dirty = true;
                        }
                    }
                }
                byte sign = 0;
                if (water >= 8) sign = 4;
                else if (forest >= 6) sign = 2;
                else if (low <= high && high - low >= 6) sign = 3;
                else if (grass >= 9) sign = 1;
                signs[rz * SIDE + rx] = sign;
            }
        }
        if (dirty) texture.upload();
    }

    private static int surfaceInk(MapColor color, boolean leaves, int slope) {
        int rgb = color.col, opacity = 108;
        if (color == MapColor.WATER) { rgb = 0x3F869A; opacity = 183; }
        else if (leaves || color == MapColor.PLANT) { rgb = 0x4B6946; opacity = 146; }
        else if (color == MapColor.GRASS) { rgb = 0x89955C; opacity = slope == 0 ? 5 : 70; }
        else if (color == MapColor.SAND) { rgb = 0xC8995D; opacity = 90; }
        else if (color == MapColor.SNOW || color == MapColor.ICE) { rgb = 0xE0E6D9; opacity = 190; }
        int shade = Math.clamp(slope * 7, -36, 28);
        return FastColor.ABGR32.color(opacity, Math.clamp((rgb & 255) + shade, 0, 255),
                Math.clamp((rgb >> 8 & 255) + shade, 0, 255), Math.clamp((rgb >> 16 & 255) + shade, 0, 255));
    }

    void draw(GuiGraphics g, TacticalBoard board, int cx, int cz, int midX, int midY, int cell,
              int firstX, int lastX, int firstZ, int lastZ) {
        if (id == null || world == null || !world.dimension().location().equals(board.dimension())) return;
        int x = midX + (centerX - TacticalBoard.RADIUS - cx) * cell - cell / 2;
        int y = midY + (centerZ - TacticalBoard.RADIUS - cz) * cell - cell / 2;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(id, x, y, SIDE * cell, SIDE * cell, 0, 0, SIZE, SIZE, SIZE, SIZE);
        int minX = centerX - TacticalBoard.RADIUS, minZ = centerZ - TacticalBoard.RADIUS;
        for (int zc = Math.max(firstZ, minZ); zc <= Math.min(lastZ, minZ + SIDE - 1); zc++) {
            for (int xc = Math.max(firstX, minX); xc <= Math.min(lastX, minX + SIDE - 1); xc++) {
                int sign = signs[(zc - minZ) * SIDE + xc - minX];
                int hash = net.minecraft.util.Mth.murmurHash3Mixer(xc * 73471 ^ zc * 91283);
                if (sign == 0 || (hash & (sign == 1 ? 3 : 1)) != 0) continue;
                int mark = Math.clamp(cell / 2, 9, 22);
                int px = x + (xc - minX) * cell + ((hash >>> 8 & 255) * (cell - mark) / 255);
                int py = y + (zc - minZ) * cell + ((hash >>> 16 & 255) * (cell - mark) / 255);
                g.blit(SIGNS, px, py, mark, mark, (sign - 1) * 16, 0, 16, 16, 64, 16);
            }
        }
        RenderSystem.disableBlend();
    }

    void close() {
        if (id != null) Minecraft.getInstance().getTextureManager().release(id);
        texture = null;
        world = null;
        id = null;
    }
}
