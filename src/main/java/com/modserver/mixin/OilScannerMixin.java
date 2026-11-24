package com.modserver.mixin;

import com.jesz.createdieselgenerators.content.tools.OilScannerItem;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.modserver.petroleum.Petroleum;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Observe the completed native survey in the exact tick and chunk that produced the reading. */
@Mixin(value = OilScannerItem.class, remap = false)
abstract class OilScannerMixin {
    @ModifyExpressionValue(method = "inventoryTick", at = @At(value = "INVOKE",
            target = "Lcom/jesz/createdieselgenerators/world/OilChunksSavedData;getChunkOilAmount(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;)I"))
    private int modserver$onOilReading(int amount, ItemStack scanner, Level level, Entity holder, int slot, boolean selected) {
        if (holder instanceof ServerPlayer player) Petroleum.onScannerReading(player, amount);
        return amount;
    }
}
