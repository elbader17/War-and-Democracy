package com.modserver.block;

import com.modserver.ModServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModServerBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ModServer.MOD_ID);

    /** Example block: it serves as an anchor to test the mod inside the game. */
    public static final DeferredBlock<Block> COG = BLOCKS.registerBlock(
            "cog",
            Block::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(3.0F)
                    .sound(SoundType.COPPER)
                    .requiresCorrectToolForDrops());

    private ModServerBlocks() {
    }
}
