package com.vrksignatures.sudhaautobot;

/**
 * Every screen-level phase the bot can be in. The value is shown verbatim on the
 * floating panel so a failed run can be diagnosed without attaching a debugger.
 */
public enum BotState {
    /** Service is loaded, nothing has been started yet. */
    IDLE,
    /** Looking at the Sudha home screen (Daily Orders entry). */
    HOME,
    /** Looking at the retailer list (Show Retailers / customer cards). */
    RETAILER_LIST,
    /** Inside one retailer's details page, verifying the SMR header. */
    DETAILS,
    /** Inside that retailer's Milk order history. */
    HISTORY,
    /** Copy Order / calendar date selection. */
    COPY_ORDER,
    /** Order form filled; PLACE ORDER is about to be (or has been) tapped. */
    PLACE_ORDER,
    /** Payment / PROCEED / CONFIRM chain. */
    PAYMENT,
    /** Success screen reached but not yet acknowledged with Done. */
    SUCCESS,
    /** Walking back to the retailer list. */
    RETURNING_TO_LIST,
    /** Every eligible retailer for today has been handled. */
    COMPLETE,
    /** User stopped the run; all progress is kept in today's ledger. */
    PAUSED,
    /** A safety check failed — the bot stopped instead of guessing. */
    ERROR
}
