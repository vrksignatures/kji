package com.vrksignatures.sudhaautobot;

import java.util.List;

/**
 * Which retailer comes next.
 *
 * <p>The rule is the operator's: the bot works down the list <b>in the order the list
 * shows</b>, and the next retailer is always the one after the retailer it handled last.
 * It never goes back up to an earlier row, even one that has not been handled.</p>
 *
 * <p>This matters because the Sudha app reopens the customer list folded up and at the
 * top after every retailer. The previous rule - "open the first unhandled SMR on the
 * screen" - is then the same thing as "go back to the top": after SMR2250 was done by
 * hand the bot went to SMR2255, the first row, instead of SMR2249.</p>
 *
 * <p>How it is decided, each time the list is looked at:</p>
 * <ul>
 *   <li>nothing handled yet (no <i>last retailer</i>): the first unhandled SMR on screen;</li>
 *   <li>the last retailer is on screen: the first unhandled SMR <b>below</b> it. Rows above
 *       it are never opened;</li>
 *   <li>the last retailer has not been seen since the list was opened: scroll down, open
 *       nothing - everything on screen is still above it;</li>
 *   <li>the last retailer was seen and has since scrolled off the top: the first unhandled
 *       SMR on screen, since everything on screen is now below it.</li>
 * </ul>
 *
 * <p>No assumption is made about how the ids are numbered. The list happens to count down
 * (SMR2255, SMR2254 ...), but only the order on the screen is used.</p>
 */
public final class RetailerSequence {

    /** What to do on the list right now. */
    public enum Action {
        /** Open {@link Decision#id}. */
        OPEN,
        /** Scroll down: the retailer handled last has not come into view yet. */
        SCROLL_TO_LAST,
        /** Scroll down: every retailer below the last one on this screen is handled. */
        SCROLL_FOR_MORE
    }

    /** Answers "has this retailer been handled today?". */
    public interface Handled {
        boolean is(String id);
    }

    public static final class Decision {
        public final Action action;
        /** Retailer to open, or null. */
        public final String id;
        /** Position of that retailer in the list given to {@link #decide}, or -1. */
        public final int index;

        private Decision(Action action, String id, int index) {
            this.action = action;
            this.id = id;
            this.index = index;
        }
    }

    private boolean lastSeen;

    /**
     * The list has just been opened afresh (a retailer was opened, Show Retailers was
     * tapped, or the run was started), so nothing is known about where it is scrolled to.
     */
    public void newVisit() {
        lastSeen = false;
    }

    /** True once the retailer handled last has been on screen during this visit. */
    public boolean lastSeen() {
        return lastSeen;
    }

    /**
     * @param idsTopToBottom customer ids on screen, top row first (distributors may be included)
     * @param last           the retailer handled last, or null when none has been
     * @param handled        the day's ledger
     */
    public Decision decide(List<String> idsTopToBottom, String last, Handled handled) {
        int from = 0;
        if (last != null && !last.isEmpty()) {
            int at = idsTopToBottom == null ? -1 : idsTopToBottom.indexOf(last);
            if (at >= 0) {
                lastSeen = true;
                from = at + 1;
            } else if (!lastSeen) {
                return new Decision(Action.SCROLL_TO_LAST, null, -1);
            }
        }
        if (idsTopToBottom != null) {
            for (int i = from; i < idsTopToBottom.size(); i++) {
                String id = idsTopToBottom.get(i);
                if (!RetailerPolicy.isRetailerId(id)) continue;
                if (handled != null && handled.is(id)) continue;
                return new Decision(Action.OPEN, id, i);
            }
        }
        return new Decision(Action.SCROLL_FOR_MORE, null, -1);
    }
}
