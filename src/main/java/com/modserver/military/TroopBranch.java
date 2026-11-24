package com.modserver.military;

import java.util.List;

public enum TroopBranch {
    GROUND("ground"), AIR("air"), NAVAL("naval");

    public static final List<TroopBranch> ALL = List.of(values());
    public final String id;
    TroopBranch(String id) { this.id = id; }

    public TroopBranch beats() {
        return switch (this) {
            case GROUND -> NAVAL;
            case AIR -> GROUND;
            case NAVAL -> AIR;
        };
    }

    public int duelRollAgainst(TroopBranch opponent, int roll) {
        return roll == 1 && beats() == opponent ? 2 : roll;
    }
}
