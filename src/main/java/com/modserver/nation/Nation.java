package com.modserver.nation;

import com.modserver.economy.NationEconomy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A nation: the main entity of the mod.
 *
 * <p>Fixed game rules:</p>
 * <ul>
 *   <li>Each player can own <b>one</b> nation.</li>
 *   <li>A nation <b>survives the death</b> of its owner: it is not dissolved nor inherited.</li>
 *   <li>Claimed chunks <b>do not expire</b>.</li>
 * </ul>
 */
public final class Nation {

    private final UUID id;
    private String name;
    private UUID owner;
    private final Map<UUID, NationRole> members = new LinkedHashMap<>();
    private int color;
    private String capitalDimension;
    private int capitalX;
    private int capitalZ;
    private long createdAt;
    private final Map<UUID, NationRelation> relations = new LinkedHashMap<>();
    /** Nation economy: treasury, defense and investments. */
    private final NationEconomy economy = new NationEconomy();

    public Nation(UUID id, String name, UUID owner, int color, long createdAt) {
        this.id = id;
        this.name = name;
        this.owner = owner;
        this.members.put(owner, NationRole.OWNER);
        this.color = color;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public UUID owner() {
        return owner;
    }

    public void setOwner(UUID newOwner) {
        this.owner = newOwner;
        members.put(newOwner, NationRole.OWNER);
    }

    public Map<UUID, NationRole> members() {
        return Collections.unmodifiableMap(members);
    }

    public NationRole roleOf(UUID player) {
        return members.get(player);
    }

    public boolean isMember(UUID player) {
        return members.containsKey(player);
    }

    public int memberCount() {
        return members.size();
    }

    public void setRole(UUID player, NationRole role) {
        if (role == NationRole.OWNER) {
            setOwner(player);
            return;
        }
        members.put(player, role);
    }

    public NationRole removeMember(UUID player) {
        if (player.equals(owner)) {
            return null;
        }
        return members.remove(player);
    }

    public void setColor(int color) {
        this.color = color;
    }

    public int color() {
        return color;
    }

    public void setCapital(String dimension, int chunkX, int chunkZ) {
        this.capitalDimension = dimension;
        this.capitalX = chunkX;
        this.capitalZ = chunkZ;
    }

    public String capitalDimension() {
        return capitalDimension;
    }

    public int capitalX() {
        return capitalX;
    }

    public int capitalZ() {
        return capitalZ;
    }

    public boolean hasCapital() {
        return capitalDimension != null;
    }

    public long createdAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    /** Nation economy (treasury, defense debt, investments). */
    public NationEconomy economy() {
        return economy;
    }

    public Map<UUID, NationRelation> relations() {
        return Collections.unmodifiableMap(relations);
    }

    public NationRelation relationWith(UUID otherNation) {
        return relations.getOrDefault(otherNation, NationRelation.NEUTRAL);
    }

    public void setRelation(UUID otherNation, NationRelation relation) {
        if (relation == null || relation == NationRelation.NEUTRAL) {
            relations.remove(otherNation);
        } else {
            relations.put(otherNation, relation);
        }
    }
}
