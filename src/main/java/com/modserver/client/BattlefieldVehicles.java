package com.modserver.client;

import com.modserver.military.BattleMotion;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Painted voxel miniatures. Each rigid group is baked once; the propeller and gun move independently. */
final class BattlefieldVehicles {
    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/block/white_concrete.png");
    private static final RenderType MATERIAL = RenderType.entityCutoutNoCull(TEXTURE);
    private static final RenderType WATER = RenderType.entityTranslucent(TEXTURE);
    private static final int STEEL = 0xFF3D4F59, BRASS = 0xFFF7B650, IVORY = 0xFFFFEBD0,
            WOOD = 0xFFA8744B, GLASS = 0xFF86C7D2, IRON = 0xFF26333D;
    private static final ModelPart AIRFRAME = bake(boxes()
            .addBox(-3, -2, -10, 6, 5, 19).addBox(-2, -1, -17, 4, 3, 7)
            .addBox(-18, 5, -4, 36, 1.5f, 8).addBox(-16, -2, -3, 32, 1.5f, 7)
            .addBox(-6, 1, -16, 12, 1, 5).addBox(-.75f, 2, -16, 1.5f, 4, 4));
    private static final ModelPart AIR_TRIM = bake(boxes().addBox(-15, 6.52f, -4, 2, .3f, 8)
            .addBox(13, 6.52f, -4, 2, .3f, 8).addBox(-3.15f, -2, 7, 6.3f, 5.2f, 2));
    private static final ModelPart AIR_RIG = bake(boxes().addBox(-13, -.6f, -2, 1, 6, 1)
            .addBox(12, -.6f, -2, 1, 6, 1).addBox(-13, -.6f, 2, 1, 6, 1).addBox(12, -.6f, 2, 1, 6, 1)
            .addBox(-2.5f, -1.8f, 9, 5, 4.5f, 2).addBox(-4, -3.5f, 1, 8, 1, 1)
            .addBox(-5, -5, 0, 2, 3.5f, 3).addBox(3, -5, 0, 2, 3.5f, 3));
    private static final ModelPart COCKPIT = bake(boxes().addBox(-2, 3, -4, 4, 2, 4));
    private static final ModelPart PROP = bake(boxes().addBox(-.75f, -7, 0, 1.5f, 14, 1)
            .addBox(-1.5f, -1.5f, -.2f, 3, 3, 2));
    private static final ModelPart HULL = bake(boxes().addBox(-5.5f, 0, -15, 11, 3, 23)
            .addBox(-6.5f, 3, -16, 13, 4, 25).addBox(-5, 2, 9, 10, 5, 4)
            .addBox(-3.5f, 3, 13, 7, 4, 3).addBox(-1.5f, 4, 16, 3, 3, 3));
    private static final ModelPart DECK = bake(boxes().addBox(-5.6f, 7, -15, 11.2f, .8f, 23)
            .addBox(-4.5f, 7, 8, 9, .8f, 4).addBox(-3, 7, 12, 6, .8f, 4));
    private static final ModelPart BRIDGE = bake(boxes().addBox(-3.6f, 7.8f, -8, 7.2f, 4, 8)
            .addBox(-4, 13.7f, -8.6f, 8, 1, 8.6f).addBox(-.6f, 13.8f, -5, 1.2f, 7, 1.2f));
    private static final ModelPart CABIN = bake(boxes().addBox(-3.4f, 11.8f, -7.8f, 6.8f, 2, 7.4f));
    private static final ModelPart SHIP_TRIM = bake(boxes().addBox(-6.55f, 4, -15, .35f, 1, 23)
            .addBox(6.2f, 4, -15, .35f, 1, 23).addBox(-1.8f, 7.8f, -13, 3.6f, 6, 3));
    private static final ModelPart PENNANT = bake(boxes().addBox(.45f, 17.5f, -5, 5, 3.2f, .6f));
    private static final ModelPart GUN = bake(boxes().addBox(-2.6f, 0, -2.5f, 5.2f, 2.8f, 5));
    private static final ModelPart BARREL = bake(boxes().addBox(-.8f, 1, 1, 1.6f, 1.6f, 9));
    private static final ModelPart STREAK = bake(boxes().addBox(-8, -8, 0, 16, 16, 16));
    private static final ModelPart ROCKET_SHELL = bake(boxes()
            .addBox(-4, -4, -12, 8, 8, 24).addBox(-3, -3, 12, 6, 6, 4));
    private static final ModelPart ROCKET_PAINT = bake(boxes()
            .addBox(-4.2f, -4.2f, 6, 8.4f, 8.4f, 3).addBox(-2, -2, 16, 4, 4, 4)
            .addBox(-1, -1, 20, 2, 2, 3).addBox(-9, -.8f, -13, 18, 1.6f, 5)
            .addBox(-.8f, -9, -13, 1.6f, 18, 5));
    private static final ModelPart ROCKET_COLLAR = bake(boxes()
            .addBox(-4.3f, -4.3f, -11, 8.6f, 8.6f, 3).addBox(-3, -3, -15, 6, 6, 3));
    private static final ModelPart EXHAUST = bake(boxes().addBox(-2.8f, -2.8f, -8, 5.6f, 5.6f, 8)
            .addBox(-1.6f, -1.6f, -14, 3.2f, 3.2f, 6).addBox(-.8f, -.8f, -19, 1.6f, 1.6f, 5));

    private static CubeListBuilder boxes() { return CubeListBuilder.create().texOffs(0, 0); }

    private static ModelPart bake(CubeListBuilder boxes) {
        var mesh = new MeshDefinition();
        mesh.getRoot().addOrReplaceChild("part", boxes, PartPose.ZERO);
        return LayerDefinition.create(mesh, 64, 64).bakeRoot().getChild("part");
    }

    private static void paint(ModelPart mesh, PoseStack pose, VertexConsumer buffer, int light, int color) {
        mesh.render(pose, buffer, light, OverlayTexture.NO_OVERLAY, color | 0xFF000000);
    }

    static void plane(PoseStack pose, MultiBufferSource buffers, int color, int light, double phase) {
        var buffer = buffers.getBuffer(MATERIAL);
        paint(AIRFRAME, pose, buffer, light, color);
        paint(AIR_TRIM, pose, buffer, light, IVORY);
        paint(AIR_RIG, pose, buffer, light, IRON);
        paint(COCKPIT, pose, buffer, light, GLASS);
        pose.pushPose();
        pose.translate(0, .035, .70);
        pose.mulPose(Axis.ZP.rotationDegrees((float)(phase * 360 * 13)));
        paint(PROP, pose, buffer, light, BRASS);
        pose.popPose();
    }

    static void missile(PoseStack pose, MultiBufferSource buffers, int color, int light, double age, boolean flying) {
        var buffer = buffers.getBuffer(MATERIAL);
        paint(ROCKET_SHELL, pose, buffer, light, IVORY);
        paint(ROCKET_PAINT, pose, buffer, light, color);
        paint(ROCKET_COLLAR, pose, buffer, light, STEEL);
        if (flying) {
            pose.pushPose();
            pose.translate(0, 0, -15 / 16f);
            pose.scale(1, 1, (float)(1.1 + .28 * Math.sin(age * 2)));
            paint(EXHAUST, pose, buffer, LightTexture.FULL_BRIGHT, 0xFFC54C);
            pose.popPose();
        }
    }

    static void ship(PoseStack pose, MultiBufferSource buffers, int color, int light, double phase, float turretYaw) {
        var buffer = buffers.getBuffer(MATERIAL);
        paint(HULL, pose, buffer, light, color);
        paint(DECK, pose, buffer, light, WOOD);
        paint(BRIDGE, pose, buffer, light, IVORY);
        paint(CABIN, pose, buffer, light, GLASS);
        paint(SHIP_TRIM, pose, buffer, light, STEEL);
        paint(PENNANT, pose, buffer, light, color);
        pose.pushPose();
        pose.translate(0, .49, .32);
        pose.mulPose(Axis.YP.rotationDegrees(turretYaw));
        paint(GUN, pose, buffer, light, STEEL);
        double recoil = Math.max(0, 1 - Math.abs(phase - (BattleMotion.CANNON_PHASE + .02)) / .07);
        pose.translate(0, 0, -.16 * recoil);
        pose.mulPose(Axis.XP.rotationDegrees(-14));
        paint(BARREL, pose, buffer, light, IRON);
        pose.popPose();
    }

    /** A shallow, feathered bow wave also gives the fleet a waterline on inland battlefields. */
    static void wake(PoseStack pose, MultiBufferSource buffers, int light, double phase) {
        var buffer = buffers.getBuffer(WATER);
        var matrix = pose.last();
        int segments = 16;
        double t = phase * 2 * Math.PI;
        for (int i = 0; i < segments; i++) {
            double a = 2 * Math.PI * i / segments, b = 2 * Math.PI * (i + 1) / segments;
            float ax = (float)(Math.sin(a) * (.99 + .065 * Math.sin(t * 2 + a * 3)));
            float bx = (float)(Math.sin(b) * (.99 + .065 * Math.sin(t * 2 + b * 3)));
            float az = (float)(1.98 * Math.cos(a)), bz = (float)(1.98 * Math.cos(b));
            // Fade out at the edge, and leave broken foam where the hull pushes water aside.
            int foam = Math.sin(t * 2 - a * 3) > .05 ? 0xB2C9EFED : 0x824AABB8;
            for (int band = 0; band < 3; band++) {
                float inner = switch (band) { case 0 -> 0; case 1 -> .74f; default -> .83f; };
                float outer = switch (band) { case 0 -> .74f; case 1 -> .83f; default -> 1.06f; };
                int inColor = band == 0 ? 0xBF266C89 : band == 1 ? 0xA04496AC : foam;
                int outColor = band == 0 ? 0xA04496AC : band == 1 ? foam : 0x005CB4BB;
                waterVertex(buffer, matrix, ax * inner, az * inner, inColor, light);
                waterVertex(buffer, matrix, ax * outer, az * outer, outColor, light);
                waterVertex(buffer, matrix, bx * outer, bz * outer, outColor, light);
                waterVertex(buffer, matrix, bx * inner, bz * inner, inColor, light);
            }
        }
    }

    private static void waterVertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float z, int color, int light) {
        buffer.addVertex(pose, x, -.045f, z - .2f).setColor(color).setUv(.5f, .5f)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0, 1, 0);
    }

    /** A short world-space stroke, used for shell paths, wingtip vapor. */
    static void stroke(PoseStack pose, MultiBufferSource buffers, Vec3 eye, Vec3 from, Vec3 to,
                       float width, int color, int light) {
        Vec3 v = to.subtract(from);
        double length = v.length();
        if (length < .001) return;
        pose.pushPose();
        pose.translate(from.x - eye.x, from.y - eye.y, from.z - eye.z);
        pose.mulPose(Axis.YP.rotation((float)Math.atan2(v.x, v.z)));
        pose.mulPose(Axis.XP.rotation((float)-Math.asin(v.y / length)));
        pose.scale(width, width, (float)length);
        paint(STREAK, pose, buffers.getBuffer(MATERIAL), light, color);
        pose.popPose();
    }

    static void tracer(PoseStack pose, MultiBufferSource buffers, Vec3 eye, Vec3 from, Vec3 to, float width) {
        stroke(pose, buffers, eye, from, to, width, 0xFFFC81, LightTexture.FULL_BRIGHT);
    }

    private BattlefieldVehicles() {}
}
