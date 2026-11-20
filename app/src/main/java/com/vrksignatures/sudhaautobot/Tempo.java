package com.vrksignatures.sudhaautobot;

import java.util.HashSet;
import java.util.Set;

/**
 * Fast mode: when a new page has come up and is still, look at it there and then, instead
 * of a fixed time after the last change on the screen.
 *
 * <p>How the bot kept time until now: every change on the screen pushed its next look to
 * 740 ms after that change (a 320 ms pause to let the changes finish, then the 420 ms
 * minimum gap). So after pressing a button it waited the better part of a second, however
 * quickly the next page came up.</p>
 *
 * <p>With this, each change on the screen also books a <em>peek</em> a few hundredths of a
 * second later. A peek reads the screen and presses nothing. When two peeks in a row read
 * the same thing, and it is a <em>different page</em> from the one the bot saw at its last
 * look, the look that was coming - the bot's ordinary one - is taken there and then.</p>
 *
 * <p>A different page means another window (a dialog opened or closed, another screen), or
 * the same window with most of its words changed (the list after a scroll, one page
 * replaced by the next). The same page with something small changed on it - a button's
 * ripple, a tick in a box, a spinner, the copied rows arriving in the order form - is
 * <em>not</em> looked at early: there the bot's own retries count on the full beat they
 * always had ("just tapped +: give the screen a full beat"), and they keep it.</p>
 *
 * <p>Floors under the early look, so it cannot come too early:</p>
 * <ul>
 *   <li>{@link #AFTER_ACTION_MS} after the bot's last press, and after its last look - the
 *       same gap the bot's own timer keeps, so two looks are never closer than they could
 *       be before;</li>
 *   <li>{@link #BETWEEN_IDLE_MS} after a look that pressed nothing, or is not known to
 *       have pressed anything (Back is one such) - the old rhythm of a wait, so looks
 *       cannot pile up while the bot waits for something;</li>
 *   <li>the phone's own window animation after a new page or dialog opened - a page still
 *       sliding in is not pressed. Scaled by the phone's animation setting; nought when
 *       animations are switched off.</li>
 * </ul>
 *
 * <p>And a ceiling: the early look is only taken while the bot's own 320 ms pause after
 * the last change on the screen is still running. When that pause ends it would book a
 * look 420 ms on; it is that look which is taken early, in its place - not a look added
 * to it. That is 420 to 620 ms sooner each time, and where less could be saved nothing is
 * done at all. After it, the bot keeps time by its own rules, as after any look.</p>
 *
 * <p>What a look does when it comes is untouched. What it costs: the bot may now look at a
 * page that has stopped half-written for a moment, which before it would have passed over.
 * Such a look finds nothing to press, counts as one look of whatever wait is running, and
 * in a narrow case the next press comes up to about half a second later than it would
 * have (TempoSelfTest prints how often, in its model). The service does not use this at
 * all while an order is being placed and paid for. Switched off, the bot keeps time
 * exactly as before.</p>
 *
 * <p>Why not "every 10 ms": reading a whole screen takes longer than that, every reading
 * is work for the Sudha app's own thread (it would become slower, not faster), and no
 * button can be pressed before the app has drawn it. The time that is left after this is
 * the app's and the server's.</p>
 *
 * <p>Plain Java: this class decides, the service reads the screen.</p>
 */
public final class Tempo {

    /** How long the screen must have been quiet before it is peeked at, and between two peeks. */
    public static final long SETTLE_MS = 60;
    /** The bot's own minimum gap between two presses (MIN_ACTION_GAP_MS). */
    public static final long AFTER_ACTION_MS = 420;
    /** The old rhythm of a wait: the debounce plus the minimum gap. */
    public static final long BETWEEN_IDLE_MS = 740;
    /** The bot's own pause after the last change on the screen (DEBOUNCE_MS). */
    public static final long PAUSE_MS = BETWEEN_IDLE_MS - AFTER_ACTION_MS;
    /** A page or dialog opening, at the phone's normal animation speed. */
    public static final long WINDOW_ANIMATION_MS = 350;
    /** Peeks spent on one burst of changes before the old timer is left to it. */
    public static final int MAX_PEEKS = 12;
    /** A screen sharing fewer than this many of its words, out of 100, is a different page. */
    public static final int SAME_PAGE_PERCENT = 50;

    /** Between two nodes in a screen's signature. */
    public static final char NODE = '\n';
    /** Between a node's words and the rest of it (state, place) in a screen's signature. */
    public static final char WORDS_END = '\u0001';

    public enum Verdict {
        /** Run the bot's ordinary look now. */
        STEP,
        /** Peek again after {@link Answer#delay} ms. */
        PEEK,
        /** Nothing new; the timers that are already running will do. */
        LEAVE
    }

    public static final class Answer {
        public final Verdict verdict;
        public final long delay;

        Answer(Verdict verdict, long delay) {
            this.verdict = verdict;
            this.delay = delay;
        }
    }

    private static final Answer LEAVE = new Answer(Verdict.LEAVE, 0);
    private static final Answer STEP = new Answer(Verdict.STEP, 0);

    private String atLastStep;
    private long lastStepAt;
    private boolean lastStepActed;
    private long lastActionAt;
    private long windowChangedAt;
    private long lastEventAt;
    private String seen;
    private int peeks;

    /** Forgets everything; for START. */
    public void reset() {
        atLastStep = null;
        lastStepAt = 0;
        lastStepActed = false;
        lastActionAt = 0;
        windowChangedAt = 0;
        lastEventAt = 0;
        seen = null;
        peeks = 0;
    }

    /**
     * The bot has just taken an ordinary look.
     *
     * @param signature    what the screen read as when the look began (see {@link #differentPage})
     * @param acted        the look pressed, scrolled or typed something
     * @param lastActionAt when the bot last did so
     */
    public void stepped(long now, String signature, boolean acted, long lastActionAt) {
        atLastStep = signature == null ? "" : signature;
        lastStepAt = now;
        lastStepActed = acted;
        this.lastActionAt = lastActionAt;
        seen = null;
        peeks = 0;
    }

    /**
     * Something on the screen changed.
     *
     * @param windowOpened a page or dialog opened (as against something changing on a page)
     * @return how long to wait before peeking
     */
    public long changed(long now, boolean windowOpened) {
        lastEventAt = now;
        if (windowOpened) windowChangedAt = now;
        return SETTLE_MS;
    }

    /**
     * A peek: the screen has been read.
     *
     * @param signature      what it reads as now; null or empty when the app is not on screen
     * @param animationScale the phone's window animation scale (1 normally, 0 when off)
     * @param actedAt        when the bot last pressed anything, as of now
     */
    public Answer peek(long now, String signature, float animationScale, long actedAt) {
        if (actedAt > lastActionAt) lastActionAt = actedAt;
        if (atLastStep == null || atLastStep.isEmpty() || signature == null || signature.isEmpty()) return LEAVE;
        if (!differentPage(atLastStep, signature)) {
            // The page the bot already saw. Its own timers look after it.
            seen = null;
            return LEAVE;
        }
        if (++peeks > MAX_PEEKS) return LEAVE;
        if (!signature.equals(seen)) {
            // Still changing: read it again in a moment.
            seen = signature;
            return new Answer(Verdict.PEEK, SETTLE_MS);
        }
        float scale = Float.isNaN(animationScale) ? 1f : Math.max(0f, Math.min(animationScale, 10f));
        long earliest = lastEventAt + SETTLE_MS;
        earliest = Math.max(earliest, lastActionAt + AFTER_ACTION_MS);
        earliest = Math.max(earliest, lastStepAt + (lastStepActed ? AFTER_ACTION_MS : BETWEEN_IDLE_MS));
        if (windowChangedAt > 0) {
            earliest = Math.max(earliest, windowChangedAt + Math.round(WINDOW_ANIMATION_MS * scale));
        }
        // Only while the bot's own pause after the last change is still running (see above).
        long ceiling = lastEventAt + PAUSE_MS;
        if (earliest >= ceiling || now >= ceiling) return LEAVE;
        if (now >= earliest) {
            seen = null;
            return STEP;
        }
        return new Answer(Verdict.PEEK, Math.max(10, earliest - now));
    }

    /**
     * Is {@code now} another page than {@code before}?
     *
     * <p>A signature is the window's number on its first line, then one line per thing on
     * the screen: its words, {@link #WORDS_END}, and anything else (state, place). Another
     * window is another page. In the same window, it is another page when fewer than
     * {@link #SAME_PAGE_PERCENT} in a hundred of the words are shared - counted against the
     * screen with more words, so a page that only gained or lost a little is the same page.
     * Two screens without any words cannot be told apart this way and count as the same.</p>
     */
    public static boolean differentPage(String before, String now) {
        if (before == null || now == null || before.isEmpty() || now.isEmpty()) return false;
        if (before.equals(now)) return false;
        if (!window(before).equals(window(now))) return true;
        Set<String> was = words(before), is = words(now);
        int most = Math.max(was.size(), is.size());
        if (most == 0) return false;
        int shared = 0;
        for (String word : is) {
            if (was.contains(word)) shared++;
        }
        return shared * 100 < most * SAME_PAGE_PERCENT;
    }

    private static String window(String signature) {
        int end = signature.indexOf(NODE);
        return end < 0 ? signature : signature.substring(0, end);
    }

    private static Set<String> words(String signature) {
        Set<String> out = new HashSet<>();
        int from = signature.indexOf(NODE);
        while (from >= 0 && from < signature.length()) {
            int end = signature.indexOf(NODE, from + 1);
            if (end < 0) end = signature.length();
            int cut = signature.indexOf(WORDS_END, from + 1);
            String word = (cut < 0 || cut > end ? signature.substring(from + 1, end)
                    : signature.substring(from + 1, cut)).trim();
            if (!word.isEmpty()) out.add(word);
            from = end;
        }
        return out;
    }
}
