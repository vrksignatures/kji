package com.vrksignatures.sudhaautobot;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Carry on after this retailer": the rules behind the day's history and the
 * <i>Last done ID</i> box on the floating panel.
 *
 * <p>Plain Java, no Android: what counts as an id, how long a remembered place stays good,
 * and what happens to the rows the bot scrolls past on its way down to that place.</p>
 *
 * <p>The bot's own rule is untouched: the next retailer is the one after the retailer handled
 * last ({@link RetailerSequence}). A start point only says <i>which</i> retailer that is when
 * the day's ledger does not know - because Reset was pressed, or the box was filled in.</p>
 */
public final class StartPoint {


    private StartPoint() {
    }

    /**
     * What was typed in the box, as a retailer id: "2244", "smr 2244" and "SMR2244" all give
     * {@code SMR2244}. Anything that is not a retailer id gives null.
     */
    public static String normalise(String typed) {
        if (typed == null) return null;
        String text = typed.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s\\-_.]", "");
        if (text.isEmpty()) return null;
        if (text.matches("[0-9]{1,6}")) text = "SMR" + text;
        return RetailerPolicy.isRetailerId(text) ? text : null;
    }

    /** The figures of an id, for the box: SMR2244 gives "2244". */
    public static String digits(String id) {
        return id == null ? "" : id.replaceAll("[^0-9]", "");
    }

    /**
     * True while a place saved on {@code savedDay} may still be used: only on that same
     * calendar day. The date alone decides - no count of hours. A place saved at 23:59 is
     * gone at 00:00, one saved at 00:01 is good until the next midnight. A new day always
     * starts from the top, because yesterday's orders say nothing about today's.
     *
     * @param savedAt when it was saved; 0 or less means "never saved"
     * @param now     not used to decide (the date decides); kept so callers read the same
     */
    public static boolean fresh(long savedAt, String savedDay, long now, String today) {
        if (savedAt <= 0) return false;
        return sameDay(savedDay, today);
    }

    /** True when both are the same calendar date, written {@code yyyy-MM-dd}. */
    public static boolean sameDay(String savedDay, String today) {
        return savedDay != null && today != null && !today.isEmpty() && savedDay.equals(today);
    }

    /**
     * What a history file holding the date {@code heldDay} must do when today is
     * {@code today}: {@code true} = wipe it whole, the date has changed. A file with no
     * date yet (written by an older version) is not wiped; its entries carry their own day.
     */
    public static boolean dayTurned(String heldDay, String today) {
        if (today == null || today.isEmpty()) return false;
        return heldDay != null && !heldDay.equals(today);
    }

    /**
     * Milliseconds from {@code now} to the next midnight in this time zone; between one
     * second and a little over a day (a day can be 23 or 25 hours long where clocks change).
     */
    public static long untilMidnight(long now, java.util.TimeZone zone) {
        java.util.Calendar next = java.util.Calendar.getInstance(
                zone == null ? java.util.TimeZone.getDefault() : zone, Locale.US);
        next.setTimeInMillis(now);
        next.set(java.util.Calendar.HOUR_OF_DAY, 0);
        next.set(java.util.Calendar.MINUTE, 0);
        next.set(java.util.Calendar.SECOND, 0);
        next.set(java.util.Calendar.MILLISECOND, 0);
        next.add(java.util.Calendar.DAY_OF_MONTH, 1);
        return Math.max(1000L, next.getTimeInMillis() - now);
    }

    /** "just now", "25 min ago", "2 h ago". */
    public static String age(long savedAt, long now) {
        long minutes = Math.max(0, (now - savedAt) / 60_000L);
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + " min ago";
        return (minutes / 60) + " h ago";
    }

    /** The part of the day's ledger a start point needs. */
    public interface Book {
        boolean isProcessed(String id);

        /** The retailer the bot will carry on after, or null. */
        String lastHandled();

        /** Writes "done before this run" for a retailer, without moving the place in the list. */
        boolean markDoneBefore(String id, String why);
    }

    /**
     * Follows the bot down the list to the start point.
     *
     * <p>While the bot scrolls, every retailer above the start point goes past unopened.
     * Those rows are remembered here, and written to the ledger as done <b>only once the
     * start point itself has come into view</b>. If it never does - a mistyped number - the
     * bot's own rule sends it back to the top, nothing has been written, and every retailer
     * is opened as usual. A typing mistake therefore costs time, never an order.</p>
     */
    public static final class Seeker {
        public enum Outcome { SEEKING, REACHED, LOST }

        public final String id;
        private final String why;
        private final Set<String> passed = new LinkedHashSet<>();
        private Outcome outcome = Outcome.SEEKING;
        private int marked;

        public Seeker(String id, String why) {
            this.id = id;
            this.why = why == null ? "" : why;
        }

        public boolean finished() {
            return outcome != Outcome.SEEKING;
        }

        public boolean reached() {
            return outcome == Outcome.REACHED;
        }

        public boolean lost() {
            return outcome == Outcome.LOST;
        }

        /** How many retailers were written as done when the start point was reached. */
        public int marked() {
            return marked;
        }

        /** Rows seen so far above the start point (not yet written anywhere). */
        public int waiting() {
            return passed.size();
        }

        /**
         * @param idsTopToBottom customer ids on screen, top row first
         * @return SEEKING while the start point has not been seen; REACHED when it is on
         *         screen (the rows above it are now in the ledger); LOST when the bot has
         *         given up looking for it
         */
        public Outcome sees(List<String> idsTopToBottom, Book book) {
            if (outcome != Outcome.SEEKING) return outcome;
            if (book == null || !id.equals(book.lastHandled())) {
                // The bot no longer carries on after this id: it reached the end of the
                // list without finding it and has gone back to the top.
                passed.clear();
                return outcome = Outcome.LOST;
            }
            int at = idsTopToBottom == null ? -1 : idsTopToBottom.indexOf(id);
            if (at < 0) {
                if (idsTopToBottom != null) {
                    for (String seen : idsTopToBottom) {
                        if (RetailerPolicy.isRetailerId(seen)) passed.add(seen);
                    }
                }
                return Outcome.SEEKING;
            }
            for (int i = 0; i <= at; i++) {
                String seen = idsTopToBottom.get(i);
                if (RetailerPolicy.isRetailerId(seen)) passed.add(seen);
            }
            String note = "Above the start point " + id + (why.isEmpty() ? "" : " (" + why + ")")
                    + "; taken as done, not opened";
            for (String above : passed) {
                if (book.isProcessed(above)) continue;
                if (book.markDoneBefore(above, note)) marked++;
            }
            passed.clear();
            return outcome = Outcome.REACHED;
        }
    }
}
