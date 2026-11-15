package com.modserver.nation;

/**
 * Diplomatic relation between two nations.
 *
 * <p>A pact ({@link #PACT}) prevents opening disputes between the two nations, but it
 * does not prevent claiming free chunks: it is a non-aggression agreement, not an
 * alliance.</p>
 */
public enum NationRelation {

    /** No particular agreement. */
    NEUTRAL,
    /** Non-aggression pact: disputes cannot be opened between them. */
    PACT,
    /** Alliance: nations share information and can claim together. */
    ALLY,
    /** War: disputes can be opened without penalty. */
    WAR;

    /** Whether the relation prevents opening disputes between the two nations. */
    public boolean blocksDisputes() {
        return this == PACT || this == ALLY;
    }

    public static NationRelation parse(String name, NationRelation fallback) {
        if (name == null) {
            return fallback;
        }
        for (NationRelation relation : values()) {
            if (relation.name().equalsIgnoreCase(name)) {
                return relation;
            }
        }
        return fallback;
    }
}
