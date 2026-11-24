package com.modserver.client;

import com.modserver.ModServer;
import com.modserver.network.ReserveChests;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.client.resources.model.Material;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = ModServer.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ReserveChestRenderer extends ChestRenderer<ChestBlockEntity> {
    private final Material single = material("reserve");
    private final Material left = material("reserve_left");
    private final Material right = material("reserve_right");
    private static ReserveChests view;

    private ReserveChestRenderer(BlockEntityRendererProvider.Context context) { super(context); }

    @SubscribeEvent
    public static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(BlockEntityType.CHEST, ReserveChestRenderer::new);
    }

    public static void show(ReserveChests snapshot) { view = snapshot; }

    private static Material material(String name) { return new Material(Sheets.CHEST_SHEET, ModServer.id("entity/chest/" + name)); }

    @Override protected Material getMaterial(ChestBlockEntity chest, ChestType type) {
        var level = chest.getLevel();
        if (view == null || level == null || !level.dimension().location().equals(view.dimension())
                || !view.positions().contains(chest.getBlockPos())) return super.getMaterial(chest, type);
        return switch (type) { case SINGLE -> single; case LEFT -> left; case RIGHT -> right; };
    }
}
