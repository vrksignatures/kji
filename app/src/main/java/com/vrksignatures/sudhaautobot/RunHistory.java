package com.vrksignatures.sudhaautobot;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The day's history, kept in {@code SharedPreferences} ("run_history"). It belongs to one
 * calendar date: when the date changes - at midnight, however few hours have passed - the
 * whole file is wiped.
 *
 * <pre>
 *   last_done_id / last_done_at / last_done_day   the retailer the bot handled last, when, on which day
 *   start_id     / start_at     / start_day       the id typed in the panel's "Last done ID" box
 *   before_reset_id / before_reset_day            what last_done_id was when Reset was pressed (a hint only)
 *   scroll_id / scroll_count / scroll_at / scroll_day   how many scrolls down the list that retailer is (ScrollMemory)
 *
 *   history_day                                    the calendar date all of the above belong to
 * </pre>
 *
 * <p>Every read goes through {@link #expire}: when {@code history_day} is not today's date
 * everything is deleted at once, and an entry whose own day is not today
 * ({@link StartPoint#fresh}) is deleted too. There is no count of hours anywhere. The
 * service also calls {@link #expire} when the phone says the date has changed, so the
 * wipe happens at midnight and not only at the next look.</p>
 *
 * <p>The day's ledger stays the bot's memory of what has been ordered. This file only tells
 * it where to carry on when the ledger cannot: after Reset, or when a number is typed.</p>
 */
final class RunHistory {

    static final String FILE = "run_history";
    private static final String LAST_ID = "last_done_id";
    private static final String LAST_AT = "last_done_at";
    private static final String LAST_DAY = "last_done_day";
    private static final String START_ID = "start_id";
    private static final String START_AT = "start_at";
    private static final String START_DAY = "start_day";
    private static final String BEFORE_RESET = "before_reset_id";
    private static final String BEFORE_RESET_DAY = "before_reset_day";

    /** The calendar date the whole file belongs to. */
    private static final String HISTORY_DAY = "history_day";

    private static final String SCROLL_ID = "scroll_id";
    private static final String SCROLL_COUNT = "scroll_count";
    private static final String SCROLL_AT = "scroll_at";
    private static final String SCROLL_DAY = "scroll_day";

    /** The id last written, so that the panel's many refreshes do not each touch the disk. */
    private static String noted;

    private RunHistory() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    // ------------------------------------------------------------- the history

    /** Called whenever the panel is refreshed: remembers the retailer handled last. */
    static void note(Context context, DailyLedger ledger) {
        if (ledger == null) return;
        // A ledger left over from yesterday (the service stays alive overnight; START
        // makes a new one) says nothing about today: nothing of it enters today's history.
        if (!StartPoint.sameDay(String.valueOf(ledger.sessionDate()), today())) return;
        String last = ledger.lastHandledId();
        if (last == null || last.equals(noted)) return;
        noted = last;
        prefs(context).edit()
                .putString(LAST_ID, last)
                .putLong(LAST_AT, System.currentTimeMillis())
                .putString(LAST_DAY, today())
                .remove(BEFORE_RESET).remove(BEFORE_RESET_DAY)
                .apply();
    }

    /**
     * The calendar-day rule. If the date has changed since the history was last touched,
     * all of it goes; whatever single entry is not from today goes too. Returns true when
     * something was deleted.
     */
    static boolean expire(Context context) {
        SharedPreferences prefs = prefs(context);
        long now = System.currentTimeMillis();
        String today = today();
        String held = prefs.getString(HISTORY_DAY, null);
        if (StartPoint.dayTurned(held, today)) {
            // Midnight has passed: a clean sheet.
            boolean had = prefs.getAll().size() > 1;
            prefs.edit().clear().putString(HISTORY_DAY, today).apply();
            noted = null;
            return had;
        }
        SharedPreferences.Editor edit = null;
        if (held == null) edit = prefs.edit().putString(HISTORY_DAY, today);
        boolean deleted = false;
        if (prefs.contains(LAST_ID)
                && !StartPoint.fresh(prefs.getLong(LAST_AT, 0), prefs.getString(LAST_DAY, null), now, today)) {
            edit = (edit == null ? prefs.edit() : edit).remove(LAST_ID).remove(LAST_AT).remove(LAST_DAY);
            noted = null;
            deleted = true;
        }
        if (prefs.contains(START_ID)
                && !StartPoint.fresh(prefs.getLong(START_AT, 0), prefs.getString(START_DAY, null), now, today)) {
            edit = (edit == null ? prefs.edit() : edit).remove(START_ID).remove(START_AT).remove(START_DAY);
            deleted = true;
        }
        if (prefs.contains(SCROLL_ID)
                && !StartPoint.fresh(prefs.getLong(SCROLL_AT, 0), prefs.getString(SCROLL_DAY, null), now, today)) {
            edit = (edit == null ? prefs.edit() : edit)
                    .remove(SCROLL_ID).remove(SCROLL_COUNT).remove(SCROLL_AT).remove(SCROLL_DAY);
            deleted = true;
        }
        if (edit != null) edit.apply();
        return deleted;
    }

    // -------------------------------------------------------- the scroll count

    /**
     * Remembers how many scrolls down the list this retailer was when it was opened. A
     * count that cannot be used (see {@link ScrollMemory#usable}) clears the memory.
     */
    static void noteScroll(Context context, String id, int count) {
        SharedPreferences.Editor edit = prefs(context).edit();
        if (id == null || !ScrollMemory.usable(count)) {
            edit.remove(SCROLL_ID).remove(SCROLL_COUNT).remove(SCROLL_AT).remove(SCROLL_DAY);
        } else {
            edit.putString(SCROLL_ID, id).putInt(SCROLL_COUNT, count)
                    .putLong(SCROLL_AT, System.currentTimeMillis()).putString(SCROLL_DAY, today());
        }
        edit.apply();
    }

    /** The scrolls it took to reach this retailer, if that was noted today; else 0. */
    static int scrollCount(Context context, String id) {
        if (id == null) return 0;
        expire(context);
        SharedPreferences prefs = prefs(context);
        if (!id.equals(prefs.getString(SCROLL_ID, null))) return 0;
        int count = prefs.getInt(SCROLL_COUNT, 0);
        return ScrollMemory.usable(count) ? count : 0;
    }

    /** The retailer handled last, if that was today; else null. */
    static String lastDone(Context context) {
        expire(context);
        return StartPoint.normalise(prefs(context).getString(LAST_ID, null));
    }

    static long lastDoneAt(Context context) {
        return prefs(context).getLong(LAST_AT, 0);
    }

    /** What the history held when Reset was pressed today; a hint for the box, never used on its own. */
    static String beforeReset(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!today().equals(prefs.getString(BEFORE_RESET_DAY, null))) return null;
        return StartPoint.normalise(prefs.getString(BEFORE_RESET, null));
    }

    // ---------------------------------------------------------------- the box

    /** The id typed in the panel's box and not yet reached; null when there is none. */
    static String typed(Context context) {
        expire(context);
        return StartPoint.normalise(prefs(context).getString(START_ID, null));
    }

    /** Stores what was typed. Null or an invalid id clears it. Returns the id that was stored. */
    static String setTyped(Context context, String typed) {
        String id = StartPoint.normalise(typed);
        SharedPreferences.Editor edit = prefs(context).edit();
        if (id == null) {
            edit.remove(START_ID).remove(START_AT).remove(START_DAY);
        } else {
            edit.putString(START_ID, id).putLong(START_AT, System.currentTimeMillis())
                    .putString(START_DAY, today());
        }
        edit.apply();
        return id;
    }

    /** Reset: the history is forgotten. The last id is kept only as a hint for the box. */
    static void forget(Context context) {
        String last = lastDone(context);
        SharedPreferences.Editor edit = prefs(context).edit().clear();
        if (last != null) edit.putString(BEFORE_RESET, last).putString(BEFORE_RESET_DAY, today());
        edit.apply();
        noted = null;
    }

    // ------------------------------------------------------------ for the bot

    /**
     * START was pressed. If a number is waiting in the box - or the ledger has nothing at
     * all while the history still knows today's place - the ledger is told to carry on
     * after that retailer, and a {@link StartPoint.Seeker} is returned to follow the bot
     * down to it. Otherwise nothing is touched.
     */
    static StartPoint.Seeker atStart(Context context, DailyLedger ledger, StartPoint.Seeker current) {
        expire(context);
        // An order in flight is settled first; the box waits.
        if (ledger == null || ledger.hasPending()) return current;
        String id = typed(context);
        String why = "typed on the panel";
        if (id == null && ledger.lastHandledId() == null && ledger.processedCount() == 0) {
            id = lastDone(context);
            why = "remembered " + StartPoint.age(lastDoneAt(context), System.currentTimeMillis());
        }
        if (id == null) {
            return current != null && !current.finished() && current.id.equals(ledger.lastHandledId())
                    ? current : null;
        }
        if (current != null && !current.finished() && current.id.equals(id)
                && id.equals(ledger.lastHandledId())) {
            return current;
        }
        if (!ledger.setLastHandled(id)) return null;
        return new StartPoint.Seeker(id, why);
    }

    /** The retailer list is on screen: lets the seeker look at it. Returns null once it is done. */
    static StartPoint.Seeker sees(Context context, DailyLedger ledger, StartPoint.Seeker seeker,
                                  List<String> idsTopToBottom) {
        if (seeker == null || ledger == null) return null;
        StartPoint.Seeker.Outcome outcome = seeker.sees(idsTopToBottom, new StartPoint.Book() {
            @Override
            public boolean isProcessed(String id) {
                return ledger.isProcessed(id);
            }

            @Override
            public String lastHandled() {
                return ledger.lastHandledId();
            }

            @Override
            public boolean markDoneBefore(String id, String why) {
                return ledger.recordDoneBefore(id, why);
            }
        });
        if (outcome == StartPoint.Seeker.Outcome.SEEKING) return seeker;
        // Reached or given up: either way the number in the box has done its job.
        if (seeker.id.equals(typed(context))) setTyped(context, null);
        return null;
    }
}
