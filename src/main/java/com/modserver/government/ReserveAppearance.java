package com.modserver.government;

import com.modserver.ModServer;
import com.modserver.network.ReserveChests;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Only tracked, loaded chests are advertised. No display entity is saved in the world. */
public final class ReserveAppearance {
    private static final Map<UUID, ReserveChests> views = new HashMap<>();
    private static final Map<UUID, Map<BlockPos, Integer>> vanillaBands = new HashMap<>();

    public static void forget(UUID player) {
        views.remove(player);
        vanillaBands.remove(player);
    }

    public static void update(MinecraftServer server) {
        for (var level : server.getAllLevels()) {
            if (level.players().isEmpty()) continue;
            var dimension = level.dimension().location();
            Set<BlockPos> active = new HashSet<>();
            for (var nation : ModServer.nations().allNations()) {
                if (nation.treasuryChest() == null || !dimension.toString().equals(nation.treasuryDimension())) continue;
                var pos = BlockPos.of(nation.treasuryChest());
                if (level.hasChunkAt(pos)) active.addAll(CapitalChest.visibleHalves(level, nation, pos));
            }
            for (var player : level.players()) {
                Set<BlockPos> visible = new HashSet<>();
                for (var pos : active)
                    if (player.getChunkTrackingView().contains(pos.getX() >> 4, pos.getZ() >> 4)) visible.add(pos);
                var next = new ReserveChests(dimension, visible);
                var previous = views.get(player.getUUID());
                if (next.equals(previous)) continue;
                views.put(player.getUUID(), next);
                if (NetworkRegistry.hasChannel(player.connection, ReserveChests.TYPE.id())) {
                    PacketDistributor.sendToPlayer(player, next);
                } else {
                    if (previous != null && !dimension.equals(previous.dimension())) vanillaBands.remove(player.getUUID());
                    updateBands(player, visible);
                }
            }
        }
    }

    /** Vanilla clients see a narrow gold band around the base, clear of the animated lid. */
    private static void updateBands(ServerPlayer player, Set<BlockPos> positions) {
        var bands = vanillaBands.computeIfAbsent(player.getUUID(), ignored -> new HashMap<>());
        bands.entrySet().removeIf(entry -> {
            if (positions.contains(entry.getKey())) return false;
            player.connection.send(new ClientboundRemoveEntitiesPacket(entry.getValue()));
            return true;
        });
        for (var pos : positions) bands.computeIfAbsent(pos, location -> {
            var band = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, player.serverLevel());
            var tag = new CompoundTag();
            tag.put("block_state", NbtUtils.writeBlockState(Blocks.GOLD_BLOCK.defaultBlockState()));
            var matrix = new ListTag();
            for (float v : new float[]{.89f,0,0,.055f, 0,.09f,0,.18f, 0,0,.89f,.055f, 0,0,0,1}) matrix.add(FloatTag.valueOf(v));
            tag.put("transformation", matrix);
            band.load(tag);
            player.connection.send(new ClientboundAddEntityPacket(band, 0, location));
            var metadata = band.getEntityData().getNonDefaultValues();
            if (metadata != null) player.connection.send(new ClientboundSetEntityDataPacket(band.getId(), metadata));
            return band.getId();
        });
    }

    private ReserveAppearance() {}
}
