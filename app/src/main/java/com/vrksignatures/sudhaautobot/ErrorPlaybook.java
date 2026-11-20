package com.vrksignatures.sudhaautobot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The place where problems found later get written down.
 *
 * <p><b>This is deliberately empty.</b> The run logic was left exactly as it is; this class
 * only gives a future fix somewhere to live, in two steps:</p>
 * <ol>
 *   <li><b>Name it.</b> Add an {@link Entry} to {@link #ENTRIES} with words that appear in the
 *       message the panel shows when the run stops. From then on the stop scene shows the
 *       entry's plain-language title and advice instead of the raw message. That is display
 *       only and cannot change the run.</li>
 *   <li><b>Handle it.</b> When a problem needs the bot to behave differently, the change
 *       belongs in {@code SudhaAccessibilityService} next to the screen it concerns, with a
 *       self-test in {@code tools/selftest}; link it from the entry's {@code note} so the
 *       two stay together.</li>
 * </ol>
 *
 * <p>Nothing in the automation reads this class.</p>
 */
public final class ErrorPlaybook {

    /** One known problem. */
    public static final class Entry {
        /** Lower-case words that must all appear in the stop message. */
        public final String[] whenMessageHas;
        /** Short name for the problem, shown as the line under the headline. */
        public final String title;
        /** What the person at the phone should do. */
        public final String advice;
        /** Where the handling lives (method, pull request), for whoever maintains this. */
        public final String note;

        public Entry(String title, String advice, String note, String... whenMessageHas) {
            this.title = title;
            this.advice = advice;
            this.note = note;
            this.whenMessageHas = whenMessageHas == null ? new String[0] : whenMessageHas;
        }

        boolean matches(String messageLower) {
            if (whenMessageHas.length == 0) return false;
            for (String word : whenMessageHas) {
                if (word == null || !messageLower.contains(word.toLowerCase(Locale.ROOT))) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Known problems, most specific first. Empty on purpose - add entries here as they are
     * reported. Example of the shape:
     *
     * <pre>
     * new Entry("The app asked to log in again",
     *           "Log in to Sudha Daily Orders, then press START.",
     *           "handled in onLoginScreen(), PR #NN",
     *           "login", "session")
     * </pre>
     */
    private static final List<Entry> ENTRIES = Collections.unmodifiableList(new ArrayList<Entry>());

    private ErrorPlaybook() {
    }

    /** The entry for a stop message, or null when the problem has not been named yet. */
    public static Entry lookup(String stopMessage) {
        if (stopMessage == null) return null;
        String lower = stopMessage.toLowerCase(Locale.ROOT);
        for (Entry entry : ENTRIES) {
            if (entry.matches(lower)) return entry;
        }
        return null;
    }

    public static int size() {
        return ENTRIES.size();
    }
}
