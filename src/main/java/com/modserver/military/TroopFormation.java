package com.modserver.military;

import java.util.List;

/** Denominations for displaying one garrison. Orders, casualties and saves use base troops. */
public enum TroopFormation {
    SOLDIER(1, "soldier"), SQUAD(10, "squad"), COMPANY(100, "company"), BATTALION(1_000, "battalion");

    public static final List<TroopFormation> LARGEST_FIRST = List.of(BATTALION, COMPANY, SQUAD, SOLDIER);
    public final int value;
    private final String key;

    TroopFormation(int value, String key) {
        this.value = value;
        this.key = "tac.formation." + key;
    }

    public String key(TroopBranch branch) {
        return branch == TroopBranch.GROUND ? key : key + "." + branch.id;
    }

    public static TroopFormation largest(int troops) {
        for (var formation : LARGEST_FIRST) {
            if (troops >= formation.value) return formation;
        }
        return SOLDIER;
    }

    /** Number of these pieces after exchanging every complete set for the next denomination. */
    public int pieces(int troops) {
        return this == BATTALION ? troops / value : troops / value % 10;
    }
}
