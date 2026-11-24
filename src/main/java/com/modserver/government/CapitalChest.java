package com.modserver.government;

import com.modserver.ModServer;
import com.modserver.nation.Nation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import java.util.List;

import java.util.UUID;

/** A binding identifies a physical chest, not just a coordinate where someone could place another one. */
public final class CapitalChest {
    private static final String SEAL = "modserver.treasury";
    public record Access(Container inventory, UUID seal, String status) {
        static Access unavailable(String reason) { return new Access(null, null, reason); }
    }

    private static boolean owned(Nation nation, ServerLevel level, BlockPos pos) {
        var claim = ModServer.nations().claims().claimAt(level.dimension().location().toString(), pos.getX() >> 4, pos.getZ() >> 4);
        return claim != null && nation.id().equals(claim.nationId());
    }

    private static boolean sealed(ChestBlockEntity chest, Nation nation) {
        var tag = chest.getPersistentData();
        return tag.hasUUID(SEAL) && nation.id().equals(tag.getUUID(SEAL)) && tag.hasUUID(SEAL + ".id");
    }

    private static List<BlockPos> halves(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (!state.is(Blocks.CHEST)) return List.of();
        if (state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return List.of(pos);
        var other = pos.relative(ChestBlock.getConnectedDirection(state));
        var neighbor = level.getBlockState(other);
        if (!neighbor.is(Blocks.CHEST) || neighbor.getValue(ChestBlock.TYPE) != state.getValue(ChestBlock.TYPE).getOpposite()
                || neighbor.getValue(ChestBlock.FACING) != state.getValue(ChestBlock.FACING)) return List.of();
        return List.of(pos, other);
    }

    /** Visible seals refer only to a current binding with a real chest on owned land. */
    static List<BlockPos> visibleHalves(ServerLevel level, Nation nation, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest) || !sealed(chest, nation)) return List.of();
        var parts = halves(level, pos);
        return parts.stream().allMatch(part -> owned(nation, level, part)) ? parts : List.of();
    }

    private static Access inspect(ServerPlayer player, Nation nation, ServerLevel level, BlockPos pos, boolean bind) {
        var parts = halves(level, pos);
        if (parts.isEmpty()) return Access.unavailable(bind ? "look" : "missing");
        if (!owned(nation, level, pos)) return Access.unavailable("territory");
        for (var part : parts) {
            if (!owned(nation, level, part)) return Access.unavailable("border");
            if (!(level.getBlockEntity(part) instanceof ChestBlockEntity half)
                    || half.getLootTable() != null || !half.canOpen(player)) return Access.unavailable("locked");
        }
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(pos);
        String dimension = level.dimension().location().toString();
        if (bind && dimension.equals(nation.treasuryDimension()) && nation.treasuryChest() != null) {
            var anchor = BlockPos.of(nation.treasuryChest());
            if (parts.contains(anchor) && level.getBlockEntity(anchor) instanceof ChestBlockEntity existing && sealed(existing, nation)) {
                chest = existing;
                bind = false;
            }
        }
        if (!bind && !sealed(chest, nation)) return Access.unavailable("missing");
        Container inventory = ChestBlock.getContainer((ChestBlock) Blocks.CHEST, level.getBlockState(pos), level, pos, true);
        if (inventory == null || inventory.getContainerSize() != 27 * parts.size()) return Access.unavailable("missing");
        var tag = chest.getPersistentData();
        if (bind) {
            tag.putUUID(SEAL, nation.id());
            tag.putUUID(SEAL + ".id", UUID.randomUUID());
            chest.setChanged();
            nation.setTreasuryChest(dimension, pos.asLong());
            ModServer.nations().markDirty();
        }
        return new Access(inventory, tag.getUUID(SEAL + ".id"), "ready");
    }

    public static Access access(ServerPlayer player, Nation nation) {
        if (nation.treasuryChest() == null) return Access.unavailable("none");
        var level = player.server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(nation.treasuryDimension())));
        return level == null ? Access.unavailable("missing") : inspect(player, nation, level, BlockPos.of(nation.treasuryChest()), false);
    }

    public static Access bindLookedAt(ServerPlayer player, Nation nation) {
        if (player.pick(player.blockInteractionRange(), 1, false) instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK)
            return inspect(player, nation, player.serverLevel(), hit.getBlockPos(), true);
        return Access.unavailable("look");
    }

    public static int count(Container chest, Commodity commodity) {
        int count = 0;
        if (chest != null) for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            var stack = chest.getItem(slot);
            if (stack.is(commodity.item) && stack.isComponentsPatchEmpty()) count += stack.getCount();
        }
        return count;
    }

    public static boolean sell(Container chest, Nation nation, Commodity commodity, int quantity, long unitPrice) {
        if (quantity <= 0 || chest == null || count(chest, commodity) < quantity || commodity.price() != unitPrice) return false;
        long payment = Math.multiplyExact((long) quantity, unitPrice);
        if (nation.economy().balance() > Long.MAX_VALUE - payment) return false;
        int remaining = quantity;
        for (int slot = 0; remaining > 0 && slot < chest.getContainerSize(); slot++) {
            var stack = chest.getItem(slot);
            if (stack.is(commodity.item) && stack.isComponentsPatchEmpty())
                remaining -= chest.removeItem(slot, Math.min(stack.getCount(), remaining)).getCount();
        }
        chest.setChanged();
        nation.economy().setBalance(nation.economy().balance() + payment);
        return true;
    }
    private CapitalChest() {}
}
