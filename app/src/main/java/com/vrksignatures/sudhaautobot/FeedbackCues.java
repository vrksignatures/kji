package com.vrksignatures.sudhaautobot;

/**
 * Decides when the bot shows something to the person watching: a flourish on the panel, a
 * full-screen card. Silently: the app has no voice, no chime and no click of its own (the
 * songs are a separate matter, see {@link MusicMood}).
 *
 * <p>This is presentation only. It is fed the same state name the floating panel already
 * prints and gives back a cue; nothing here is read by the automation, so no cue - shown,
 * missed or failed - can change what the bot does next.</p>
 *
 * <p>Cues are tied to milestones, never to taps: one order placed, the whole run done,
 * the run stopped.</p>
 */
public final class FeedbackCues {

    public enum Cue {
        NONE,
        /** A run began (not a resume after a pause). */
        RUN_STARTED,
        /** One retailer's order reached the success screen. */
        ORDER_PLACED,
        /** The run got to the end of the list. */
        RUN_COMPLETE,
        /** The run stopped on a safety check. */
        RUN_STOPPED
    }

    private FeedbackCues() {
    }

    /**
     * The cue for a change of state. Staying in a state is never a cue, so repainting the
     * panel (which happens many times a second) cannot repeat a sound.
     *
     * @param previous state name shown before, or null when nothing was shown yet
     * @param next     state name being shown now
     */
    public static Cue on(String previous, String next) {
        if (next == null || previous == null || next.equals(previous)) return Cue.NONE;
        // Work begins: from idle, or again after a finished or stopped run. Coming back
        // from a pause is the same run carrying on, so it says nothing.
        if (!isResting(next) && isResting(previous) && !"PAUSED".equals(previous)) {
            return Cue.RUN_STARTED;
        }
        switch (next) {
            case "SUCCESS":
                return Cue.ORDER_PLACED;
            case "COMPLETE":
                return Cue.RUN_COMPLETE;
            case "ERROR":
                return Cue.RUN_STOPPED;
            default:
                return Cue.NONE;
        }
    }

    /**
     * Follows the states as they are drawn and hands out each cue exactly once.
     *
     * <p>The first state it is shown only sets the scene (showing the panel again after a
     * stop must not replay the stop). An order is cheered only when the count of placed
     * orders has actually gone up, so a success screen that is looked at twice is still one
     * cheer.</p>
     */
    public static final class Tracker {
        private String shown;
        private int cheeredAt;

        /**
         * @param state  state name being drawn now
         * @param placed orders placed today, as the panel shows it
         */
        public Cue next(String state, int placed) {
            String before = shown;
            shown = state;
            // First frame, or the ledger was reset: start counting from here.
            if (before == null || placed < cheeredAt) cheeredAt = placed;
            Cue cue = on(before, state);
            if (cue == Cue.ORDER_PLACED) {
                boolean newOrder = placed > cheeredAt;
                cheeredAt = placed;
                if (!newOrder) return Cue.NONE;
            }
            return cue;
        }
    }

    /** True for the states in which the bot is no longer driving the screen. */
    public static boolean isResting(String state) {
        return "COMPLETE".equals(state) || "ERROR".equals(state)
                || "PAUSED".equals(state) || "IDLE".equals(state);
    }

    // ------------------------------------------------------------------ words

    /** Headline and line under it, for the end of a run. */
    private static final String[][] VICTORY = {
            {"Kaam complete hua", "Aapka kaam complete hua."},
            {"All done", "Every retailer on today's list has been handled."},
    };

    /** Headline and line under it, for a run that stopped. */
    private static final String[][] DRAMA = {
            {"Run stopped", "Something on the screen was not what Sutara expected."},
            {"Needs a look", "The run has stopped so that nothing is ordered by mistake."},
    };

    /** Short enough to sit where the state name is printed on the panel. */
    private static final String[] ORDER = {
            "Order placed", "Placed", "Done, next",
    };

    /** What the screen says when the whole list is done. */
    public static final String COMPLETE_HEADLINE = "Kaam complete hua";
    public static final String COMPLETE_SUBLINE = "Aapka kaam complete hua.";

    public static String[] victoryWords(int turn) {
        return VICTORY[Math.floorMod(turn, VICTORY.length)];
    }

    public static String[] dramaWords(int turn) {
        return DRAMA[Math.floorMod(turn, DRAMA.length)];
    }

    public static String orderWords(int turn) {
        return ORDER[Math.floorMod(turn, ORDER.length)];
    }

    /** "118 placed · 5 skipped · 2 to check". */
    public static String tally(int placed, int skipped, int toCheck) {
        StringBuilder line = new StringBuilder();
        line.append(placed).append(" placed");
        if (skipped > 0) line.append("  ·  ").append(skipped).append(" skipped");
        if (toCheck > 0) line.append("  ·  ").append(toCheck).append(" to check");
        return line.toString();
    }
}
