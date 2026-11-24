package com.modserver.client;

import com.modserver.ModServer;
import com.modserver.client.map.NationMapData;
import com.modserver.military.BattleMotion;
import com.modserver.military.TroopBranch;
import com.modserver.military.TroopFormation;
import com.modserver.network.DisputeMapPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Rotations;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.EnumMap;

/** Formations and their projectiles exist only in the world renderer. */
@EventBusSubscriber(modid = ModServer.MOD_ID, value = Dist.CLIENT)
public final class BattlefieldRenderer {
    private static final class Soldier extends ArmorStand {
        private final TroopFormation tier;
        private int uniform = -1, lastPieces = -1;

        Soldier(ClientLevel level, TroopFormation tier) {
            super(level, 0, 0, 0);
            this.tier = tier;
            setSilent(true);
            setShowArms(true);
            setNoBasePlate(true);
            var helmet = switch (tier) {
                case SQUAD -> Items.CHAINMAIL_HELMET;
                case BATTALION -> Items.GOLDEN_HELMET;
                default -> Items.IRON_HELMET;
            };
            setItemSlot(EquipmentSlot.HEAD, helmet.getDefaultInstance());
            setItemSlot(EquipmentSlot.LEGS, Items.CHAINMAIL_LEGGINGS.getDefaultInstance());
            setItemSlot(EquipmentSlot.FEET, Items.IRON_BOOTS.getDefaultInstance());
            setItemSlot(EquipmentSlot.MAINHAND, Items.IRON_SWORD.getDefaultInstance());
            setItemSlot(EquipmentSlot.OFFHAND, Items.SHIELD.getDefaultInstance());
        }

        void displayPieces(int pieces) {
            if (lastPieces == pieces) return;
            setCustomName(pieces == 0 ? null : formationName(tier, TroopBranch.GROUND, pieces));
            setCustomNameVisible(pieces > 0);
            lastPieces = pieces;
        }

        void dress(int color) {
            if (color == uniform) return;
            var tunic = Items.LEATHER_CHESTPLATE.getDefaultInstance();
            tunic.set(DataComponents.DYED_COLOR, new DyedItemColor(color, false));
            setItemSlot(EquipmentSlot.CHEST, tunic);
            if (tier == TroopFormation.COMPANY || tier == TroopFormation.BATTALION) {
                DyeColor closest = DyeColor.WHITE;
                int best = Integer.MAX_VALUE;
                for (var dye : DyeColor.values()) {
                    int rgb = dye.getTextureDiffuseColor();
                    int r = (color >> 16 & 255) - (rgb >> 16 & 255);
                    int g = (color >> 8 & 255) - (rgb >> 8 & 255);
                    int b = (color & 255) - (rgb & 255);
                    int distance = r * r + g * g + b * b;
                    if (distance < best) { best = distance; closest = dye; }
                }
                setItemSlot(EquipmentSlot.OFFHAND, BannerBlock.byColor(closest).asItem().getDefaultInstance());
            }
            uniform = color;
        }
    }

    private static final EnumMap<TroopFormation, Soldier> attackers = new EnumMap<>(TroopFormation.class);
    private static final EnumMap<TroopFormation, Soldier> defenders = new EnumMap<>(TroopFormation.class);
    private static ClientLevel scene;

    private static void clear() {
        attackers.clear();
        defenders.clear();
        scene = null;
    }

    @SubscribeEvent
    public static void leave(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        var mc = Minecraft.getInstance();
        var level = mc.level;
        if (scene != level) { clear(); scene = level; }
        if (level == null || !level.dimension().equals(NationMapData.dimension())) return;
        var eye = event.getCamera().getPosition();
        for (var battle : NationMapData.battles()) {
            double x = battle.x() * 16 + 8.5, z = battle.z() * 16 + 8.5;
            if (Math.abs(x - eye.x) > 80 || Math.abs(z - eye.z) > 80
                    || level.getChunkSource().getChunkNow(battle.x(), battle.z()) == null) continue;
            float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            var field = new Field(level, battle, event.getPoseStack(), mc.renderBuffers().bufferSource(), eye,
                    partial, BattleMotion.phase(level.getGameTime(), battle.nextDuel(), partial));
            field.formation(true);
            field.formation(false);
        }
    }

    private static Component formationName(TroopFormation tier, TroopBranch branch, int pieces) {
        return Component.translatable("tac.formation.stack", Component.translatable(tier.key(branch)), pieces);
    }

    private static int color(String name, int fallback) {
        for (var country : NationMapData.nations()) if (country.name().equals(name)) return country.color() & 0xFFFFFF;
        return fallback;
    }

    private record Field(ClientLevel level, DisputeMapPayload.Battle battle, PoseStack pose, MultiBufferSource buffers,
                         Vec3 eye, float partial, double phase) {
        private Vec3 point(TroopBranch branch, boolean attacking, int index, double time) {
            var p = BattleMotion.position(branch, attacking, index, time);
            double x = p.x(battle.x(), battle.dx(), battle.dz()), z = p.z(battle.z(), battle.dx(), battle.dz());
            double floor = BattleMotion.surface(level, branch, battle.x(), battle.z(), x, z);
            return new Vec3(x, floor <= level.getMinBuildHeight() ? floor : floor + p.height(), z);
        }

        private void formation(boolean attacking) {
            var force = attacking ? battle.attacking() : battle.defending();
            int paint = attacking ? color(battle.challenger(), 0xA64A36) : color(battle.defender(), 0x477E84);
            for (var arm : TroopBranch.ALL) {
                int troops = force.count(arm);
                if (troops == 0) continue;
                boolean grouped = troops >= TroopFormation.SQUAD.value;
                boolean showLabels = grouped && !Minecraft.getInstance().options.hideGui;
                int index = 0;
                for (var tier : TroopFormation.LARGEST_FIRST) {
                    int pieces = tier.pieces(troops);
                    if (pieces == 0) continue;
                    int figures = grouped ? 1 : Math.min(3, troops);
                    for (int i = 0; i < figures; i++, index++) {
                        Vec3 pos = point(arm, attacking, index, phase);
                        if (pos.y <= level.getMinBuildHeight()
                                || Math.abs(pos.y - eye.y) > 80) continue;
                        int light = LevelRenderer.getLightColor(level, BlockPos.containing(pos.x, pos.y + .6, pos.z));
                        pose.pushPose();
                        pose.translate(pos.x - eye.x, pos.y - eye.y, pos.z - eye.z);
                        int facing = attacking ? 1 : -1;
                        float yaw = yaw(battle.dx() * facing, battle.dz() * facing);
                        if (arm == TroopBranch.GROUND) {
                            infantry(attacking, tier, showLabels ? pieces : 0, index, paint, pos, yaw, light);
                        } else {
                            vehicle(arm, attacking, index, paint, yaw, light);
                            if (showLabels && pos.distanceToSqr(eye) < 32 * 32)
                                label(formationName(tier, arm, pieces), arm == TroopBranch.AIR ? .95 : 2.04, light);
                        }
                        pose.popPose();
                        if (index == 0 && arm != TroopBranch.GROUND && battle.nextDuel() != 0)
                            flightAndFire(arm, attacking, pos, light);
                    }
                }
            }
        }

        private void infantry(boolean attacking, TroopFormation tier, int pieces, int index, int paint,
                              Vec3 pos, float yaw, int light) {
            var ranks = attacking ? attackers : defenders;
            var soldier = ranks.computeIfAbsent(tier, t -> new Soldier(level, t));
            soldier.dress(paint);
            soldier.displayPieces(pieces);
            soldier.setPos(pos);
            // Minecraft entity yaw increases towards -X.
            float entityYaw = -yaw;
            soldier.yBodyRot = soldier.yBodyRotO = soldier.yHeadRot = soldier.yHeadRotO = entityYaw;
            double angle = 2 * Math.PI * phase;
            float stride = (float)Math.sin(4 * angle + index * .8);
            float windup = (float)Math.pow(.5 + .5 * Math.cos(angle + .72), 5);
            float strike = (float)Math.pow(.5 + .5 * Math.cos(angle - .18), 24);
            soldier.setBodyPose(new Rotations(10 * windup - 8 * strike, 21 * windup - 24 * strike, 0));
            soldier.setRightArmPose(new Rotations(-36 - 111 * windup + 42 * strike, 22 * windup - 38 * strike, 12 - 50 * strike));
            soldier.setLeftArmPose(new Rotations(-65 - 24 * windup, -12, -12));
            soldier.setRightLegPose(new Rotations(29 * stride, 0, 4));
            soldier.setLeftLegPose(new Rotations(-29 * stride, 0, -4));
            Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(soldier)
                    .render(soldier, entityYaw, partial, pose, buffers, light);
        }

        private void vehicle(TroopBranch arm, boolean attacking, int index, int paint, float facing, int light) {
            pose.pushPose();
            double angle = phase * 2 * Math.PI;
            if (arm == TroopBranch.AIR) {
                var ahead = BattleMotion.position(arm, attacking, index, phase + .003);
                var here = BattleMotion.position(arm, attacking, index, phase);
                double d = ahead.depth() - here.depth(), l = ahead.lane() - here.lane();
                pose.mulPose(Axis.YP.rotationDegrees(yaw(battle.dx()*d - battle.dz()*l, battle.dz()*d + battle.dx()*l)));
                pose.mulPose(Axis.XP.rotation((float)-Math.atan2(ahead.height() - here.height(), Math.hypot(d, l))));
                pose.mulPose(Axis.ZP.rotationDegrees((float)(20 + 22 * Math.cos(angle + index * .36))));
                pose.scale(1.24f, 1.24f, 1.24f);
                BattlefieldVehicles.plane(pose, buffers, paint, light, phase);
            } else {
                float heading = (float)(10 * Math.sin(angle + index));
                pose.mulPose(Axis.YP.rotationDegrees(facing + heading));
                BattlefieldVehicles.wake(pose, buffers, light, phase + .14 * index);
                pose.mulPose(Axis.ZP.rotationDegrees((float)(4 * Math.sin(2 * angle + index))));
                pose.scale(1.34f, 1.34f, 1.34f);
                BattlefieldVehicles.ship(pose, buffers, paint, light, phase, -heading);
            }
            pose.popPose();
        }

        private void label(Component text, double height, int light) {
            var mc = Minecraft.getInstance();
            pose.pushPose();
            pose.translate(0, height, 0);
            pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
            pose.scale(.022f, -.022f, .022f);
            mc.font.drawInBatch(text, -mc.font.width(text) / 2f, 0, -1, true, pose.last().pose(),
                    buffers, Font.DisplayMode.NORMAL, 0, light);
            pose.popPose();
        }

        private void flightAndFire(TroopBranch branch, boolean attacking, Vec3 pos, int light) {
            int facing = attacking ? 1 : -1;
            Vec3 forward = new Vec3(battle.dx()*facing, 0, battle.dz()*facing);
            Vec3 across = new Vec3(-forward.z, 0, forward.x);
            if (branch == TroopBranch.AIR) {
                // Sample the actual flight path so the twin trails bend into the turn.
                Vec3 prev = pos, prevDir = pos.subtract(point(branch, attacking, 0, phase - .006)).multiply(1, 0, 1).normalize();
                for (int i = 1; i <= 6; i++) {
                    Vec3 past = point(branch, attacking, 0, phase - .012 * i);
                    Vec3 dir = prev.subtract(past).multiply(1, 0, 1).normalize();
                    for (int side = -1; side <= 1; side += 2) {
                        Vec3 a = prev.add(-prevDir.z * side * 1.08, .16, prevDir.x * side * 1.08);
                        Vec3 b = past.add(-dir.z * side * 1.08, .16 + .015*i, dir.x * side * 1.08);
                        BattlefieldVehicles.stroke(pose, buffers, eye, a, a.lerp(b, 1 - i * .075),
                                .046f - i * .0045f, 0xCAE8E7, light);
                    }
                    prev = past;
                    prevDir = dir;
                }
            }

            var enemy = attacking ? battle.defending() : battle.attacking();
            if (enemy.total() == 0) return;
            var targetBranch = enemy.count(branch) > 0 ? branch : enemy.branchAt(0);
            Vec3 target = point(targetBranch, !attacking, 0, 0).add(0, targetBranch == TroopBranch.GROUND ? 1 : .35, 0);
            if (branch == TroopBranch.NAVAL && phase >= BattleMotion.CANNON_PHASE) {
                Vec3 muzzle = point(branch, attacking, 0, BattleMotion.CANNON_PHASE).add(forward.scale(1.38)).add(0, 1.03, 0);
                double t = (phase - BattleMotion.CANNON_PHASE) / (1 - BattleMotion.CANNON_PHASE);
                Vec3 head = shellPoint(muzzle, target, t), tail = shellPoint(muzzle, target, Math.max(0, t - .075));
                BattlefieldVehicles.tracer(pose, buffers, eye, tail, head, .095f);
            }
            if (branch == TroopBranch.AIR) {
                for (int shot = 0; shot < 3; shot++) {
                    double start = BattleMotion.STRAFE_PHASE + shot * .055, t = (phase - start) / .09;
                    if (t < 0 || t > 1) continue;
                    var gun = point(branch, attacking, 0, start).add(across.scale((shot % 2 == 0 ? .65 : -.65)));
                    BattlefieldVehicles.tracer(pose, buffers, eye, gun.lerp(target, Math.max(0, t - .22)),
                            gun.lerp(target, t), .046f);
                }
            }
        }

        private static Vec3 shellPoint(Vec3 from, Vec3 to, double t) {
            return from.lerp(to, t).add(0, 8 * t * (1 - t), 0);
        }

        private static float yaw(double x, double z) { return (float)Math.toDegrees(Math.atan2(x, z)); }
    }

    private BattlefieldRenderer() {}
}
