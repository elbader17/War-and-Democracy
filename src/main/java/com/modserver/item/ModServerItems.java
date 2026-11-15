package com.modserver.item;

import com.modserver.ModServer;
import com.modserver.block.ModServerBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModServerItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ModServer.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ModServer.MOD_ID);

    public static final DeferredItem<BlockItem> COG = ITEMS.registerItem(
            "cog",
            properties -> new BlockItem(ModServerBlocks.COG.get(), properties),
            new Item.Properties());

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB =
            TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.modserver"))
                    .icon(() -> new ItemStack(COG.get()))
                    .displayItems((parameters, output) -> output.accept(COG.get()))
                    .build());

    private ModServerItems() {
    }
}
