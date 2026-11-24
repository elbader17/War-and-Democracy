package com.modserver.military;

import com.modserver.network.MissileFlight;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.ArrayList;
import java.util.List;

/** Per-server visual timeline. All sounds and particles are cosmetic, including the arrival burst. */
public final class MissileEffects {
    private final List<MissileFlight> active = new ArrayList<>();

    public void launch(ServerPlayer player, int sx, int sz, int tx, int tz, int color, boolean nuclear) {
        var level = player.serverLevel();
        var a = level.getChunkSource().getChunkNow(sx, sz);
        var b = level.getChunkSource().getChunkNow(tx, tz);
        int fallback = level.getSeaLevel();
        int y1 = a == null ? fallback : a.getHeight(Heightmap.Types.MOTION_BLOCKING, 8, 8) + 1;
        int y2 = b == null ? y1 : b.getHeight(Heightmap.Types.MOTION_BLOCKING, 8, 8) + 1;
        var shot = new MissileFlight(level.dimension().location(),
                new BlockPos(sx * 16 + 8, a == null ? y2 : y1, sz * 16 + 8),
                new BlockPos(tx * 16 + 8, y2, tz * 16 + 8), color, level.getGameTime(), nuclear);
        var middle = shot.position(MissileFlight.LIFTOFF).lerp(shot.position(shot.impactTick()), .5);
        if (NetworkRegistry.hasChannel(player.connection, MissileFlight.Launch.TYPE.id())) {
            PacketDistributor.sendToPlayer(player, new MissileFlight.Launch(shot));
        } else {
            player.lookAt(EntityAnchorArgument.Anchor.EYES, shot.position(0));
            if (NetworkRegistry.hasChannel(player.connection, MissileFlight.TYPE.id()))
                PacketDistributor.sendToPlayer(player, shot);
        }
        for (var viewer : level.players()) {
            if (viewer != player && viewer.position().distanceToSqr(middle) < 128 * 128
                    && NetworkRegistry.hasChannel(viewer.connection, MissileFlight.TYPE.id()))
                PacketDistributor.sendToPlayer(viewer, shot);
        }
        if (a == null && b == null) return; // A remote map launch never needs to load terrain.
        active.add(shot);
        if (a != null) sound(level, Vec3.atCenterOf(shot.origin()), SoundEvents.RESPAWN_ANCHOR_CHARGE, .82f, 1.35f);
    }

    public void tick(MinecraftServer server) {
        for (var it = active.iterator(); it.hasNext();) {
            var shot = it.next();
            var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, shot.dimension()));
            if (level == null || level.getGameTime() - shot.started() >= shot.endTick()) {
                it.remove();
                continue;
            }
            int age = (int)(level.getGameTime() - shot.started());
            var p = shot.position(age);
            var block = BlockPos.containing(p);
            if (level.getChunkSource().getChunkNow(block.getX() >> 4, block.getZ() >> 4) == null) continue;
            if (age == MissileFlight.LIFTOFF) {
                burst(level, ParticleTypes.CLOUD, p.add(0, -1, 0), 20, .8, .15, .12);
                burst(level, ParticleTypes.FLAME, p.add(0, -.85, 0), 12, .4, .18, .09);
                sound(level, p, SoundEvents.FIREWORK_ROCKET_LAUNCH, 1.3f, .65f);
            }
            if (age >= MissileFlight.LIFTOFF && age < shot.impactTick() && age % 2 == 0) {
                burst(level, ParticleTypes.FLAME, p, 4, .14, .12, .035);
                burst(level, ParticleTypes.WHITE_SMOKE, p, 4, .19, .16, .035);
                if (age % 6 == 0) burst(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, p, 1, .12, .10, .012);
            }
            boolean nuclear = shot.nuclear();
            if (age == shot.impactTick()) {
                burst(level, ParticleTypes.EXPLOSION, p.add(0, .7, 0), nuclear ? 18 : 9, nuclear ? 4 : 1.8, nuclear ? 3 : 1.2, .06);
                burst(level, ParticleTypes.FLAME, p.add(0, .3, 0), nuclear ? 90 : 56, nuclear ? 4 : 1.8, nuclear ? 2 : 1, .30);
                burst(level, ParticleTypes.FIREWORK, p, 40, .65, .45, .30);
                sound(level, p, SoundEvents.GENERIC_EXPLODE.value(), nuclear ? 3.4f : 2.1f, nuclear ? .53f : .68f);
            }
            if (age == shot.impactTick() + 5) {
                burst(level, ParticleTypes.GUST, p.add(0, .6, 0), 2, .6, .2, 0);
                burst(level, ParticleTypes.LARGE_SMOKE, p.add(0, 1.5, 0), nuclear ? 44 : 30, nuclear ? 4 : 1.6, nuclear ? 3 : 1.1, .13);
                burst(level, ParticleTypes.LAVA, p.add(0, 1.2, 0), 8, .85, .4, .08);
                sound(level, p, SoundEvents.WARDEN_SONIC_BOOM, nuclear ? 2.5f : 1f, nuclear ? .60f : .83f);
            }
            int after = age - shot.impactTick();
            if (nuclear && after >= 10 && after <= 50 && after % 10 == 0) {
                double rise = after * .46;
                burst(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, p.add(0, rise * .5, 0), 12, 1.7, rise * .25, .04);
                for (int i = 0; i < 8; i++) {
                    double angle = i * Math.PI / 4 + after * .016;
                    Vec3 rim = p.add(Math.cos(angle) * rise * .45, rise, Math.sin(angle) * rise * .45);
                    burst(level, ParticleTypes.LARGE_SMOKE, rim, 9, 2.4, 1.2, .09);
                }
                if (after == 20) sound(level, p, SoundEvents.GENERIC_EXPLODE.value(), 2f, .58f);
            }
        }
    }

    private static void burst(ServerLevel level, ParticleOptions kind, Vec3 p, int count, double spread,
                              double height, double speed) {
        level.sendParticles(kind, p.x, p.y, p.z, count, spread, height, spread, speed);
    }

    private static void sound(ServerLevel level, Vec3 p, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, p.x, p.y, p.z, sound, SoundSource.PLAYERS, volume, pitch);
    }
}
