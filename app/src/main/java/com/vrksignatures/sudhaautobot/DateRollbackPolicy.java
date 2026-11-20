package com.vrksignatures.sudhaautobot;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The strict T-1 .. T-5 rule.
 *
 * <p><b>T is the date the calendar itself has highlighted when it opens</b> (the orange
 * disc), read from the dialog by {@link CalendarDateSelector#highlightedDate}. It is not
 * the phone's clock: this class never asks what today's date is, and refuses to work
 * without being told T. If the calendar opens on the 20th, the dates tried are the 19th,
 * 18th, 17th, 16th and 15th, in that order, whatever the phone believes the date to be.</p>
 *
 * <p>The app itself reports whether a date has data: a popup appears when it does not.
 * That makes the date the only thing the bot has to choose, so it simply walks
 * backwards one day at a time and stops at the first date that loads without an error.</p>
 *
 * <p>The window is deliberately short. It never jumps a week or more, and the calendar
 * month is only ever stepped back by one (which can only be needed in the first days of
 * a month), so a stale or far-past order can never be copied.</p>
 */
public final class DateRollbackPolicy {
    /**
     * How many consecutive days back the bot may try. Was 3; the operator's rule is to
     * try up to five consecutive days before giving up on a retailer.
     */
    public static final int MAX_LOOK_BACK_DAYS = 5;

    private DateRollbackPolicy() {
    }

    /**
     * Ordered candidates: T-1 through T-5 (never T itself, never a week ago).
     *
     * @param today T: the date the calendar had highlighted when it opened
     */
    public static List<LocalDate> candidates(LocalDate today) {
        if (today == null) {
            throw new IllegalArgumentException(
                    "T must be read from the calendar; the phone's date is never used");
        }
        LocalDate base = today;
        List<LocalDate> dates = new ArrayList<>(MAX_LOOK_BACK_DAYS);
        for (int offset = 1; offset <= MAX_LOOK_BACK_DAYS; offset++) {
            LocalDate candidate = base.minusDays(offset);
            // The offset starts at 1, so today can never be a candidate. Asserted rather
            // than assumed: ordering for the present day is the one mistake that must not
            // happen, and a future edit to the loop bound would otherwise reintroduce it.
            if (!candidate.isBefore(base)) {
                throw new IllegalStateException("candidate must be in the past: " + candidate);
            }
            dates.add(candidate);
        }
        return Collections.unmodifiableList(dates);
    }

    /**
     * Candidate for a zero-based attempt index.
     *
     * @throws IllegalArgumentException when the attempt is outside T-1..T-5
     */
    public static LocalDate candidateForAttempt(LocalDate today, int attemptIndex) {
        if (attemptIndex < 0 || attemptIndex >= MAX_LOOK_BACK_DAYS) {
            throw new IllegalArgumentException("attempt index must be 0.."
                    + (MAX_LOOK_BACK_DAYS - 1) + ", was " + attemptIndex);
        }
        return candidates(today).get(attemptIndex);
    }

    /** True when {@code date} is inside the allowed window. */
    public static boolean isWithinWindow(LocalDate today, LocalDate date) {
        if (date == null || today == null) return false;
        for (LocalDate allowed : candidates(today)) {
            if (allowed.equals(date)) return true;
        }
        return false;
    }

    /** Same window as {@link #candidates} but as a plain array for hot paths. */
    public static LocalDate[] candidateArray(LocalDate today) {
        List<LocalDate> list = candidates(today);
        return list.toArray(new LocalDate[0]);
    }

    /** How many attempts are still available after {@code attemptsMade} failures. */
    public static int remainingAttempts(int attemptsMade) {
        return Math.max(0, MAX_LOOK_BACK_DAYS - attemptsMade);
    }

    /** Human readable label for logs and the floating panel. */
    public static String label(int attemptIndex) {
        return "T-" + (attemptIndex + 1);
    }
}
