package com.modserver.military;

import com.modserver.dispute.Dispute;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/** Positions in the contested chunk, measured along and across the direction of the assault. */
public final class BattleMotion {
    /** The second visual exchange reaches contact when the server rolls the duel. */
    private static final int EXCHANGE_TICKS = Dispute.DUEL_TICKS / 2;
    private static final int CANNON_TICK = EXCHANGE_TICKS * 16 / 25;
    private static final int STRAFE_TICK = EXCHANGE_TICKS * 4 / 5;
    private static final int SWING_TICK = EXCHANGE_TICKS * 22 / 25;
    public static final double CANNON_PHASE = (double) CANNON_TICK / EXCHANGE_TICKS;
    public static final double STRAFE_PHASE = (double) STRAFE_TICK / EXCHANGE_TICKS;

    public record Position(double depth, double lane, double height) {
        public double x(int chunkX, int dx, int dz) { return chunkX * 16 + 8.5 + dx * depth - dz * lane; }
        public double z(int chunkZ, int dx, int dz) { return chunkZ * 16 + 8.5 + dz * depth + dx * lane; }
    }

    /** Interpolate terrain under a low flight so crossing a block edge does not jerk the aircraft and its trails. */
    public static double surface(Level level, TroopBranch branch, int chunkX, int chunkZ, double x, double z) {
        var map = Heightmap.Types.MOTION_BLOCKING;
        if (branch != TroopBranch.AIR) return level.getHeight(map, Mth.floor(x), Mth.floor(z));
        int x0 = Mth.clamp(Mth.floor(x - .5), chunkX * 16, chunkX * 16 + 14);
        int z0 = Mth.clamp(Mth.floor(z - .5), chunkZ * 16, chunkZ * 16 + 14);
        return Mth.lerp2(Mth.clamp(x - .5 - x0, 0, 1), Mth.clamp(z - .5 - z0, 0, 1),
                level.getHeight(map, x0, z0), level.getHeight(map, x0+1, z0),
                level.getHeight(map, x0, z0+1), level.getHeight(map, x0+1, z0+1));
    }

    public static double phase(long now, long nextDuel, float partial) {
        return (Math.floorMod(now - nextDuel, EXCHANGE_TICKS) + (double) partial) / EXCHANGE_TICKS;
    }

    public static TroopBranch attackCue(long now, long nextDuel) {
        return switch (Math.floorMod(now - nextDuel, EXCHANGE_TICKS)) {
            case CANNON_TICK -> TroopBranch.NAVAL;
            case STRAFE_TICK -> TroopBranch.AIR;
            case SWING_TICK -> TroopBranch.GROUND;
            default -> null;
        };
    }

    public static Position position(TroopBranch branch, boolean attacking, int index, double phase) {
        int side = attacking ? -1 : 1;
        double lane = switch (index) { case 1 -> -2.8; case 2 -> 2.8; case 3 -> -5.4; default -> 0; };
        double angle = 2 * Math.PI * phase;
        return switch (branch) {
            case GROUND -> {
                // Close the last two blocks quickly, make contact, then fall back.
                double rush = Math.pow(.5 + .5 * Math.cos(angle - .08 * index), 4);
                double step = Math.sin(4 * angle + index);
                yield new Position(side * (2.8 - 2.05 * rush), lane + side * .18, .09 * Math.abs(step));
            }
            case AIR -> {
                double turn = angle + index * .36;
                yield new Position(side * (1.25 - 3.6 * Math.sin(turn)),
                        side * (1.7 + 1.7 * Math.sin(turn) - 1.5 * Math.cos(turn)) + lane * .78,
                        3.8 - 1.35 * Math.cos(turn) + (attacking ? 0 : .95) + .55 * index);
            }
            case NAVAL -> new Position(side * (4.85 + .50 * Math.cos(angle)),
                    side * 2.4 + lane * .74 + .42 * Math.sin(angle), .17 + .08 * Math.sin(2 * angle + index));
        };
    }

    private BattleMotion() {}
}
