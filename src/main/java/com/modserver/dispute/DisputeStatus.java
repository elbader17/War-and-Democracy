package com.modserver.dispute;

/**
 * Status of a dispute over a chunk.
 *
 * <p>State machine:</p>
 * <pre>
 *   OPEN --defender--> DEFENDED   (the chunk stays with the defender)
 *   OPEN --expires---> KEPT (the defender remains in possession)
 *   OPEN --dice conquest--> TRANSFERRED
 *   OPEN --admin keep--> KEPT     (the chunk stays with the defender)
 *   OPEN --admin transfer--> TRANSFERRED
 *   OPEN --cancelled--> CANCELLED (the chunk was released or no longer exists)
 * </pre>
 */
public enum DisputeStatus {

    /** Open dispute: waiting for the defending nation to answer. */
    OPEN,
    /** The defending nation answered in time: the claim is kept. */
    DEFENDED,
    /** The chunk changed owner to the challenging nation. */
    TRANSFERRED,
    /** An administrator resolved the dispute in favour of the defender. */
    KEPT,
    /** The dispute was cancelled (the chunk was released or is no longer claimed). */
    CANCELLED;

    /** The dispute is still open and waiting for an answer. */
    public boolean isOpen() {
        return this == OPEN;
    }

    /** The dispute already has a result. */
    public boolean isResolved() {
        return this != OPEN;
    }

    /** The chunk stayed in the hands of the defending nation. */
    public boolean keptByDefender() {
        return this == DEFENDED || this == KEPT;
    }

    /** The chunk went to the challenging nation. */
    public boolean transferred() {
        return this == TRANSFERRED;
    }
}
