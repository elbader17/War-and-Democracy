package com.modserver.client.map;

import com.modserver.command.ModServerCommands;
import com.modserver.claim.Claim;
import com.modserver.network.BorderedMapPayload;
import com.modserver.network.DisputeMapPayload;
import com.modserver.network.OilMapPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import com.modserver.network.NationMapPayload;
import net.minecraft.core.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Client cache of the nation map data.
 *
 * <p>The server fills it through {@link NationMapPayload} and both the map screen
 * and the minimap integration read from it.</p>
 */
public final class NationMapData {

    private static final Logger LOGGER = LoggerFactory.getLogger(NationMapData.class);

    private static NationMapPayload.NationView[] nations = new NationMapPayload.NationView[0];
    private static NationMapPayload.ClaimView[] claims = new NationMapPayload.ClaimView[0];
    public record Border(int x, int z, boolean horizontal, boolean international, int length) {
        static final int OUTER_COLOR = 0xFFDAE3E7;
        static final int INTERNATIONAL_COLOR = 0xFFFFD15C;

        private Border unitAtOffset(int offset) {
            return new Border(horizontal ? x + offset : x, horizontal ? z : z + offset,
                    horizontal, international, 1);
        }

        public int color() {
            return international ? INTERNATIONAL_COLOR : OUTER_COLOR;
        }
    }
    private static List<Long> oilFields = List.of();
    private static List<Border> borders = List.of();
    private static List<DisputeMapPayload.Battle> battles = List.of();
    private static ResourceKey<Level> dimension = Level.OVERWORLD;
    private static int playerY;
    private static long updatedAt;

    private NationMapData() {
    }

    public static void clear() {
        var empty = new NationMapPayload(List.of(), List.of(), 0);
        accept(new BorderedMapPayload(empty, new byte[0]), Level.OVERWORLD.location(), List.of(), List.of());
        updatedAt = 0;
    }

    /** Compatibility with servers that only send the visible claims. */
    public static void accept(NationMapPayload payload) {
        Map<Long, UUID> owners = new HashMap<>();
        for (var claim : payload.claims()) {
            owners.put(Claim.pack(claim.chunkX(), claim.chunkZ()), payload.nations().get(claim.nationIndex()).id());
        }
        accept(BorderedMapPayload.from(payload, (x, z) -> owners.get(Claim.pack(x, z))));
    }

    public static synchronized void accept(BorderedMapPayload snapshot) {
        var level = Minecraft.getInstance().level;
        accept(snapshot, level == null ? Level.OVERWORLD.location() : level.dimension().location(), List.of(), List.of());
    }

    public static void accept(DisputeMapPayload snapshot) {
        accept(snapshot.territory(), snapshot.dimension(), snapshot.battles(), List.of());
        TerritoryScreen.refreshIfOpen();
    }

    public static void accept(OilMapPayload snapshot) {
        var map = snapshot.map();
        accept(map.territory(), map.dimension(), map.battles(), snapshot.fields());
        TerritoryScreen.refreshIfOpen();
    }

    public static List<Long> oilFields() { return oilFields; }
    public static List<DisputeMapPayload.Battle> battles() { return battles; }
    public static ResourceKey<Level> dimension() { return dimension; }

    private static synchronized void accept(BorderedMapPayload snapshot, ResourceLocation world, List<DisputeMapPayload.Battle> open, List<Long> fields) {
        oilFields = List.copyOf(fields);
        battles = List.copyOf(open);
        dimension = ResourceKey.create(Registries.DIMENSION, world);
        NationMapPayload payload = snapshot.map();
        nations = payload.nations().toArray(new NationMapPayload.NationView[0]);
        claims = payload.claims().toArray(new NationMapPayload.ClaimView[0]);
        Set<Border> edges = new LinkedHashSet<>();
        for (int i = 0; i < claims.length; i++) {
            var claim = claims[i];
            int mask = snapshot.borders()[i];
            for (Direction face : Direction.Plane.HORIZONTAL) {
                int bit = 1 << face.get2DDataValue();
                if ((mask & bit) != 0) {
                    edges.add(new Border(claim.chunkX() + Math.max(0, face.getStepX()),
                            claim.chunkZ() + Math.max(0, face.getStepZ()), face.getStepZ() != 0,
                            (mask & (bit << 4)) != 0, 1));
                }
            }
        }
        List<Border> runs = new ArrayList<>();
        for (Border edge : edges) {
            if (edges.contains(edge.unitAtOffset(-1))) continue;
            int length = 1;
            while (edges.contains(edge.unitAtOffset(length))) length++;
            runs.add(new Border(edge.x(), edge.z(), edge.horizontal(), edge.international(), length));
        }
        borders = List.copyOf(runs);
        playerY = payload.playerY();
        updatedAt = System.currentTimeMillis();

        LOGGER.info("{} MAPDATA {} nations, {} claims, Y={}", ModServerCommands.SELFTEST_MARKER,
                nations.length, claims.length, playerY);

        // The player minimap is updated too (if it is installed).
        XaeroMapProvider.update(nations, claims, playerY);

        // And the territory is drawn as a shape in JourneyMap (if it is installed).
        JourneyMapProvider.update(nations, claims, playerY);
    }

    public static synchronized NationMapPayload.NationView[] nations() {
        return nations;
    }

    public static synchronized NationMapPayload.ClaimView[] claims() {
        return claims;
    }

    public static synchronized List<Border> borders() {
        return borders;
    }

    public static synchronized int playerY() {
        return playerY;
    }

    public static synchronized long updatedAt() {
        return updatedAt;
    }

    /** Claims of a nation by index. */
    public static synchronized List<NationMapPayload.ClaimView> claimsOf(int nationIndex) {
        List<NationMapPayload.ClaimView> result = new ArrayList<>();
        for (NationMapPayload.ClaimView claim : claims) {
            if (claim.nationIndex() == nationIndex) {
                result.add(claim);
            }
        }
        return result;
    }

    /** Number of chunks of a nation according to the received data. */
    public static synchronized int chunkCount(int nationIndex) {
        int total = 0;
        for (NationMapPayload.ClaimView claim : claims) {
            if (claim.nationIndex() == nationIndex) {
                total++;
            }
        }
        return total;
    }
}
