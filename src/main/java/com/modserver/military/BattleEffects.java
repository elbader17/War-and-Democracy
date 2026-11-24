package com.modserver.military;

import com.modserver.dispute.Dispute;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/** Local particle and sound cues. Vanilla clients can follow the same fight. */
public final class BattleEffects {
    private static ServerLevel loadedLevel(MinecraftServer server, Dispute battle) {
        var key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(battle.dimension()));
        var level = server.getLevel(key);
        return level != null && level.getChunkSource().getChunkNow(battle.chunkX(), battle.chunkZ()) != null ? level : null;
    }

    private static Vec3 anchor(ServerLevel level, Dispute battle, TroopBranch branch, boolean attacking, double phase) {
        var force = battle.assault();
        int dx = force == null ? 1 : battle.chunkX() - force.originX();
        int dz = force == null ? 0 : battle.chunkZ() - force.originZ();
        var p = BattleMotion.position(branch, attacking, 0, phase);
        double x = p.x(battle.chunkX(), dx, dz), z = p.z(battle.chunkZ(), dx, dz);
        double ground = BattleMotion.surface(level, branch, battle.chunkX(), battle.chunkZ(), x, z);
        return ground <= level.getMinBuildHeight() ? null : new Vec3(x, ground + p.height(), z);
    }

    public static void maneuver(MinecraftServer server, Dispute battle, TroopCounts defenders, TroopBranch branch, double phase) {
        var force = battle.assault();
        if (force == null && branch != TroopBranch.GROUND) return;
        var level = loadedLevel(server, battle);
        if (level == null) return;
        if (force == null) {
            var p = anchor(level, battle, TroopBranch.GROUND, true, 0);
            if (p != null) burst(level, ParticleTypes.POOF, p, 3, .3, .03);
            return;
        }
        for (int side = 0; side < 2; side++) {
            boolean attacking = side == 0;
            if ((attacking ? force.forces() : defenders).count(branch) == 0) continue;
            var p = anchor(level, battle, branch, attacking, phase);
            if (p == null) continue;
            switch (branch) {
                case GROUND -> {
                    burst(level, ParticleTypes.POOF, p, 4, .22, .025);
                    sound(level, p, SoundEvents.PLAYER_ATTACK_SWEEP, .4f, .95f);
                }
                case AIR -> {
                    burst(level, ParticleTypes.CLOUD, p, 3, .16, .015);
                    sound(level, p, SoundEvents.BREEZE_SHOOT, .48f, 1.45f);
                }
                case NAVAL -> {
                    var muzzle = p.add((battle.chunkX() - force.originX()) * (attacking ? 1.3 : -1.3),
                            1, (battle.chunkZ() - force.originZ()) * (attacking ? 1.3 : -1.3));
                    burst(level, ParticleTypes.SPLASH, p, 4, .35, .03);
                    burst(level, ParticleTypes.FLAME, muzzle, 4, .12, .045);
                    burst(level, ParticleTypes.SMOKE, muzzle, 2, .12, .025);
                    sound(level, muzzle, SoundEvents.FIREWORK_ROCKET_BLAST, .55f, .78f);
                }
            }
        }
    }

    public static void impact(MinecraftServer server, Dispute battle, TroopBranch attacker, TroopBranch defender,
                              boolean attackerWon) {
        var level = loadedLevel(server, battle);
        if (level == null) return;
        var winner = attackerWon ? attacker : defender;
        var loser = attackerWon ? defender : attacker;
        var p = anchor(level, battle, loser, !attackerWon, 0);
        if (p == null) return;
        p = p.add(0, loser == TroopBranch.GROUND ? 1.05 : .4, 0);
        switch (winner) {
            case GROUND -> {
                burst(level, ParticleTypes.SWEEP_ATTACK, p, 2, .22, 0);
                burst(level, ParticleTypes.CRIT, p, 12, .26, .13);
                sound(level, p, SoundEvents.SHIELD_BLOCK, .85f, .95f);
            }
            case AIR -> {
                burst(level, ParticleTypes.ELECTRIC_SPARK, p, 16, .34, .14);
                burst(level, ParticleTypes.FLASH, p, 1, 0, 0);
                sound(level, p, SoundEvents.FIREWORK_ROCKET_BLAST, .85f, 1.55f);
            }
            case NAVAL -> {
                burst(level, ParticleTypes.EXPLOSION, p, 1, .12, 0);
                burst(level, ParticleTypes.SMOKE, p, 8, .4, .075);
                sound(level, p, SoundEvents.GENERIC_EXPLODE.value(), .8f, .88f);
            }
        }
        if (loser == TroopBranch.NAVAL) burst(level, ParticleTypes.SPLASH, p, 16, .5, .13);
        if (loser == TroopBranch.AIR) burst(level, ParticleTypes.LARGE_SMOKE, p, 6, .25, .055);
    }

    private static void burst(ServerLevel level, ParticleOptions particle, Vec3 p, int count, double spread, double speed) {
        level.sendParticles(particle, p.x, p.y, p.z, count, spread, spread * .55, spread, speed);
    }

    private static void sound(ServerLevel level, Vec3 p, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, BlockPos.containing(p), sound, SoundSource.PLAYERS, volume, pitch);
    }

    private BattleEffects() {}
}
