package com.modserver.council;

import com.modserver.government.Commodity;
import com.modserver.nation.NationRelation;
import java.util.Objects;
import java.util.UUID;

/** Offers live in the recipient's register. Correspondence is filed by both parties. */
public record CouncilPost(UUID id, UUID from, UUID to, Kind kind, Commodity item, int quantity,
                          long coins, UUID seal, String text) {
    public enum Kind {
        PACT, ALLY, PEACE, TRADE, LETTER, CIRCULAR, ACCEPTED, DECLINED, WITHDRAWN;
        public boolean treaty() { return this == PACT || this == ALLY || this == PEACE; }
        public boolean offer() { return treaty() || this == TRADE; }
        public NationRelation relation() {
            return switch (this) {
                case PACT -> NationRelation.PACT;
                case ALLY -> NationRelation.ALLY;
                case PEACE -> NationRelation.NEUTRAL;
                default -> throw new IllegalStateException("Not a treaty");
            };
        }
    }

    public CouncilPost {
        Objects.requireNonNull(id);
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        Objects.requireNonNull(kind);
        if (from.equals(to) || text == null || text.length() > 160) throw new IllegalArgumentException("Invalid dispatch");
        if (kind == Kind.TRADE && (item == null || seal == null || quantity < 1 || quantity > 3456
                || coins < 1 || coins > 1_000_000_000)) throw new IllegalArgumentException("Invalid trade");
    }

    public static CouncilPost note(UUID from, UUID to, Kind kind, String text) {
        return new CouncilPost(UUID.randomUUID(), from, to, kind, null, 0, 0, null, text);
    }
}
