package com.vrksignatures.sudhaautobot;

import java.util.List;

/**
 * Scroll memory: getting back to the place in the retailer list without reading every
 * screenful on the way down.
 *
 * <p>The Sudha app reopens the customer list at the top after every retailer. Until now
 * the bot came down again one checked scroll at a time, reading the rows after each. With
 * this, the number of scrolls it took to reach a retailer is remembered with that retailer;
 * next time that many scrolls are sent one after the other <b>without looking</b>, and only
 * then are the rows read.</p>
 *
 * <p>If the retailer handled last is on screen after that, the ordinary rule
 * ({@link RetailerSequence}) opens the one after it. If it is not, this looks around:</p>
 * <ol>
 *   <li>every retailer on screen is already handled: the place is further down, and the
 *       ordinary scan - which goes down - simply carries on from here;</li>
 *   <li>otherwise one scroll <b>up</b>, and look;</li>
 *   <li>then two scrolls <b>down</b> (the screenful below where the count ended), and look;</li>
 *   <li>then up, one scroll at a time, looking after each, until the retailer is found or
 *       the list will go no further up.</li>
 * </ol>
 *
 * <p>Whatever happens it ends by handing the list back to the ordinary scan, which finds
 * the place the way it always did. So a wrong count costs time and nothing else; and after
 * a search that failed the caller stops using the memory for the rest of the run.</p>
 *
 * <p>Plain Java: this class decides, the service scrolls. It opens nothing and records
 * nothing.</p>
 */
public final class ScrollMemory {

    /** What the service should do now. */
    public enum Move {
        /** Nothing more from here: read the list the ordinary way. */
        NONE,
        /** Send one scroll down. */
        FORWARD,
        /** Send one scroll up. */
        BACK,
        /** Look again in a moment (the rows are not on screen yet). */
        WAIT
    }

    /** How it ended, for the log and the tests. */
    public enum End { NOT_ENDED, FOUND, FURTHER_DOWN, GAVE_UP, REFUSED }

    /** A count above this is not believed (the list is a few hundred rows at most). */
    public static final int MAX_COUNT = 300;
    /**
     * Pause between two scrolls sent without looking. It has to be long enough for one
     * scroll to finish before the next is sent, and how long that is depends on how the app
     * draws its list - so it starts short and {@link #nextGap} lengthens it when the
     * counted scrolls are seen to have fallen short.
     */
    public static final long BLIND_GAP_MS = 350;
    public static final long MAX_BLIND_GAP_MS = 950;
    /** Pause before the rows are read after a scroll. */
    public static final long LOOK_GAP_MS = 450;

    private ScrollMemory() {
    }

    /**
     * The pause to use next time. Ending above the retailer after a count that was right
     * means the scrolls ran into each other, so the pause grows; it never shrinks again.
     */
    public static long nextGap(long gap, End end) {
        long now = Math.max(BLIND_GAP_MS, Math.min(gap, MAX_BLIND_GAP_MS));
        return end == End.FURTHER_DOWN ? Math.min(MAX_BLIND_GAP_MS, now + 200) : now;
    }

    /** True when a saved count can be used at all. */
    public static boolean usable(int count) {
        return count > 0 && count <= MAX_COUNT;
    }

    /** Answers "has this retailer been handled today?". */
    public interface Handled {
        boolean is(String id);
    }

    /** One trip down the list. Make a new one each time the list is opened afresh. */
    public static final class Seek {
        private static final int COUNTING = 0, ARRIVED = 1, WENT_UP = 2, DOWN_ONE = 3,
                WENT_DOWN = 4, CLIMBING = 5, OVER = 6;
        private static final int MAX_WAITS = 2;

        public final String id;
        public final int count;
        private int phase = COUNTING;
        private int waits;
        private int climbed;
        private String before = "";
        private End end = End.NOT_ENDED;
        /** Scrolls sent, down minus up. */
        private int net;

        public Seek(String id, int count) {
            this.id = id;
            this.count = Math.max(0, Math.min(count, MAX_COUNT));
        }

        public boolean over() {
            return phase == OVER;
        }

        public End end() {
            return end;
        }

        /**
         * The first thing to do: this many scrolls down, one after the other, without
         * reading the rows in between. Call once; then send them; then ask {@link #next}.
         */
        public int burst() {
            if (phase != COUNTING) return 0;
            phase = ARRIVED;
            net += count;
            return count;
        }

        /** True when the scroll just asked for is to be followed by another without a look. */
        public boolean blind() {
            return phase == DOWN_ONE;
        }

        /** Scrolls sent so far, down minus up. */
        public int net() {
            return net;
        }

        /** The service could not send the scroll it was asked for. */
        public void refused() {
            finish(End.REFUSED);
        }

        /**
         * @param idsTopToBottom customer ids on screen, top row first
         * @param handled        the day's ledger
         * @param rows           anything that changes when the list moves (the row snapshot)
         */
        public Move next(List<String> idsTopToBottom, Handled handled, String rows) {
            if (phase == OVER) return Move.NONE;
            // The counted scrolls were never asked for: there is nothing to look around from.
            if (phase == COUNTING) return finish(End.REFUSED);
            if (phase == DOWN_ONE) {
                // The second of the two scrolls down: the first only undid the scroll up.
                phase = WENT_DOWN;
                return send(Move.FORWARD);
            }

            // From here on the rows are read before anything else is done.
            if (idsTopToBottom != null && idsTopToBottom.contains(id)) return finish(End.FOUND);
            int retailers = 0;
            int unhandled = 0;
            if (idsTopToBottom != null) {
                for (String one : idsTopToBottom) {
                    if (!RetailerPolicy.isRetailerId(one)) continue;
                    retailers++;
                    if (handled == null || !handled.is(one)) unhandled++;
                }
            }
            if (retailers == 0 && waits < MAX_WAITS) {
                waits++;
                return Move.WAIT;
            }
            switch (phase) {
                case ARRIVED:
                    // Rows above the retailer handled last are all handled; rows below it
                    // are not. Nothing unhandled in sight: the place is further down.
                    if (unhandled == 0) return finish(End.FURTHER_DOWN);
                    phase = WENT_UP;
                    before = rows == null ? "" : rows;
                    return send(Move.BACK);
                case WENT_UP:
                    phase = DOWN_ONE;
                    return send(Move.FORWARD);
                case WENT_DOWN:
                    phase = CLIMBING;
                    before = rows == null ? "" : rows;
                    climbed = 0;
                    return send(Move.BACK);
                case CLIMBING:
                    // The list did not move: this is its top (or it will not scroll up).
                    if (rows != null && rows.equals(before)) return finish(End.GAVE_UP);
                    if (++climbed > count + 4) return finish(End.GAVE_UP);
                    before = rows == null ? "" : rows;
                    return send(Move.BACK);
                default:
                    return finish(End.GAVE_UP);
            }
        }

        private Move send(Move move) {
            net += move == Move.FORWARD ? 1 : -1;
            return move;
        }

        private Move finish(End how) {
            phase = OVER;
            end = how;
            return Move.NONE;
        }
    }
}
