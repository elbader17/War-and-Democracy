package com.modserver.economy;

/** Type of investment of a nation. */
public enum Investment {

    /** Farms: increase the passive income per chunk (+25% per level). */
    FARM("farm", "Farms"),

    /** Garrison: defends disputes without any player being connected. */
    GARRISON("garrison", "Garrison"),

    /** Treasury: daily interest over the treasury balance. */
    TREASURY("treasury", "Treasury");

    /** Spanish aliases, so older commands keep working. */
    private static final String[][] ALIASES = {
            {"granja"},
            {"guarnicion"},
            {"hacienda"},
    };

    private final String id;
    private final String label;

    Investment(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    /**
     * Looks it up by its identifier in the commands ({@code farm}, {@code garrison}...).
     *
     * <p>The Spanish aliases are still accepted so that commands written before the
     * translation keep working.</p>
     */
    public static Investment parse(String name) {
        if (name == null) {
            return null;
        }
        Investment[] values = values();
        for (Investment investment : values) {
            if (investment.id.equalsIgnoreCase(name) || investment.name().equalsIgnoreCase(name)) {
                return investment;
            }
        }
        for (int i = 0; i < values.length && i < ALIASES.length; i++) {
            for (String alias : ALIASES[i]) {
                if (alias.equalsIgnoreCase(name)) {
                    return values[i];
                }
            }
        }
        return null;
    }
}
