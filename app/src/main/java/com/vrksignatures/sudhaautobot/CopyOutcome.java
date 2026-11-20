package com.vrksignatures.sudhaautobot;

import java.util.Locale;

/**
 * "Has the copied order arrived in the form?" - asked every 150 ms after the calendar's OK,
 * so that PLACE ORDER is pressed as soon as the order is there instead of after a fixed
 * wait of about three seconds.
 *
 * <p>The rule stays the one in docs/REFERENCE-FLOW.md: the copy worked when the app shows
 * no error dialog. What changes is how long the bot waits for that dialog. The app answers
 * a Copy Order in one of two ways - the dialog, or the order written into the form - so
 * once the form has visibly been rewritten, the answer has come and it was not the dialog.
 * </p>
 *
 * <p>"Rewritten" is judged without reading a single value: the writing on the form is
 * compared, as a whole, with what was there at the first look after the calendar closed
 * (and with what was there before Copy Order was pressed). Dates and clock times are left
 * out of the comparison, since choosing a date fills the Date box whether or not there is
 * an order. The form must then read the same on two looks in a row, so it is not caught
 * half-written.</p>
 *
 * <p>This can make the bot quicker, not bolder: if the form never changes, the old wait
 * applies in full. And the service stops using it for a day if it ever sees the form
 * change and the error dialog arrive after all.</p>
 */
public final class CopyOutcome {

    /** How often the form is looked at while waiting. */
    public static final long POLL_MS = 150;
    /** The first look after the calendar's OK: long enough for the dialog to have closed. */
    public static final long FIRST_LOOK_MS = 300;

    public enum Verdict {
        /** Nothing to go on yet; look again. */
        WAIT,
        /** The form has been rewritten and has settled: the order is in it. */
        ARRIVED,
        /** The old wait has run out with no dialog: proceed as the bot always did. */
        TIMED_OUT
    }

    private static final String[] BUSY = {
            "loading", "please wait", "fetching", "processing",
    };
    private static final String[] REFUSING = {
            "no order", "not found", "no data", "error", "failed", "unable", "try again",
            "already created", "retry",
    };

    /** A date as the form may write it: 18-11-2026, 18/11/26, 2026-11-18, 18 Nov 2026, 18 Nov,2026, Nov 18, 2026. */
    private static final String MONTH = "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*";
    private static final String DATE = "\\b(\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{1,4}"
            + "|\\d{1,2}[-/. ]?" + MONTH + "[-/., ]{0,2}(\\d{2,4})?"
            + "|" + MONTH + "[-/. ]{0,2}\\d{1,2}(,? ?\\d{4})?)\\b";
    private static final String TIME = "\\b\\d{1,2}:\\d{2}(:\\d{2})?( ?[ap]\\.?m\\.?)?";
    private static final String WEEKDAY = "\\b(mon|tue|wed|thu|fri|sat|sun)[a-z]*\\b,?";

    private CopyOutcome() {
    }

    /** Lower case, one space between words, and without dates, weekdays and clock times. */
    static String flat(CharSequence text) {
        if (text == null) return "";
        return text.toString().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")
                .replaceAll(DATE, " ").replaceAll(TIME, " ").replaceAll(WEEKDAY, " ")
                .replaceAll("\\s+", " ").trim();
    }

    /** Watches one Copy Order from the calendar's OK to the verdict. */
    public static final class Watch {
        private final String before;
        private final long settleMs;
        private String after;
        private String previous;
        private long since;
        private boolean sawChange;

        /**
         * @param beforeCopy everything written on the form just before Copy Order was pressed
         * @param settleMs   how long the bot waited for the dialog before this was added
         */
        public Watch(CharSequence beforeCopy, long settleMs) {
            this.before = flat(beforeCopy);
            this.settleMs = settleMs;
        }

        /** True once the form was seen rewritten. A dialog after that means this rule misjudged. */
        public boolean sawChange() {
            return sawChange;
        }

        /**
         * One look at the form. Only call it when no dialog and no calendar is on screen.
         *
         * @param formText  everything written on the form now
         * @param canPlace  PLACE ORDER is on the form
         * @param now       the time of this look
         */
        public Verdict look(CharSequence formText, boolean canPlace, long now) {
            String is = flat(formText);
            if (since <= 0) since = now;
            if (after == null) {
                // The first look after the calendar: the Date box is filled by now.
                after = is;
                previous = is;
                return timedOut(now);
            }
            boolean rewritten = !before.isEmpty() && !is.isEmpty()
                    && !is.equals(after) && !is.equals(before) && canPlace && calm(is);
            boolean settled = is.equals(previous);
            previous = is;
            if (rewritten) {
                sawChange = true;
                if (settled) return Verdict.ARRIVED;
            }
            return timedOut(now);
        }

        private Verdict timedOut(long now) {
            return now - since >= settleMs ? Verdict.TIMED_OUT : Verdict.WAIT;
        }

        /** Nothing on the form says "busy", and nothing new on it says "no". */
        private boolean calm(String is) {
            for (String word : BUSY) if (is.contains(word)) return false;
            for (String word : REFUSING) if (is.contains(word) && !before.contains(word)) return false;
            return true;
        }
    }
}
