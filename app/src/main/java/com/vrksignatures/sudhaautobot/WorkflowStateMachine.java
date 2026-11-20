package com.vrksignatures.sudhaautobot;

/**
 * Holds the bot's current {@link BotState} plus the retailer it is working on.
 *
 * <p>Deliberately tiny and boring: every transition goes through one of the
 * methods below, all of which are synchronized and which notify a listener so the
 * floating panel never renders a stale state. Illegal resumptions (for example
 * jumping straight from IDLE to PAYMENT) are rejected instead of silently
 * corrupting the run.</p>
 */
public final class WorkflowStateMachine {

    /** Implemented by the service to repaint the panel. */
    public interface Listener {
        void onStateChanged(BotState state, String retailerId, String message);
    }

    private BotState state = BotState.IDLE;
    private String retailerId;
    private String message = "Waiting for START";
    private Listener listener;

    public synchronized void setListener(Listener listener) {
        this.listener = listener;
    }

    public synchronized BotState state() {
        return state;
    }

    public synchronized String retailerId() {
        return retailerId;
    }

    public synchronized String message() {
        return message;
    }

    /** True while the loop is actively driving screens. */
    public synchronized boolean isActive() {
        switch (state) {
            case IDLE:
            case PAUSED:
            case ERROR:
            case COMPLETE:
                return false;
            default:
                return true;
        }
    }

    /** Attaches a verified retailer id and moves to DETAILS. */
    public synchronized boolean beginRetailer(String id) {
        if (!RetailerPolicy.isRetailerId(id)) return false;
        retailerId = id;
        moveTo(BotState.DETAILS, "Opened " + id + ", verifying details header");
        return true;
    }

    /** Free-form transition; refuses to invent a retailer or to bypass verification. */
    public synchronized void moveTo(BotState next, String detail) {
        if (next == null) return;
        if (next == BotState.DETAILS && retailerId == null) return;
        if ((next == BotState.PLACE_ORDER || next == BotState.PAYMENT || next == BotState.SUCCESS)
                && retailerId == null) {
            // Orders must always be attributable to a verified retailer.
            return;
        }
        state = next;
        message = detail == null ? "" : detail;
        notifyListener();
    }

    public synchronized void clearRetailer() {
        retailerId = null;
    }

    /** User pressed PAUSE (or the service is going away). */
    public synchronized void pause(String reason) {
        state = BotState.PAUSED;
        message = reason == null ? "Paused" : reason;
        notifyListener();
    }

    /** Safety stop: the bot refused to guess. */
    public synchronized void fail(String reason) {
        state = BotState.ERROR;
        message = reason == null ? "Stopped for safety" : reason;
        notifyListener();
    }

    /** Full reset back to a startable state. */
    public synchronized void reset(String detail) {
        state = BotState.IDLE;
        retailerId = null;
        message = detail == null ? "Waiting for START" : detail;
        notifyListener();
    }

    private void notifyListener() {
        Listener current = listener;
        if (current != null) current.onStateChanged(state, retailerId, message);
    }
}
