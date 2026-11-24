package com.modserver.client;

import com.modserver.ModServer;
import com.modserver.network.MissileFlight;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(modid = ModServer.MOD_ID, value = Dist.CLIENT)
public final class MissileRenderer {
    private static final List<MissileFlight> flights = new ArrayList<>();
    private static ClientLevel scene;
    private static final int GOLD = 0xFFC75A, AMBER = 0xF78338, WHITE = 0xFFF2BD;
    private static final RenderType[] FIREBALL = new RenderType[16];
    private static final RenderType SMOKE = RenderType.entityTranslucent(ResourceLocation.withDefaultNamespace("textures/particle/big_smoke_4.png"));
    static {
        for (int i = 0; i < FIREBALL.length; i++)
            FIREBALL[i] = RenderType.entityTranslucentEmissive(ResourceLocation.withDefaultNamespace("textures/particle/explosion_" + i + ".png"));
    }

    public static void launch(MissileFlight flight, boolean focusLaunch) {
        var mc = Minecraft.getInstance();
        var level = mc.level;
        if (level == null || !flight.dimension().equals(level.dimension().location())) return;
        if (scene != level) flights.clear();
        scene = level;
        flights.add(flight);
        if (focusLaunch && mc.player != null) {
            Vec3 ignition = flight.position(0);
            // Front-facing third person looks back along the player's viewing direction.
            Vec3 target = mc.options.getCameraType().isMirrored()
                    ? mc.player.getEyePosition().scale(2).subtract(ignition) : ignition;
            mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
        }
    }

    @SubscribeEvent
    public static void leave(ClientPlayerNetworkEvent.LoggingOut event) {
        flights.clear();
        scene = null;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var level = Minecraft.getInstance().level;
        if (scene != level) {
            flights.clear();
            scene = null;
        } else if (!flights.isEmpty()) {
            flights.removeIf(shot -> level.getGameTime() - shot.started() >= shot.endTick());
        }
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || flights.isEmpty()) return;
        var mc = Minecraft.getInstance();
        if (scene == null || scene != mc.level) return;
        Vec3 eye = event.getCamera().getPosition();
        var pose = event.getPoseStack();
        var buffers = mc.renderBuffers().bufferSource();
        for (var shot : flights) {
            double age = scene.getGameTime() - shot.started() + (double)event.getPartialTick().getGameTimeDeltaPartialTick(false);
            Vec3 p = shot.position(age);
            if (age < 0 || age >= shot.endTick() || p.distanceToSqr(eye) > 128 * 128
                    || !scene.hasChunkAt(BlockPos.containing(p))) continue;
            if (age >= shot.impactTick()) {
                impact(pose, buffers, eye, p, (age - shot.impactTick()) / (shot.endTick() - shot.impactTick()), shot.nuclear());
                continue;
            }

            boolean flying = age >= MissileFlight.LIFTOFF;
            if (!flying) {
                double t = age / MissileFlight.LIFTOFF;
                ring(pose, buffers, eye, Vec3.atBottomCenterOf(shot.origin()).add(0, .14, 0),
                        2.2 - 1.6 * t, .065f, GOLD, 8, .67, t);
            } else {
                for (int i = 0; i < 10; i++) {
                    var a = shot.position(age - .7 * i);
                    var b = shot.position(age - .7 * (i + 1));
                    stroke(pose, buffers, eye, a, b, .24f - .022f * i, i < 4 ? WHITE : AMBER);
                }
            }
            Vec3 direction = flying ? shot.position(age + .1).subtract(shot.position(age - .1)) : new Vec3(0, 1, 0);
            pose.pushPose();
            pose.translate(p.x - eye.x, p.y - eye.y, p.z - eye.z);
            pose.mulPose(Axis.YP.rotation((float)Math.atan2(direction.x, direction.z)));
            pose.mulPose(Axis.XP.rotation((float)-Math.atan2(direction.y, direction.horizontalDistance())));
            pose.mulPose(Axis.ZP.rotationDegrees((float)(Math.max(0, age - MissileFlight.LIFTOFF) * 7)));
            float size = shot.nuclear() ? 2.65f : 1.65f;
            pose.scale(size, size, size);
            BattlefieldVehicles.missile(pose, buffers, shot.nuclear() ? 0xE6A12D : shot.color(), LevelRenderer.getLightColor(scene, BlockPos.containing(p)), age, flying);
            pose.popPose();
        }
    }

    private static void impact(PoseStack pose, MultiBufferSource buffers, Vec3 eye, Vec3 p, double t, boolean nuclear) {
        double scale = nuclear ? 3.6 : 1.72;
        double radius = (nuclear ? 32 : 13.5) * (1 - Math.pow(1 - t, 3));
        float width = (float)(.20 * scale * (1 - t));
        ring(pose, buffers, eye, p.add(0, -.36, 0), radius, width, t < .35 ? WHITE : GOLD, 32, 1 - .7 * t, .12);
        if (t > .14) ring(pose, buffers, eye, p, radius * .73, width * .65f, AMBER, 24, .62, .04);
        // A rising core and five rolling lobes share the vanilla explosion flipbook.
        for (int i = 0; i < 6; i++) {
            double life = (t - .024 * i) / .70;
            if (life < 0 || life >= 1) continue;
            double angle = i * 2.4;
            double spread = i == 0 ? 0 : 2.6 * scale * Math.sqrt(life);
            var center = p.add(Math.cos(angle) * spread, .35 + scale * life * (i == 0 ? 4.6 : 2.5), Math.sin(angle) * spread);
            float size = (float)(scale * (i == 0 ? 3.35 : 2.25) * Math.sin(Math.PI * Math.sqrt(life)));
            int tint = life < .20 ? WHITE : life < .5 ? 0xFFAC43 : 0xDC672C;
            int color = ((int)(240 * (1 - life)) << 24) | tint;
            billboard(pose, buffers, eye, center, size, angle, color, FIREBALL[(int)(life * FIREBALL.length)]);

        }
        if (nuclear && t > .10) {
            double rise = Math.sqrt((t - .10) / .90);
            double height = 27 * rise;
            int opacity = (int)(220 * Math.min(1, (t - .10) * 6) * (1 - Math.pow(t, 4)));
            int smoke = (opacity << 24) | (t < .36 ? 0xD99557 : 0x918780);
            for (int i = 0; i < 6; i++)
                billboard(pose, buffers, eye, p.add(Math.sin(i + t * 2) * rise, height * i / 6, 0),
                        (float)((2.8 + .25 * i) * rise), i, smoke, SMOKE);
            for (int i = 0; i < 12; i++) {
                double angle = i * Math.PI / 6 + t * .25;
                var cap = p.add(8.8 * rise * Math.cos(angle), height + rise * Math.sin(angle * 3),
                        8.8 * rise * Math.sin(angle));
                billboard(pose, buffers, eye, cap, (float)(6.2 * rise), angle, smoke, SMOKE);
            }
            billboard(pose, buffers, eye, p.add(0, height + 2.1 * rise, 0), (float)(8 * rise), t, smoke, SMOKE);
        }
        for (int i = 0; i < 14; i++) {
            double angle = i * 2.39996;
            double spread = scale * (2.8 + (i % 4) * 1.2);
            double height = scale * (8 + (i % 5) * 2.7);
            double tail = Math.max(0, t - .06 * (1 - t));
            Vec3 start = ember(p, angle, spread, height, tail);
            Vec3 end = ember(p, angle, spread, height, t);
            stroke(pose, buffers, eye, start, end, width * .7f, i % 3 == 0 ? WHITE : AMBER);
        }
    }

    private static void billboard(PoseStack pose, MultiBufferSource buffers, Vec3 eye, Vec3 center, float size,
                                  double angle, int color, RenderType sprite) {
        pose.pushPose();
        pose.translate(center.x - eye.x, center.y - eye.y, center.z - eye.z);
        pose.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        pose.mulPose(Axis.ZP.rotation((float)angle));
        var buffer = buffers.getBuffer(sprite);
        var matrix = pose.last();
        fireVertex(buffer, matrix, -size, -size, 0, 1, color);
        fireVertex(buffer, matrix, size, -size, 1, 1, color);
        fireVertex(buffer, matrix, size, size, 1, 0, color);
        fireVertex(buffer, matrix, -size, size, 0, 0, color);
        pose.popPose();
    }

    private static void fireVertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float u, float v, int color) {
        buffer.addVertex(pose, x, y, 0).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0, 0, 1);
    }

    private static Vec3 ember(Vec3 p, double angle, double spread, double height, double t) {
        return p.add(Math.cos(angle) * spread * t, height * t * (1 - t), Math.sin(angle) * spread * t);
    }

    private static void ring(PoseStack pose, MultiBufferSource buffers, Vec3 eye, Vec3 center, double radius,
                             float width, int color, int segments, double coverage, double rotation) {
        for (int i = 0; i < segments; i++) {
            double a = rotation + 2 * Math.PI * i / segments;
            double b = a + 2 * Math.PI * coverage / segments;
            stroke(pose, buffers, eye, center.add(radius * Math.cos(a), 0, radius * Math.sin(a)),
                    center.add(radius * Math.cos(b), 0, radius * Math.sin(b)), width, color);
        }
    }

    private static void stroke(PoseStack pose, MultiBufferSource buffers, Vec3 eye, Vec3 a, Vec3 b, float width, int color) {
        BattlefieldVehicles.stroke(pose, buffers, eye, a, b, width, color, LightTexture.FULL_BRIGHT);
    }

    /** Observers with the tactical board open see the same flight over the target territory. */
    public static void drawOnMap(GuiGraphics g, int cx, int cz, int midX, int midY, int cellSize) {
        if (flights.isEmpty() || scene == null || scene != Minecraft.getInstance().level) return;
        float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        for (var shot : flights) {
            double age = scene.getGameTime() - shot.started() + (double)partial;
            if (age < 0 || age >= shot.endTick()) continue;
            double x0 = midX + ((shot.origin().getX() >> 4) - cx) * cellSize;
            double z0 = midY + ((shot.origin().getZ() >> 4) - cz) * cellSize;
            double x1 = midX + ((shot.target().getX() >> 4) - cx) * cellSize;
            double z1 = midY + ((shot.target().getZ() >> 4) - cz) * cellSize;
            if (age < shot.impactTick()) {
                double t = shot.horizontalProgress(age);
                for (int i = 1; i <= 24 * t; i++) {
                    int x = (int)Math.round(Mth.lerp(i / 24., x0, x1)), z = (int)Math.round(Mth.lerp(i / 24., z0, z1));
                    g.fill(x - 1, z - 1, x + 1, z + 1, 0xED924526);
                }
                int x = (int)Math.round(Mth.lerp(t, x0, x1)), z = (int)Math.round(Mth.lerp(t, z0, z1));
                g.fill(x - 3, z - 3, x + 4, z + 4, 0xFF733D25);
                g.fill(x - 2, z - 1, x + 3, z + 2, 0xFFFFDC83);
                g.fill(x - 1, z - 2, x + 2, z + 3, 0xFFFFDC83);
            } else {
                double t = (age - shot.impactTick()) / (shot.endTick() - shot.impactTick());
                double radius = cellSize * (.13 + (shot.nuclear() ? 1.6 : .85) * t);
                int color = ((int)(255 * (1 - t)) << 24) | AMBER;
                for (int i = 0; i < 24; i++) {
                    int x = (int)Math.round(x1 + radius * Math.cos(i * Math.PI / 12));
                    int z = (int)Math.round(z1 + radius * Math.sin(i * Math.PI / 12));
                    g.fill(x - 1, z - 1, x + 2, z + 2, color);
                }
            }
        }
    }

    private MissileRenderer() {}
}
