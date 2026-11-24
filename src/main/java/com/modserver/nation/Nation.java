package com.modserver.nation;

import com.modserver.economy.NationEconomy;
import com.modserver.council.CouncilPost;
import java.util.ArrayList;
import java.util.List;
import com.modserver.military.TroopTransfer;
import java.util.Collection;
import java.util.Comparator;
import java.util.PriorityQueue;

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
    private String preamble = "";
    private String doctrines = "";
    private Long treasuryChest;
    private String treasuryDimension;

    private UUID owner;
    private final Map<UUID, NationRole> members = new LinkedHashMap<>();
    private int color;
    private String capitalDimension;
    private int capitalX;
    private int capitalZ;
    private long createdAt;
    private final Map<UUID, NationRelation> relations = new LinkedHashMap<>();
    private final PriorityQueue<TroopTransfer> transfers = new PriorityQueue<>(Comparator.comparingLong(TroopTransfer::dueAt));

    private final List<CouncilPost> register = new ArrayList<>();
    private boolean envoy;
    public boolean isEnvoy() { return envoy; }
    public void setEnvoy(boolean value) { envoy = value; }
    public List<CouncilPost> register() { return Collections.unmodifiableList(register); }
    public void removePost(UUID id) { register.removeIf(post -> post.id().equals(id)); }
    public void file(CouncilPost post) {
        if (!post.to().equals(id) && (!post.from().equals(id) || post.kind().offer())) return;
        if (!post.kind().offer() && register.stream().filter(entry -> !entry.kind().offer()).count() >= 64)
            register.remove(register.stream().filter(entry -> !entry.kind().offer()).findFirst().orElseThrow());
        register.add(post);
    }

    public Collection<TroopTransfer> transfers() { return Collections.unmodifiableCollection(transfers); }
    public void addTransfer(TroopTransfer trip) { transfers.add(trip); }
    public TroopTransfer pollDueTransfer(long now) {
        var next = transfers.peek();
        return next != null && next.dueAt() <= now ? transfers.poll() : null;
    }

    /** Nation economy: treasury, troop reserve and investments. */
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

    void rename(String newName) {
        this.name = newName;
    }

    public String preamble() { return preamble; }
    public void setPreamble(String value) { preamble = value; }
    public String doctrines() { return doctrines; }
    public void setDoctrines(String value) { doctrines = value; }
    public Long treasuryChest() { return treasuryChest; }
    public String treasuryDimension() { return treasuryDimension; }
    public void setTreasuryChest(String dimension, long pos) {
        treasuryDimension = dimension;
        treasuryChest = pos;
    }

    public UUID owner() {
        return owner;
    }

    public void setOwner(UUID newOwner) {
        members.put(owner, NationRole.MEMBER);
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
        if (!player.equals(owner)) {
            members.put(player, role);
        }
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

    /** Nation economy (treasury, troop reserve, investments). */
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
        if (relationWith(otherNation) != relation)
            register.removeIf(post -> post.from().equals(otherNation) && post.kind().treaty());
        if (relation == null || relation == NationRelation.NEUTRAL) {
            relations.remove(otherNation);
        } else {
            relations.put(otherNation, relation);
        }
    }
}
