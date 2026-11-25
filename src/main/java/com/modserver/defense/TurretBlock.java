package com.modserver.defense;

import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;

public final class TurretBlock extends KineticBlock implements IBE<TurretEntity> {
    public final DefenseTier tier;

    TurretBlock(DefenseTier tier, Properties properties) {
        super(properties);
        this.tier = tier;
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return Direction.Axis.Y;
    }

    @Override
    public boolean hasShaftTowards(
            LevelReader level, BlockPos pos, BlockState state, Direction face) {
        return face == Direction.DOWN;
    }

    @Override
    public Class<TurretEntity> getBlockEntityClass() {
        return TurretEntity.class;
    }

    @Override
    public BlockEntityType<TurretEntity> getBlockEntityType() {
        return DefenseHardware.TURRET.get();
    }

    @Override
    protected VoxelShape getShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return box(1, 0, 1, 15, 14, 15);
    }

    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(
            net.minecraft.world.item.ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            net.minecraft.world.InteractionHand hand,
            BlockHitResult hit) {
        if (stack.is(DefenseHardware.KEY.get()))
            return net.minecraft.world.ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel server
                && level.getBlockEntity(pos) instanceof TurretEntity turret) {
            var station = DefenseRegister.of(server).station(server, pos, tier);
            player.displayClientMessage(
                    Component.literal(
                            tier.title
                                    + " • "
                                    + turret.status(server, station)
                                    + " • "
                                    + Math.round(Math.abs(turret.getSpeed()))
                                    + "/"
                                    + tier.rpm
                                    + " RPM"),
                    true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void onRemove(
            BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock()) && level instanceof ServerLevel server)
            DefenseRegister.of(server).remove(server, pos);
        super.onRemove(state, level, pos, next, moving);
    }
}
