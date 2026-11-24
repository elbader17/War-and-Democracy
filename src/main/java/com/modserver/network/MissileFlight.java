package com.modserver.network;

import com.modserver.ModServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** One cosmetic flight, shared by the world, the tactical board and the server's particle cues. */
public record MissileFlight(ResourceLocation dimension, BlockPos origin, BlockPos target, int color, long started, boolean nuclear)
        implements CustomPacketPayload {
    public static final int LIFTOFF = 16;
    public int impactTick() { return nuclear ? 108 : 84; }
    public int endTick() { return impactTick() + (nuclear ? 88 : 44); }
    public static final Type<MissileFlight> TYPE = new Type<>(ModServer.id("missile_flight"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MissileFlight> CODEC = StreamCodec.of(
            (buf, shot) -> {
                buf.writeResourceLocation(shot.dimension);
                buf.writeBlockPos(shot.origin);
                buf.writeBlockPos(shot.target);
                buf.writeInt(shot.color);
                buf.writeVarLong(shot.started); buf.writeBoolean(shot.nuclear);
            }, buf -> new MissileFlight(buf.readResourceLocation(), buf.readBlockPos(), buf.readBlockPos(),
                    buf.readInt(), buf.readVarLong(), buf.readBoolean()));

    /** The launcher receives the same flight path with a one-time camera cue. */
    public record Launch(MissileFlight flight) implements CustomPacketPayload {
        public static final Type<Launch> TYPE = new Type<>(ModServer.id("missile_launch"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Launch> CODEC = MissileFlight.CODEC.map(Launch::new, Launch::flight);
        @Override public Type<Launch> type() { return TYPE; }
    }

    private double flightTime(double age) {
        return Mth.clamp((age - LIFTOFF) / (impactTick() - LIFTOFF), 0, 1);
    }

    public double horizontalProgress(double age) {
        double t = flightTime(age);
        return t * t * (3 - 2 * t);
    }

    public Vec3 position(double age) {
        Vec3 a = Vec3.atBottomCenterOf(origin).add(0, 1.65, 0);
        Vec3 b = Vec3.atBottomCenterOf(target).add(0, .65, 0);
        double t = flightTime(age);
        double arch = (nuclear ? 47 : 21) + .34 * Math.hypot(b.x - a.x, b.z - a.z);
        return a.lerp(b, horizontalProgress(age)).add(0, 4 * arch * t * (1 - t), 0);
    }

    @Override public Type<MissileFlight> type() { return TYPE; }
}
