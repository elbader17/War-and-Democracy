package com.modserver.client;

import com.modserver.ModServer;
import com.modserver.defense.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.*;

public final class DefenseRenderer implements BlockEntityRenderer<TurretEntity> {
    public static void install(IEventBus bus) {
        bus.addListener(
                (EntityRenderersEvent.RegisterRenderers event) -> {
                    event.registerBlockEntityRenderer(
                            DefenseHardware.TURRET.get(), ctx -> new DefenseRenderer());
                    event.registerEntityRenderer(DefenseHardware.ROUND.get(), RoundRenderer::new);
                });
        bus.addListener(
                (ModelEvent.RegisterAdditional event) -> {
                    for (var tier : DefenseTier.values()) {
                        event.register(part(tier.id() + "_mount"));
                        event.register(part(tier.id() + "_carriage"));
                        event.register(part(tier.id() + "_breech"));
                    }
                    event.register(part("drive_wheel"));
                });
    }

    private static ModelResourceLocation part(String name) {
        return ModelResourceLocation.standalone(ModServer.id("block/defense/" + name));
    }

    private static void draw(
            String name,
            TurretEntity tile,
            PoseStack pose,
            MultiBufferSource buffers,
            int light,
            int overlay) {
        var mc = Minecraft.getInstance();
        var model = mc.getModelManager().getModel(part(name));
        mc.getBlockRenderer()
                .getModelRenderer()
                .renderModel(
                        pose.last(),
                        buffers.getBuffer(Sheets.solidBlockSheet()),
                        tile.getBlockState(),
                        model,
                        1,
                        1,
                        1,
                        light,
                        overlay);
    }

    @Override
    public void render(
            TurretEntity tile,
            float partial,
            PoseStack pose,
            MultiBufferSource buffers,
            int light,
            int overlay) {
        if (tile.getLevel() == null) return;
        float dt = tile.getLevel().getGameTime() - tile.firedAt + partial;
        float recoil =
                dt < 0 || dt > 10
                        ? 0
                        : (float) Math.sin(Math.min(dt / 2, 1) * Math.PI / 2)
                                * Math.max(0, 1 - dt / 10);
        pose.pushPose();
        pose.translate(.5, 0, .5);
        pose.mulPose(
                Axis.YP.rotationDegrees(Mth.rotLerp(partial, tile.previousHeading, tile.heading)));
        pose.translate(-.5, 0, -.5);
        draw(tile.tier().id() + "_mount", tile, pose, buffers, light, overlay);
        pose.pushPose();
        pose.translate(0, .5, .5);
        pose.mulPose(Axis.XP.rotationDegrees(tile.wheelAngle + partial * tile.getSpeed() * .3f));
        pose.translate(0, -.5, -.5);
        draw("drive_wheel", tile, pose, buffers, light, overlay);
        pose.popPose();
        pose.translate(.5, .75, .5);
        pose.mulPose(
                Axis.XN.rotationDegrees(Mth.lerp(partial, tile.previousElevation, tile.elevation)));
        pose.translate(-.5, -.75, -.5 - recoil * .16);
        draw(tile.tier().id() + "_carriage", tile, pose, buffers, light, overlay);
        float phase = Mth.clamp((dt - 3) / (tile.tier().reload - 6f), 0, 1);
        float reload = (float) Math.sin(Math.PI * phase);
        pose.translate(0, tile.tier().battery() ? reload * .10 : 0, -reload * .19);
        draw(tile.tier().id() + "_breech", tile, pose, buffers, light, overlay);
        pose.popPose();
    }

    private static final class RoundRenderer extends EntityRenderer<DefenseRound> {
        RoundRenderer(EntityRendererProvider.Context ctx) {
            super(ctx);
        }

        @Override
        public ResourceLocation getTextureLocation(DefenseRound r) {
            return TextureAtlas.LOCATION_BLOCKS;
        }

        @Override
        public void render(
                DefenseRound round,
                float yaw,
                float partial,
                PoseStack pose,
                MultiBufferSource buffers,
                int light) {
            Item item =
                    switch (round.kind()) {
                        case SKYROCKET -> DefenseHardware.ROCKET.get();
                        case INTERCEPTOR -> DefenseHardware.INTERCEPTOR.get();
                        case FLARE -> DefenseHardware.FLARE.get();
                        default -> DefenseHardware.SLUG.get();
                    };
            pose.pushPose();
            pose.mulPose(
                    Axis.YP.rotationDegrees(Mth.rotLerp(partial, round.yRotO, round.getYRot())));
            pose.mulPose(Axis.XP.rotationDegrees(Mth.lerp(partial, round.xRotO, round.getXRot())));
            float scale =
                    round.kind() == DefenseRound.Kind.SKYROCKET
                            ? 1.6f
                            : round.kind() == DefenseRound.Kind.INTERCEPTOR ? 1.35f : .65f;
            pose.scale(scale, scale, scale);
            Minecraft.getInstance()
                    .getItemRenderer()
                    .renderStatic(
                            item.getDefaultInstance(),
                            ItemDisplayContext.NONE,
                            light,
                            OverlayTexture.NO_OVERLAY,
                            pose,
                            buffers,
                            round.level(),
                            round.getId());
            pose.popPose();
        }
    }
}
