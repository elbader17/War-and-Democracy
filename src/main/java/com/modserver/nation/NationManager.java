package com.modserver.nation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Nation manager: creation, lookup, members, relations and name validation.
 */
public final class NationManager {

    /** 3-24 characters, letters, numbers and underscore. */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,24}$");

    private final Map<UUID, Nation> byId = new LinkedHashMap<>();
    /** Index by normalized name (lowercase) to avoid duplicate names. */
    private final Map<String, UUID> byName = new LinkedHashMap<>();

    public Nation create(UUID id, String name, UUID owner, int color, long createdAt) {
        String normalized = name.toLowerCase(Locale.ROOT);
        if (byName.containsKey(normalized)) {
            throw new IllegalArgumentException("A nation with that name already exists");
        }
        // Game rule: one nation per player (they can only own one).
        if (ownedBy(owner).isPresent()) {
            throw new IllegalStateException("A player can only found one nation");
        }
        Nation nation = new Nation(id, name, owner, color, createdAt);
        byId.put(id, nation);
        byName.put(normalized, id);
        return nation;
    }

    public void add(Nation nation) {
        byId.put(nation.id(), nation);
        byName.put(nation.name().toLowerCase(Locale.ROOT), nation.id());
    }

    public Optional<Nation> byId(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<Nation> byName(String name) {
        return Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT))).map(byId::get);
    }

    /** The nation whose owner is that player, if any. */
    public Optional<Nation> ownedBy(UUID player) {
        for (Nation nation : byId.values()) {
            if (nation.owner().equals(player)) {
                return Optional.of(nation);
            }
        }
        return Optional.empty();
    }

    /** The nation the player is a member of (not necessarily the owner). */
    public Optional<Nation> membershipOf(UUID player) {
        for (Nation nation : byId.values()) {
            if (nation.isMember(player)) {
                return Optional.of(nation);
            }
        }
        return Optional.empty();
    }

    public Collection<Nation> all() {
        return byId.values();
    }

    public int count() {
        return byId.size();
    }

    public void remove(UUID id) {
        Nation nation = byId.remove(id);
        if (nation != null) {
            byName.remove(nation.name().toLowerCase(Locale.ROOT));
            // Clear relations pointing to this nation.
            for (Nation other : byId.values()) {
                other.setRelation(id, NationRelation.NEUTRAL);
            }
        }
    }

    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    public static List<String> validateName(String name) {
        List<String> problems = new ArrayList<>();
        if (name == null || name.isBlank()) {
            problems.add("The name cannot be empty");
            return problems;
        }
        if (!isValidName(name)) {
            problems.add("The name must have 3-24 letters, numbers or underscores");
        }
        return problems;
    }
}
