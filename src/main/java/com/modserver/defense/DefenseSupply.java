package com.modserver.defense;

import com.modserver.ModServer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

public final class DefenseSupply extends Item {
    private final boolean key;

    DefenseSupply(Properties props, boolean key) {
        super(props);
        this.key = key;
    }

    static void registerDispenser() {
        net.minecraft.world.level.block.DispenserBlock.registerBehavior(
                DefenseHardware.FLARE.get(),
                new net.minecraft.core.dispenser.DefaultDispenseItemBehavior() {
                    @Override
                    protected ItemStack execute(
                            net.minecraft.core.dispenser.BlockSource source, ItemStack stack) {
                        var direction =
                                source.state()
                                        .getValue(
                                                net.minecraft.world.level.block.DispenserBlock
                                                        .FACING);
                        var look =
                                net.minecraft.world.phys.Vec3.atLowerCornerOf(
                                        direction.getNormal());
                        var origin = source.center().add(look.scale(.7));
                        if (ModList.get().isLoaded("sable")) {
                            var vehicle = Airships.at(source.level(), source.pos());
                            if (vehicle != null) {
                                origin = vehicle.logicalPose().transformPosition(origin);
                                look = vehicle.logicalPose().transformNormal(look);
                            }
                        }
                        if (DefenseRound.flare(source.level(), origin, look)) stack.shrink(1);
                        return stack;
                    }
                });
    }

    @Override
    public InteractionResultHolder<ItemStack> use(
            Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (key) return InteractionResultHolder.pass(stack);
        if (level instanceof ServerLevel server
                && DefenseRound.flare(server, player.getEyePosition(), player.getLookAngle())) {
            stack.consume(1, player);
            player.getCooldowns().addCooldown(this, 12);
            server.playSound(
                    null,
                    player.blockPosition(),
                    net.minecraft.sounds.SoundEvents.FIREWORK_ROCKET_LAUNCH,
                    net.minecraft.sounds.SoundSource.PLAYERS,
                    .6f,
                    1.6f);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (!key) return InteractionResult.PASS;
        if (!(ctx.getLevel() instanceof ServerLevel level)
                || !(ctx.getPlayer() instanceof ServerPlayer player))
            return InteractionResult.SUCCESS;
        var nation = ModServer.nations().nations().ownedBy(player.getUUID()).orElse(null);
        var pos = ctx.getClickedPos();
        var stack = ctx.getItemInHand();
        String dim = DefenseRegister.dimension(level);
        String message;
        if (nation == null) message = "Found a nation before registering defenses.";
        else if (ModList.get().isLoaded("sable") && Airships.at(level, pos) != null) {
            var id = Airships.register(level, pos, nation.id());
            message =
                    id == null
                            ? "Register an unassigned vehicle over your own territory."
                            : "Registered vehicle • " + id;
        } else if (!nation.id().equals(DefenseRegister.land(dim, pos)))
            message = "Select equipment inside your territory.";
        else if (ModList.get().isLoaded("create_radar") && RadarFeed.source(level, pos)) {
            CustomData.update(
                    DataComponents.CUSTOM_DATA,
                    stack,
                    t -> {
                        t.putString("Dimension", dim);
                        t.putLong("Radar", pos.asLong());
                        t.putUUID("Nation", nation.id());
                    });
            message = "Radar selected. Use this key on a defense installation.";
        } else if (level.getBlockEntity(pos) instanceof TurretEntity turret) {
            var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            var source = BlockPos.of(tag.getLong("Radar"));
            if (!dim.equals(tag.getString("Dimension"))
                    || !tag.hasUUID("Nation")
                    || !nation.id().equals(tag.getUUID("Nation"))
                    || !nation.id().equals(DefenseRegister.land(dim, source))
                    || !ModList.get().isLoaded("create_radar")
                    || !RadarFeed.source(level, source))
                message = "Use the survey key on a radar in your territory first.";
            else {
                var book = DefenseRegister.of(level);
                var station = book.station(level, pos, turret.tier());
                station.link = source;
                station.radar = true;
                book.setDirty();
                message = "Radar linked to " + turret.tier().title + ".";
            }
        } else message = "Use the key on a radar, defense installation or assembled vehicle.";
        player.displayClientMessage(Component.literal(message), false);
        return InteractionResult.SUCCESS;
    }
}
