package com.modserver.defense;

import java.util.Locale;

public enum DefenseTier {
    WATCHTOWER("Watchtower", 16, 24, 24, 2),
    BASTION("Bastion", 32, 40, 44, 4),
    SKYGUARD("Skyguard", 48, 64, 80, 8),
    AEGIS("Aegis", 64, 80, 36, 12);

    public final String title;
    public final int rpm, range, reload, stress;

    DefenseTier(String title, int rpm, int range, int reload, int stress) {
        this.title = title;
        this.rpm = rpm;
        this.range = range;
        this.reload = reload;
        this.stress = stress;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean battery() {
        return this == SKYGUARD || this == AEGIS;
    }
}
