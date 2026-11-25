package com.modserver.defense;

import com.modserver.ModServer;
import com.modserver.claim.Claim;
import com.modserver.nation.Nation;
import com.modserver.nation.NationRelation;

import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.*;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.*;

import java.util.*;

@GameTestHolder("modserver")
@PrefixGameTestTemplate(false)
public final class DefenseGameTests {
    @GameTest(template = "empty", batch = "territorial_defense")
    public void rulesSurviveReloadAndProtectTreaties(GameTestHelper t) {
        var a = new Nation(UUID.randomUUID(), "Home", UUID.randomUUID(), 0, 0);
        var foreign = UUID.randomUUID();
        var pilot = UUID.randomUUID();
        var book = new DefenseRegister();
        var site = new DefenseRegister.Site("minecraft:overworld", BlockPos.ZERO);
        var s = new DefenseRegister.Station(UUID.randomUUID(), site, DefenseTier.BASTION);
        book.sites.put(site, s);
        s.owner = a.id();
        s.armed = true;
        s.engage.add(foreign);
        t.assertTrue(s.hostile(a, pilot, foreign), "The country list applies to its pilots");
        s.protect.add(pilot);
        t.assertFalse(s.hostile(a, pilot, foreign), "Personal protection takes precedence");
        s.protect.clear();
        a.setRelation(foreign, NationRelation.PACT);
        t.assertFalse(s.hostile(a, pilot, foreign), "A treaty protects a listed nation");
        t.assertFalse(s.hostile(a, pilot, a.id()), "Own citizens have passage");
        s.link = new BlockPos(-3, 122, 7);
        s.radar = true;
        var saved =
                DefenseRegister.load(
                                book.save(new CompoundTag(), t.getLevel().registryAccess()),
                                t.getLevel().registryAccess())
                        .sites
                        .get(site);
        t.assertTrue(
                saved.id.equals(s.id)
                        && saved.engage.contains(foreign)
                        && saved.link.equals(s.link)
                        && saved.armed
                        && saved.radar,
                "Orders, scope identity and radar link survive restart");
        t.succeed();
    }

    @GameTest(template = "empty", batch = "territorial_defense", timeoutTicks = 160)
    public void kineticMagazineAndCapture(GameTestHelper t) {
        if (!ModList.get().isLoaded("create")) {
            t.succeed();
            return;
        }
        Trials.kinetics(t);
    }

    @GameTest(template = "empty", batch = "territorial_visitors", timeoutTicks = 160)
    public void protectedPlayerThenHostilePlayer(GameTestHelper t) {
        if (!ModList.get().isLoaded("create")) {
            t.succeed();
            return;
        }
        Trials.personnel(t);
    }

    @GameTest(template = "empty", batch = "territorial_defense", timeoutTicks = 220)
    public void radarGuidesTurretAgainstRealSublevel(GameTestHelper t) {
        if (!ModList.get().isLoaded("create_radar") || !ModList.get().isLoaded("sable")) {
            t.succeed();
            return;
        }
        Trials.vehicle(t, false);
    }

    @GameTest(template = "empty", batch = "territorial_defense", timeoutTicks = 220)
    public void flareDivertsPhysicalSkyrocket(GameTestHelper t) {
        if (!ModList.get().isLoaded("create") || !ModList.get().isLoaded("sable")) {
            t.succeed();
            return;
        }
        Trials.vehicle(t, true);
    }

    @GameTest(template = "empty", batch = "territorial_defense", timeoutTicks = 220)
    public void interceptorResolvesIncomingRound(GameTestHelper t) {
        if (!ModList.get().isLoaded("create") || !ModList.get().isLoaded("sable")) {
            t.succeed();
            return;
        }
        Trials.intercept(t);
    }

    private static final class Trials {
        record Rig(
                ServerLevel level,
                BlockPos pos,
                Nation nation,
                DefenseRegister.Station station,
                BarrelBlockEntity barrel) {
            Vec3 origin() {
                return Vec3.atCenterOf(pos).add(0, .65, 0);
            }

            void finish(GameTestHelper t) {
                var cp = new ChunkPos(pos);
                level.setChunkForced(cp.x, cp.z, false);
                var service = ModServer.nations();
                service.claims().release(DefenseRegister.dimension(level), cp.x, cp.z);
                service.nations().remove(nation.id());
                t.succeed();
            }
        }

        static Rig rig(GameTestHelper t, DefenseTier tier, int lane) {
            var level = t.getLevel();
            var dim = DefenseRegister.dimension(level);
            var anchor = new ChunkPos(t.absolutePos(BlockPos.ZERO));
            var cp = new ChunkPos(anchor.x + lane * 3 + 1, anchor.z - 2);
            level.setChunkForced(cp.x, cp.z, true);
            var pos = cp.getBlockAt(2, 110, 7);
            var service = ModServer.nations();
            UUID owner = UUID.randomUUID();
            var country =
                    service.nations()
                            .create(
                                    UUID.randomUUID(),
                                    "Workshop " + owner.toString().substring(0, 8),
                                    owner,
                                    0,
                                    0);
            service.claims().release(dim, cp.x, cp.z);
            service.claims().claim(new Claim(dim, cp.x, cp.z, country.id(), 0));
            var casing =
                    BuiltInRegistries.BLOCK
                            .get(ResourceLocation.parse("create:brass_casing"))
                            .defaultBlockState();
            level.setBlockAndUpdate(pos.north(), casing);
            level.setBlockAndUpdate(pos.south(), casing);
            level.setBlockAndUpdate(
                    pos.west(),
                    Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
            var motor =
                    BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:creative_motor"));
            level.setBlockAndUpdate(
                    pos.below(),
                    motor.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
            var drive = pos.below();
            level.getServer()
                    .getCommands()
                    .performPrefixedCommand(
                            level.getServer().createCommandSourceStack(),
                            "data merge block "
                                    + drive.getX()
                                    + " "
                                    + drive.getY()
                                    + " "
                                    + drive.getZ()
                                    + " {ScrollValue:256}");
            level.setBlockAndUpdate(
                    pos, DefenseHardware.TURRETS.get(tier).get().defaultBlockState());
            var book = DefenseRegister.of(level);
            var station = book.station(level, pos, tier);
            station.armed = true;
            var barrel = (BarrelBlockEntity) level.getBlockEntity(pos.west());
            barrel.setItem(0, new ItemStack(DefenseHardware.ammo(tier), 4));
            return new Rig(level, pos, country, station, barrel);
        }

        static void personnel(GameTestHelper t) {
            var r = rig(t, DefenseTier.WATCHTOWER, 4);
            var profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), "BorderVisitor");
            var pilot =
                    new net.minecraft.server.level.ServerPlayer(
                            r.level.getServer(),
                            r.level,
                            profile,
                            net.minecraft.server.level.ClientInformation.createDefault());
            var connection =
                    new net.minecraft.network.Connection(
                            net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            var channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
            pilot.connection =
                    new net.minecraft.server.network.ServerGamePacketListenerImpl(
                            r.level.getServer(),
                            connection,
                            pilot,
                            net.minecraft.server.network.CommonListenerCookie.createInitial(
                                    profile, false));
            pilot.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            var pvp = r.level.getServer().isPvpAllowed();
            r.level.getServer().setPvpAllowed(true);
            pilot.setNoGravity(true);
            var pos = r.pos.east(6);
            r.level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
            pilot.moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
            r.level.addNewPlayer(pilot);
            r.station.protect.add(pilot.getUUID());
            r.station.engage.add(pilot.getUUID());
            t.runAfterDelay(
                    28,
                    () -> {
                        t.assertTrue(
                                pilot.getHealth() == pilot.getMaxHealth()
                                        && r.barrel.getItem(0).getCount() == 4,
                                "A protected visitor crosses without spending ammunition");
                        r.station.protect.clear();
                    });
            t.runAfterDelay(
                    110,
                    () -> {
                        boolean hit =
                                pilot.getHealth() < pilot.getMaxHealth()
                                        && r.barrel.getItem(0).getCount() < 4;
                        r.level.removePlayerImmediately(
                                pilot, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                        channel.finishAndReleaseAll();
                        r.level.getServer().setPvpAllowed(pvp);
                        t.assertTrue(
                                hit,
                                "Survival visitor health "
                                        + pilot.getHealth()
                                        + ", arrows remaining "
                                        + r.barrel.getItem(0).getCount());
                        r.finish(t);
                    });
        }

        static void kinetics(GameTestHelper t) {
            var r = rig(t, DefenseTier.SKYGUARD, 0);
            t.runAfterDelay(
                    15,
                    () -> {
                        var turret = (TurretEntity) r.level.getBlockEntity(r.pos);
                        t.assertTrue(
                                Math.abs(turret.getSpeed()) >= 48
                                        && turret.calculateStressApplied() > 0,
                                "Create propagates rotation from the motor");
                        t.assertTrue(
                                turret.status(r.level, r.station).equals("Ready"),
                                "A complete supplied battery is ready");
                        r.level.removeBlock(r.pos.north(), false);
                        t.assertTrue(
                                turret.status(r.level, r.station).contains("casings"),
                                "Missing multiblock support blocks firing");
                        r.level.setBlockAndUpdate(
                                r.pos.north(), r.level.getBlockState(r.pos.south()));
                        r.barrel.clearContent();
                        t.assertTrue(
                                turret.status(r.level, r.station).contains("Needs"),
                                "Empty magazine blocks firing");
                        r.level.removeBlock(r.pos.below(), false);
                    });
            t.runAfterDelay(
                    20,
                    () -> {
                        var turret = (TurretEntity) r.level.getBlockEntity(r.pos);
                        t.assertTrue(
                                turret.getSpeed() == 0,
                                "Disconnecting the shaft stops the installation");
                        var claim =
                                ModServer.nations()
                                        .claims()
                                        .claimAt(
                                                DefenseRegister.dimension(r.level),
                                                r.pos.getX() >> 4,
                                                r.pos.getZ() >> 4);
                        ModServer.nations().claims().transfer(claim, UUID.randomUUID());
                        t.assertFalse(
                                r.station.armed, "A captured installation returns to hold fire");
                        r.finish(t);
                    });
        }

        static void assembly(Rig r, BlockPos target) {
            for (int x = -2; x <= 2; x++)
                for (int z = -2; z <= 2; z++)
                    r.level.setBlockAndUpdate(
                            target.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            r.level.setBlockAndUpdate(target, Blocks.GOLD_BLOCK.defaultBlockState());
            String xyz = target.getX() + " " + target.getY() + " " + target.getZ();
            r.level
                    .getServer()
                    .getCommands()
                    .performPrefixedCommand(
                            r.level.getServer().createCommandSourceStack(),
                            "sable assemble area " + xyz + " " + xyz);
        }

        static void vehicle(GameTestHelper t, boolean flare) {
            var r = rig(t, DefenseTier.SKYGUARD, flare ? 2 : 1);
            assembly(r, r.pos.east(10));
            trackVehicle(t, r, flare);
        }

        static UUID ship(GameTestHelper t, Rig r) {
            var craft = Airships.nearby(r.level, new AABB(r.pos.east(10)).inflate(4));
            t.assertFalse(craft.isEmpty(), "Sable exposes the assembled vehicle in world space");
            return craft.getFirst().id();
        }

        static void trackVehicle(GameTestHelper t, Rig r, boolean flare) {
            var ship = new UUID[1];
            t.runAfterDelay(
                    12,
                    () -> {
                        ship[0] = ship(t, r);
                        r.station.engage.add(ship[0]);
                    });
            if (!flare) {
                var pos = r.pos.offset(0, 2, 0);
                var radar =
                        BuiltInRegistries.BLOCK.get(
                                ResourceLocation.parse("create_radar:plane_radar"));
                r.level.setBlockAndUpdate(
                        pos,
                        radar.defaultBlockState()
                                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
                var owner =
                        new net.neoforged.neoforge.common.util.FakePlayer(
                                r.level,
                                new com.mojang.authlib.GameProfile(r.nation.owner(), "Surveyor"));
                var hand = net.minecraft.world.InteractionHand.MAIN_HAND;
                var key = DefenseHardware.KEY.get().getDefaultInstance();
                owner.setItemInHand(hand, key);
                for (var selected : List.of(pos, r.pos)) {
                    var hit =
                            new BlockHitResult(
                                    Vec3.atCenterOf(selected), Direction.UP, selected, false);
                    owner.gameMode.useItemOn(owner, r.level, key, hand, hit);
                }
                t.assertTrue(
                        r.station.radar && pos.equals(r.station.link),
                        "The survey key links a real radar to the turret");
            }
            var diverted = new boolean[1];
            var observed = new boolean[1];
            t.onEachTick(
                    () -> {
                        var rounds =
                                r.level.getEntitiesOfClass(
                                        DefenseRound.class,
                                        new AABB(r.pos).inflate(20),
                                        s -> s.kind() == DefenseRound.Kind.SKYROCKET);
                        if (!rounds.isEmpty()) {
                            if (flare && !observed[0])
                                DefenseRound.flare(r.level, r.origin().add(7, 1, 1), Vec3.ZERO);
                            observed[0] = true;
                            if (rounds.getFirst().diverted) diverted[0] = true;
                        }
                        if (!observed[0] || !rounds.isEmpty()) return;
                        var ships = Airships.nearby(r.level, new AABB(r.pos.east(10)).inflate(3));
                        if (flare) {
                            t.assertTrue(
                                    diverted[0]
                                            && ships.stream().anyMatch(c -> c.id().equals(ship[0])),
                                    "Flare preserves the vehicle and diverts the rocket");
                        } else if (ships.stream().anyMatch(c -> c.id().equals(ship[0]))) return;
                        t.assertTrue(
                                r.barrel.getItem(0).getCount() == 3,
                                "One launch consumes exactly one rocket");
                        r.finish(t);
                    });
        }

        static void intercept(GameTestHelper t) {
            var r = rig(t, DefenseTier.AEGIS, 3);
            var targetPos = r.pos.east(21);
            var enemyChunk = new ChunkPos(targetPos);
            var level = r.level;
            var dim = DefenseRegister.dimension(level);
            var enemyId = UUID.randomUUID();
            var enemy =
                    ModServer.nations()
                            .nations()
                            .create(
                                    enemyId,
                                    "Raid " + enemyId.toString().substring(0, 6),
                                    UUID.randomUUID(),
                                    0,
                                    0);
            var claims = ModServer.nations().claims();
            level.setChunkForced(enemyChunk.x, enemyChunk.z, true);
            claims.claim(new Claim(dim, enemyChunk.x, enemyChunk.z, enemyId, 0));
            assembly(r, targetPos);
            var site = targetPos.south(4);
            level.setBlockAndUpdate(
                    site,
                    DefenseHardware.TURRETS.get(DefenseTier.SKYGUARD).get().defaultBlockState());
            var raid = DefenseRegister.of(level).station(level, site, DefenseTier.SKYGUARD);
            raid.armed = true;
            r.nation.setRelation(enemyId, NationRelation.WAR);
            var missile = new DefenseRound[1];
            var hull = new DefenseContact[1];
            t.runAfterDelay(
                    15,
                    () -> {
                        var contacts = Airships.nearby(level, new AABB(targetPos).inflate(4));
                        t.assertFalse(
                                contacts.isEmpty(),
                                "The incoming missile has a physical vehicle target");
                        hull[0] = contacts.getFirst();
                        raid.engage.add(hull[0].id());
                        missile[0] =
                                DefenseRound.launch(level, raid, hull[0], r.origin().add(0, 1, 10));
                        t.assertTrue(missile[0] != null, "The incoming skyrocket is in flight");
                    });
            t.runAfterDelay(
                    55,
                    () -> {
                        t.assertTrue(
                                missile[0].isRemoved() && r.barrel.getItem(0).getCount() == 3,
                                "A powered Aegis consumed one round and intercepted the skyrocket");
                        t.assertTrue(
                                Airships.nearby(level, new AABB(targetPos).inflate(4)).stream()
                                        .anyMatch(c -> c.id().equals(hull[0].id())),
                                "The vehicle survived the interception");
                        claims.release(dim, enemyChunk.x, enemyChunk.z);
                        level.setChunkForced(enemyChunk.x, enemyChunk.z, false);
                        ModServer.nations().nations().remove(enemy.id());
                        r.finish(t);
                    });
        }
    }
}
