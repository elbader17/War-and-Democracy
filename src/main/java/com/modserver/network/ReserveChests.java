package com.modserver.network;

import com.modserver.ModServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Set;

public record ReserveChests(ResourceLocation dimension, Set<BlockPos> positions) implements CustomPacketPayload {
    public static final Type<ReserveChests> TYPE = new Type<>(ModServer.id("reserve_chests"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ReserveChests> CODEC = StreamCodec.of(
            (buf, view) -> {
                buf.writeResourceLocation(view.dimension());
                buf.writeCollection(view.positions(), (buffer, pos) -> buffer.writeBlockPos(pos));
            }, buf -> new ReserveChests(buf.readResourceLocation(), buf.readCollection(HashSet::new, buffer -> buffer.readBlockPos())));
    @Override public Type<ReserveChests> type() { return TYPE; }
}
