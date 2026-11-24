package com.modserver.military;

import net.minecraft.network.FriendlyByteBuf;

/** Base troops, independent of their display denominations. A null branch selects a mixed force. */
public record TroopCounts(int ground, int air, int naval) {
    public static final TroopCounts EMPTY = new TroopCounts(0, 0, 0);

    public TroopCounts {
        if (ground < 0 || air < 0 || naval < 0 || (long)ground + air + naval > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid troop counts");
    }

    public static TroopCounts of(TroopBranch branch, int quantity) {
        return switch (branch) {
            case GROUND -> new TroopCounts(quantity, 0, 0);
            case AIR -> new TroopCounts(0, quantity, 0);
            case NAVAL -> new TroopCounts(0, 0, quantity);
        };
    }

    public int total() { return ground + air + naval; }

    public int count(TroopBranch branch) {
        return switch (branch) {
            case null -> total();
            case GROUND -> ground;
            case AIR -> air;
            case NAVAL -> naval;
        };
    }

    public TroopCounts plus(TroopCounts other) {
        return new TroopCounts(ground + other.ground, air + other.air, naval + other.naval);
    }

    public TroopCounts minus(TroopCounts other) {
        return new TroopCounts(ground - other.ground, air - other.air, naval - other.naval);
    }

    /** Allocate a partial mixed order proportionally, distributing spare seats by largest remainder. */
    public TroopCounts take(int quantity, TroopBranch branch) {
        if (quantity < 0 || quantity > count(branch)) throw new IllegalArgumentException("Insufficient troops");
        if (quantity == 0) return EMPTY;
        if (quantity == total()) return this;
        if (branch != null) return of(branch, quantity);
        int[] seats = new int[3];
        long[] remainders = new long[3];
        int left = quantity;
        for (var arm : TroopBranch.ALL) {
            int i = arm.ordinal();
            long product = (long)count(arm) * quantity;
            seats[i] = (int)(product / total());
            remainders[i] = product % total();
            left -= seats[i];
        }
        while (left-- > 0) {
            int best = 0;
            for (int i = 1; i < seats.length; i++) if (remainders[i] > remainders[best]) best = i;
            seats[best]++;
            remainders[best] = -1;
        }
        return new TroopCounts(seats[0], seats[1], seats[2]);
    }

    public TroopBranch branchAt(int index) {
        if (index < 0 || index >= total()) throw new IndexOutOfBoundsException(index);
        if (index < ground) return TroopBranch.GROUND;
        return index - ground < air ? TroopBranch.AIR : TroopBranch.NAVAL;
    }

    public TroopBranch largestBranch() {
        var largest = TroopBranch.GROUND;
        for (var branch : TroopBranch.ALL) if (count(branch) > count(largest)) largest = branch;
        return largest;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(ground);
        buf.writeVarInt(air);
        buf.writeVarInt(naval);
    }

    public static TroopCounts read(FriendlyByteBuf buf) {
        return new TroopCounts(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }
}
