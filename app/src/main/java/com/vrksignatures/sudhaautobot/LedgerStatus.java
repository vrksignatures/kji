package com.vrksignatures.sudhaautobot;

/** Terminal outcome stored per retailer for the current day. */
public enum LedgerStatus {
    /** Order was placed and the success screen was acknowledged. */
    SUCCESS("SUCCESS"),
    /** Copy Order produced no items for the very latest previous day. */
    SKIPPED_EMPTY("SKIPPED"),
    /** No copyable data for T-1, T-2 or T-3. */
    SKIPPED_NO_DATA("SKIPPED"),
    /** Order history itself was empty (no previous days to copy from). */
    SKIPPED_NO_HISTORY("SKIPPED"),
    /**
     * The app refused PLACE ORDER with "Today Order already created": this retailer
     * already has today's order, so there is nothing for the bot to do.
     */
    ALREADY_ORDERED("SKIPPED"),
    /** Above the start point given by the operator; not opened by the bot. */
    DONE_BEFORE("SKIPPED"),
    /** Details header did not match the row that was tapped. */
    SKIPPED_ID_MISMATCH("SKIPPED"),
    /** Something went wrong with this retailer; it is not retried today. */
    ERROR("ERROR"),
    /**
     * PLACE ORDER was tapped but no result could be verified. Treated as
     * untouchable for the rest of the day so an order is never submitted twice.
     */
    IN_FLIGHT_UNVERIFIED("ERROR");

    private final String bucket;

    LedgerStatus(String bucket) {
        this.bucket = bucket;
    }

    /** Which panel counter this status increments: SUCCESS, SKIPPED or ERROR. */
    public String bucket() {
        return bucket;
    }

    public boolean isSuccess() {
        return this == SUCCESS;
    }

    public boolean isSkip() {
        return "SKIPPED".equals(bucket);
    }
}
