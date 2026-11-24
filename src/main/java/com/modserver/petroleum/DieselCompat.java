package com.modserver.petroleum;

import com.jesz.createdieselgenerators.CDGConfig;
import com.jesz.createdieselgenerators.world.OilChunksSavedData;
import com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Only loaded on a server with both mods present. The addon's own data includes depletion and datapack overrides. */
final class DieselCompat {
    static int survey(ServerLevel level, ChunkPos chunk) { return OilChunksSavedData.getChunkOilAmount(level, chunk); }
    static int scannerCeiling() { return CDGConfig.MAX_OIL_SCANNER_LEVEL.get(); }

    static BlockPos tankBase(ServerLevel level, BlockPos pos) {
        var part = level.getBlockEntity(pos);
        if (!(part instanceof IMultiBlockEntityContainer vessel)) return null;
        String type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(part.getType()).toString();
        if (!type.equals("create:fluid_tank") && !type.equals("createdieselgenerators:oil_barrel_block_entity")) return null;
        BlockPos base = vessel.getController();
        if (base == null || !new ChunkPos(pos).equals(new ChunkPos(base))) return null;
        BlockEntity controller = level.getBlockEntity(base);
        if (controller == null || controller.getType() != part.getType()
                || !(controller instanceof IMultiBlockEntityContainer tank) || !tank.isController()) return null;
        int w = tank.getWidth(), length = tank.getHeight();
        if (w < 1 || length < 1) return null;
        var axis = tank.getMainConnectionAxis();
        int dx = axis == Direction.Axis.X ? length : w, dz = axis == Direction.Axis.Z ? length : w;
        return (base.getX() & 15) + (long)dx <= 16 && (base.getZ() & 15) + (long)dz <= 16 ? base : null;
    }

    private DieselCompat() {}
}
