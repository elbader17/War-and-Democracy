package com.modserver.defense;

import com.modserver.ModServer;
import com.modserver.nation.Nation;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;

import java.util.*;

public final class TurretEntity extends KineticBlockEntity {
    public float wheelAngle;
    public float heading, elevation, previousHeading, previousElevation;
    public long firedAt = -100;
    private float aim, pitch;
    private UUID target;

    public TurretEntity(BlockPos pos, BlockState state) {
        super(DefenseHardware.TURRET.get(), pos, state);
    }

    public DefenseTier tier() {
        return ((TurretBlock) getBlockState().getBlock()).tier;
    }

    @Override
    public float calculateStressApplied() {
        return lastStressApplied = tier().stress;
    }

    boolean support(ServerLevel level, BlockPos pos, UUID owner) {
        return level.isLoaded(pos)
                && owner.equals(DefenseRegister.land(DefenseRegister.dimension(level), pos))
                && BuiltInRegistries.BLOCK
                        .getKey(level.getBlockState(pos).getBlock())
                        .toString()
                        .equals("create:brass_casing");
    }

    private boolean braced(ServerLevel level, BlockPos center, UUID owner) {
        return support(level, center.east(), owner) && support(level, center.west(), owner)
                || support(level, center.north(), owner) && support(level, center.south(), owner);
    }

    private boolean feed(ServerLevel level, UUID owner, boolean simulate) {
        var ammo = DefenseHardware.ammo(tier());
        for (var face : Direction.Plane.HORIZONTAL) {
            var pos = worldPosition.relative(face);
            if (!level.isLoaded(pos)
                    || !owner.equals(DefenseRegister.land(DefenseRegister.dimension(level), pos)))
                continue;
            var inventory =
                    level.getCapability(Capabilities.ItemHandler.BLOCK, pos, face.getOpposite());
            if (inventory == null) continue;
            for (int i = 0; i < inventory.getSlots(); i++) {
                if (inventory.getStackInSlot(i).is(ammo)
                        && inventory.extractItem(i, 1, simulate).is(ammo)) return true;
            }
        }
        return false;
    }

    public String status(ServerLevel level, DefenseRegister.Station station) {
        if (station.owner == null) return "Outside national territory";
        if (!station.armed) return "Hold fire";
        if (Math.abs(getSpeed()) < tier().rpm) return "Needs rotation from below";
        if (tier().battery()
                && !braced(level, worldPosition, station.owner)
                && !braced(level, worldPosition.below(), station.owner))
            return "Needs two opposite brass casings";
        if (station.radar
                && (!ModList.get().isLoaded("create_radar")
                        || RadarFeed.linked(level, station) == null)) return "Radar offline";
        if (!feed(level, station.owner, true))
            return "Needs " + DefenseHardware.ammo(tier()).getDescription().getString();
        return "Ready";
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null) return;
        wheelAngle = (wheelAngle + getSpeed() * .3f) % 360;
        previousHeading = heading;
        previousElevation = elevation;
        heading = Mth.approachDegrees(heading, aim, 8);
        elevation = Mth.approach(elevation, pitch, 5);
        if (!(level instanceof ServerLevel server)
                || (server.getGameTime() + worldPosition.asLong()) % 5 != 0) return;
        var station = DefenseRegister.of(server).station(server, worldPosition, tier());
        if (!"Ready".equals(status(server, station))) return;
        Nation nation = ModServer.nations().nations().byId(station.owner).orElse(null);
        if (nation == null) return;
        Vec3 origin = Vec3.atCenterOf(worldPosition).add(0, .65, 0);
        Set<UUID> tracks = station.radar ? RadarFeed.contacts(server, station) : null;
        var contact =
                DefenseContact.nearby(server, tier(), origin, tier().range).stream()
                        .filter(c -> c.point().distanceToSqr(origin) <= tier().range * tier().range)
                        .filter(c -> tracks == null || tracks.contains(c.id()))
                        .filter(
                                c ->
                                        station.hostile(nation, c.id(), c.nation())
                                                && station.covers(c.point()))
                        .filter(c -> clear(server, origin, c))
                        .min(Comparator.comparingDouble(c -> c.point().distanceToSqr(origin)))
                        .orElse(null);
        if (contact == null) {
            target = null;
            return;
        }
        Vec3 delta = contact.point().subtract(origin);
        float nextAim = (float) Math.toDegrees(Math.atan2(delta.x, delta.z));
        float nextPitch = (float) Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance()));
        if (!contact.id().equals(target)
                || Math.abs(Mth.wrapDegrees(nextAim - aim)) > 1
                || Math.abs(nextPitch - pitch) > 1) {
            target = contact.id();
            aim = nextAim;
            pitch = nextPitch;
            sendData();
        }
        if (Math.abs(Mth.wrapDegrees(heading - nextAim)) > 12
                || Math.abs(elevation - nextPitch) > 12
                || server.getGameTime() - firedAt < tier().reload
                || !feed(server, station.owner, true)) return;
        var shot = DefenseRound.launch(server, station, contact, origin);
        if (shot == null) return;
        if (!feed(server, station.owner, false)) {
            shot.discard();
            return;
        }
        firedAt = server.getGameTime();
        setChanged();
        sendData();
        server.playSound(
                null,
                worldPosition,
                tier().battery()
                        ? net.minecraft.sounds.SoundEvents.FIREWORK_ROCKET_LAUNCH
                        : net.minecraft.sounds.SoundEvents.CROSSBOW_SHOOT,
                net.minecraft.sounds.SoundSource.BLOCKS,
                1f,
                tier().battery() ? .7f : .85f);
        server.sendParticles(
                net.minecraft.core.particles.ParticleTypes.POOF,
                origin.x,
                origin.y,
                origin.z,
                5,
                .09,
                .08,
                .09,
                .025);
    }

    private boolean clear(ServerLevel level, Vec3 origin, DefenseContact contact) {
        var hit =
                level.clip(
                        new ClipContext(
                                origin,
                                contact.point(),
                                ClipContext.Block.COLLIDER,
                                ClipContext.Fluid.NONE,
                                net.minecraft.world.phys.shapes.CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS
                || (tier() == DefenseTier.BASTION || tier() == DefenseTier.SKYGUARD)
                        && Airships.belongs(level, hit.getBlockPos(), contact.id());
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean packet) {
        super.write(tag, registries, packet);
        tag.putFloat("Bearing", aim);
        tag.putFloat("Elevation", pitch);
        tag.putLong("LastLaunch", firedAt);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean packet) {
        super.read(tag, registries, packet);
        aim = tag.getFloat("Bearing");
        pitch = tag.getFloat("Elevation");
        firedAt = tag.contains("LastLaunch") ? tag.getLong("LastLaunch") : -100;
    }
}
