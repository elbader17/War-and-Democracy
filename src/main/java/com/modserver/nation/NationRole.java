package com.modserver.nation;

/** Role of a player inside a nation. */
public enum NationRole {

    /** Owner: can dissolve the nation, hand it over and manage everything. */
    OWNER,
    /** Officer: can invite/remove members and manage claims. */
    OFFICER,
    /** Member: can claim chunks on behalf of the nation. */
    MEMBER;

    public boolean canManageMembers() {
        return this == OWNER || this == OFFICER;
    }

    public boolean canManageClaims() {
        return this != null;
    }
}
