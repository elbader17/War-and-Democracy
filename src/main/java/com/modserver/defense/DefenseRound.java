package com.modserver.defense;

import com.modserver.ModServer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.*;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.*;

public final class DefenseRound extends Entity {
    public enum Kind {
        BOLT,
        SLUG,
        SKYROCKET,
        INTERCEPTOR,
        FLARE
    }

    private static final EntityDataAccessor<Integer> KIND =
            SynchedEntityData.defineId(DefenseRound.class, EntityDataSerializers.INT);
    UUID nation, target, stationId;
    BlockPos stationPos;
    Vec3 course = Vec3.ZERO;
    int age;
    boolean diverted;

    public DefenseRound(EntityType<? extends DefenseRound> type, Level level) {
        super(type, level);
    }

    public Kind kind() {
        return Kind.values()[entityData.get(KIND)];
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder data) {
        data.define(KIND, 0);
    }

    static DefenseRound launch(
            ServerLevel level, DefenseRegister.Station site, DefenseContact contact, Vec3 origin) {
        var round = new DefenseRound(DefenseHardware.ROUND.get(), level);
        round.entityData.set(
                KIND,
                switch (site.tier) {
                    case WATCHTOWER -> Kind.BOLT.ordinal();
                    case BASTION -> Kind.SLUG.ordinal();
                    case SKYGUARD -> Kind.SKYROCKET.ordinal();
                    case AEGIS -> Kind.INTERCEPTOR.ordinal();
                });
        round.nation = site.owner;
        round.stationPos = site.site.pos();
        round.stationId = site.id;
        round.target = contact.id();
        round.course = contact.point().subtract(origin).normalize();
        round.setPos(origin);
        round.orient();
        return level.addFreshEntity(round) ? round : null;
    }

    static boolean flare(ServerLevel level, Vec3 origin, Vec3 look) {
        var round = new DefenseRound(DefenseHardware.ROUND.get(), level);
        round.entityData.set(KIND, Kind.FLARE.ordinal());
        round.setPos(origin);
        round.course = look.scale(.32).add(0, .12, 0);
        return level.addFreshEntity(round);
    }

    private void orient() {
        setYRot((float) Math.toDegrees(Math.atan2(course.x, course.z)));
        setXRot((float) -Math.toDegrees(Math.atan2(course.y, course.horizontalDistance())));
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel level)) return;
        age++;
        Kind kind = kind();
        if (age > (kind == Kind.FLARE ? 36 : 110)) {
            discard();
            return;
        }
        if (kind == Kind.FLARE) {
            course = course.scale(.95).add(0, -.014, 0);
            setPos(position().add(course));
            trail(level, ParticleTypes.FIREWORK);
            trail(level, ParticleTypes.FLAME);
            return;
        }
        var book = DefenseRegister.of(level);
        var site =
                book.sites.get(
                        new DefenseRegister.Site(DefenseRegister.dimension(level), stationPos));
        if (site == null || !site.id.equals(stationId)) {
            discard();
            return;
        }
        book.reconcile(site);
        var owner = ModServer.nations().nations().byId(nation).orElse(null);
        if (owner == null || !nation.equals(site.owner) || !site.armed) {
            discard();
            return;
        }
        DefenseContact contact;
        if (diverted) {
            var decoy = level.getEntity(target);
            if (decoy == null || decoy.isRemoved()) {
                burst(level);
                return;
            }
            contact = new DefenseContact(target, null, decoy.position());
        } else {
            contact =
                    DefenseContact.nearby(level, site.tier, position(), site.tier.range + 8)
                            .stream()
                            .filter(c -> c.id().equals(target))
                            .findFirst()
                            .orElse(null);
            if (contact == null
                    || !site.hostile(owner, contact.id(), contact.nation())
                    || !site.covers(contact.point())) {
                discard();
                return;
            }
            if (kind == Kind.SKYROCKET) {
                var flares =
                        level.getEntitiesOfClass(
                                DefenseRound.class,
                                new AABB(contact.point(), contact.point()).inflate(8),
                                r -> r.kind() == Kind.FLARE && r.distanceToSqr(this) < 18 * 18);
                if (!flares.isEmpty()) {
                    var flare = flares.getFirst();
                    target = flare.getUUID();
                    diverted = true;
                    contact = new DefenseContact(target, null, flare.position());
                }
            }
        }
        double step =
                switch (kind) {
                    case BOLT, SLUG -> 2.4;
                    case INTERCEPTOR -> 1.65;
                    default -> .8;
                };
        if (kind == Kind.SKYROCKET || kind == Kind.INTERCEPTOR)
            course = contact.point().subtract(position()).normalize();
        Vec3 next = position().add(course.scale(step));
        if (!level.isLoaded(BlockPos.containing(next))) {
            discard();
            return;
        }
        var block =
                level.clip(
                        new ClipContext(
                                position(),
                                next,
                                ClipContext.Block.COLLIDER,
                                ClipContext.Fluid.NONE,
                                CollisionContext.empty()));
        if (block.getType() != HitResult.Type.MISS) next = block.getLocation();
        var bounds =
                new AABB(contact.point(), contact.point()).inflate(kind == Kind.BOLT ? .48 : .8);
        boolean hits = bounds.contains(position()) || bounds.clip(position(), next).isPresent();
        if (!diverted
                && (kind == Kind.SKYROCKET || kind == Kind.SLUG)
                && block.getType() != HitResult.Type.MISS)
            hits |= Airships.belongs(level, block.getBlockPos(), target);
        setPos(next);
        orient();
        switch (kind) {
            case SKYROCKET -> {
                trail(level, ParticleTypes.FLAME);
                trail(level, ParticleTypes.WHITE_SMOKE);
            }
            case INTERCEPTOR -> {
                trail(level, ParticleTypes.SOUL_FIRE_FLAME);
                trail(level, ParticleTypes.END_ROD);
            }
            default -> trail(level, ParticleTypes.CRIT);
        }
        if (hits) {
            var entity = level.getEntity(target);
            if (!diverted && kind == Kind.BOLT && entity instanceof LivingEntity living)
                living.hurt(damageSources().generic(), 5);
            if (!diverted && (kind == Kind.SKYROCKET || kind == Kind.SLUG))
                Airships.hit(
                        level,
                        target,
                        contact.point(),
                        block.getType() == HitResult.Type.MISS ? null : block.getBlockPos(),
                        kind == Kind.SLUG ? 1 : 3);
            if (kind == Kind.INTERCEPTOR
                    && entity instanceof DefenseRound r
                    && r.kind() == Kind.SKYROCKET) r.burst(level);
            burst(level);
        } else if (block.getType() != HitResult.Type.MISS) burst(level);
    }

    private void trail(ServerLevel level, ParticleOptions particle) {
        level.sendParticles(particle, getX(), getY(), getZ(), 2, .04, .04, .04, .01);
    }

    private void burst(ServerLevel level) {
        if (isRemoved()) return;
        boolean rocket = kind() == Kind.SKYROCKET || kind() == Kind.INTERCEPTOR;
        level.sendParticles(
                rocket ? ParticleTypes.FLASH : ParticleTypes.CRIT,
                getX(),
                getY(),
                getZ(),
                rocket ? 1 : 6,
                .13,
                .13,
                .13,
                .1);
        if (rocket) {
            level.sendParticles(
                    kind() == Kind.SKYROCKET ? ParticleTypes.FIREWORK : ParticleTypes.END_ROD,
                    getX(),
                    getY(),
                    getZ(),
                    22,
                    .35,
                    .35,
                    .35,
                    .12);
            level.playSound(
                    null,
                    blockPosition(),
                    SoundEvents.FIREWORK_ROCKET_BLAST,
                    SoundSource.BLOCKS,
                    .8f,
                    .85f);
        }
        discard();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag t) {
        t.putInt("Kind", kind().ordinal());
        t.putInt("Age", age);
        t.putBoolean("Diverted", diverted);
        if (stationPos != null) t.putLong("Origin", stationPos.asLong());
        if (stationId != null) t.putUUID("Station", stationId);
        if (nation != null) t.putUUID("Nation", nation);
        if (target != null) t.putUUID("Target", target);
        t.putDouble("DX", course.x);
        t.putDouble("DY", course.y);
        t.putDouble("DZ", course.z);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag t) {
        entityData.set(KIND, Math.clamp(t.getInt("Kind"), 0, Kind.values().length - 1));
        age = t.getInt("Age");
        diverted = t.getBoolean("Diverted");
        stationPos = BlockPos.of(t.getLong("Origin"));
        if (t.hasUUID("Station")) stationId = t.getUUID("Station");
        if (t.hasUUID("Nation")) nation = t.getUUID("Nation");
        if (t.hasUUID("Target")) target = t.getUUID("Target");
        course = new Vec3(t.getDouble("DX"), t.getDouble("DY"), t.getDouble("DZ"));
    }
}
