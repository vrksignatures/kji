package com.vrksignatures.sudhaautobot;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds and taps one exact day inside the Copy Order calendar dialog.
 *
 * <p>The dialog is a third-party widget, so it is matched by behaviour rather than
 * by resource id: it contains a month header plus day cells. Supported layouts:</p>
 * <ul>
 *   <li>Custom grids whose cells expose {@code contentDescription} like
 *       "16 November 2025" or "16 Nov 2025".</li>
 *   <li>The stock Android {@code DatePickerDialog} — which is what this app opens. Its
 *       month title and weekday letters are painted, not nodes; each day is a virtual node
 *       with text "16" <b>and</b> contentDescription "16 November 2026".</li>
 *   <li>Grids whose cells are bare numbers such as "16" under a month header node.</li>
 * </ul>
 *
 * <p><b>Only what is on screen counts.</b> The stock dialog keeps its grid in a pager, and
 * a pager keeps the neighbouring month attached off-screen. Those nodes are still in the
 * tree (only {@code isVisibleToUser()} is false) and, being ordered by position, the
 * previous month comes <i>first</i>. Reading "the first month in the tree" therefore
 * returned October while November was on screen, the selector concluded the wrong month
 * was showing, and it refused to tap anything. Every read below goes through
 * {@link #walkOnScreen}, which ignores whatever an ancestor has scrolled out of view.</p>
 *
 * <p>Hard rules enforced here:</p>
 * <ul>
 *   <li>Only a day that actually belongs to the target date can be tapped; a month
 *       header mismatch means "not found" instead of "tap the 16th of some month".</li>
 *   <li>Months are never skipped and the year never changes: the previous-month
 *       arrow is used at most once (which can only happen for T-1..T-3 in the first
 *       days of a month).</li>
 *   <li>Ambiguous matches (two identically labelled cells) are rejected.</li>
 * </ul>
 */
public final class CalendarDateSelector {
    private static final int MAX_DEPTH = 60;
    /**
     * How many month headers the calendar may be stepped back in one selection.
     *
     * <p>Dates are only ever T-1..T-5, so more than two is already unexpected; the cap exists
     * to stop a broken header read from walking the calendar into last year.</p>
     */
    private static final int MAX_MONTHS_BACK = 2;

    /** Result of one selection attempt. */
    public enum Outcome {
        /** Day cell for the target date was tapped. */
        TAPPED,
        /** Exactly the target date's cell was already selected. */
        ALREADY_SELECTED,
        /** Target is in the previous month; that arrow was tapped, caller must retry. */
        STEPPED_BACK_MONTH,
        /** Calendar shows a month outside the allowed window (never navigated). */
        MONTH_NOT_AVAILABLE,
        /** Calendar visible but the day cell was not found. */
        DAY_NOT_FOUND,
        /** Screen is not a calendar at all. */
        NOT_A_CALENDAR
    }

    private static final Pattern MONTH_YEAR = Pattern.compile(
            "(?i)\\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|"
                    + "aug(?:ust)?|sep(?:t|tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\s*,?\\s*(20\\d{2})\\b");

    private static final Pattern DAY_MONTH_YEAR = Pattern.compile(
            "(?i)\\b(\\d{1,2})\\s+([a-z]{3,9}),?\\s+(20\\d{2})\\b");

    private static final Pattern MONTH_DAY_YEAR = Pattern.compile(
            "(?i)\\b([a-z]{3,9})\\s+(\\d{1,2}),?\\s+(20\\d{2})\\b");

    private static final Pattern ISO = Pattern.compile("\\b(20\\d{2})-(\\d{2})-(\\d{2})\\b");
    private static final Pattern DMY = Pattern.compile("\\b(\\d{1,2})[/-](\\d{1,2})[/-](20\\d{2})\\b");
    /** The stock dialog's headline: "Wed, 18 Nov" (or "Wed, Nov 18"). */
    private static final Pattern HEADLINE_DATE = Pattern.compile(
            "\\b(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)[a-z]*,\\s*(?:\\d{1,2}\\s+[A-Z][a-z]{2,8}|[A-Z][a-z]{2,8}\\s+\\d{1,2})\\b");
    private static final Pattern BARE_DAY = Pattern.compile("^\\s*(\\d{1,2})\\s*$");

    private CalendarDateSelector() {
    }

    // ---------------------------------------------------------- classification

    /**
     * True when the screen shows the Copy Order date dialog.
     *
     * <p>Deliberately generous, because a missed calendar is a <b>destructive</b> mistake:
     * if this returns false, the caller treats the dialog as an error popup and taps its
     * confirm button — and in this calendar the confirm button <b>commits the present
     * date</b>, which is still selected. That is exactly how the bot ended up clicking
     * today on every ladder step.</p>
     *
     * <p>Accepted evidence, any one of which is enough:</p>
     * <ul>
     *   <li>a month-and-year header such as "November 2026" — conclusive on its own,
     *       because the order form behind it shows "18-November-2026", not a bare header;</li>
     *   <li>an explicit date-picker phrase plus a day cell;</li>
     *   <li>a full day grid together with this dialog's CANCEL/OK footer;</li>
     *   <li>the dialog's headline ("Wed, 18 Nov") together with the CANCEL/OK footer. This
     *       covers a grid that puts no day cells in the tree at all: without it such a
     *       dialog was not recognised, and the bot sat in front of it doing nothing. Both
     *       parts are required - an error popup has no CANCEL and no headline, and the
     *       order form writes its date as "18-November-2026", without a weekday.</li>
     * </ul>
     *
     * <p>The old version demanded <b>20</b> readable day cells, which a custom-drawn
     * calendar never provides — only the highlighted day carries text.</p>
     */
    public static boolean isCalendar(AccessibilityNodeInfo root) {
        if (root == null) return false;
        String text = AccessibilityTree.allText(root);
        String lower = text.toLowerCase(Locale.ROOT);

        boolean hasHeader = MONTH_YEAR.matcher(text).find();
        int days = visibleDayCells(root).size();
        boolean hasFooter = hasCancelButton(root) && hasConfirmButton(root);
        boolean mentionsDateUi = lower.contains("select date")
                || lower.contains("choose date")
                || lower.contains("select delivery date")
                || lower.contains("copy order date")
                || lower.contains("calendar")
                || lower.contains("today");

        if (mentionsDateUi && (hasHeader || days >= 1)) return true;
        // A month-and-year header alone is conclusive for this app: the order form shows
        // a plain "18-November-2026" date, never a "November 2026" header, so a bare
        // month+year can only come from the calendar.
        if (hasHeader) return true;
        // A bare day grid with this dialog's footer is still recognisably the calendar.
        if (days >= 10 && hasFooter) return true;
        // So is the dialog's headline with its footer, even when no day cell is exposed.
        return hasFooter && HEADLINE_DATE.matcher(text).find();
    }

    /**
     * True when this window contains the calendar footer's CANCEL label.
     *
     * <p>Used only as calendar evidence, never to decide anything is an error.</p>
     */
    private static boolean hasCancelButton(AccessibilityNodeInfo root) {
        // Visibility-filtered for the same reason the OK tap is: an off-screen node at
        // stale coordinates must never count as evidence, nor be tapped.
        for (String label : new String[]{"CANCEL", "Cancel", "cancel"}) {
            if (findVisibleExact(root, label, 0) != null) return true;
        }
        return false;
    }

    /**
     * Dismisses the date dialog <b>without</b> committing anything, by tapping CANCEL.
     *
     * <p>Needed when the bot cannot find the target day cell. The alternative — tapping
     * OK — commits whatever day the dialog currently has selected, and it opens with the
     * present day selected, so OK must never be used to escape this dialog. CANCEL simply
     * closes it, leaving the order form untouched.</p>
     *
     * @return true when a cancel control was tapped
     */
    public static boolean cancel(AccessibilityNodeInfo root) {
        if (root == null) return false;
        for (String label : new String[]{"CANCEL", "Cancel", "cancel"}) {
            AccessibilityNodeInfo node = findVisibleExact(root, label, 0);
            if (node == null) continue;
            if (AccessibilityTree.click(node, null)) return true;
            if (AccessibilityTree.tapBounds(node, null)) return true;
        }
        return false;
    }

    // ------------------------------------------------------- on-screen walking

    /** Receives every node that is really on screen. */
    private interface OnScreenVisitor {
        void visit(AccessibilityNodeInfo node, Rect bounds);
    }

    /**
     * Visits, top-down, only the nodes a person can actually see.
     *
     * <p>Two independent tests, because either one alone has let stale nodes through:</p>
     * <ul>
     *   <li>the platform's own {@code isVisibleToUser()};</li>
     *   <li>geometry — a node whose centre lies outside any ancestor's bounds has been
     *       scrolled away by that ancestor (an off-screen pager page), and a subtree that
     *       does not intersect its ancestor at all is skipped entirely.</li>
     * </ul>
     *
     * @param clip the part of the screen the ancestors leave visible, or null at the root
     */
    private static void walkOnScreen(AccessibilityNodeInfo node, Rect clip, int depth,
                                     OnScreenVisitor visitor) {
        if (node == null || depth > MAX_DEPTH) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        Rect childClip = clip;
        if (bounds.width() > 0 && bounds.height() > 0) {
            Rect visible = new Rect(bounds);
            if (clip != null && !visible.intersect(clip)) {
                // Entirely outside an ancestor: nothing below it can be on screen either.
                return;
            }
            boolean centreShown = clip == null
                    || clip.contains(bounds.centerX(), bounds.centerY());
            if (centreShown && node.isVisibleToUser()) visitor.visit(node, bounds);
            childClip = visible;
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            walkOnScreen(node.getChild(i), childClip, depth + 1, visitor);
        }
    }

    /**
     * Month/year the calendar is showing <b>on screen</b>, or null when none is readable.
     *
     * <p>Every on-screen node that names a month and year casts one vote and the majority
     * wins. In the stock dialog the thirty visible day cells all say "November 2026", so
     * neither the off-screen neighbour month nor a stray label can outvote them.</p>
     */
    public static YearMonth visibleMonth(AccessibilityNodeInfo root) {
        if (root == null) return null;
        final Map<YearMonth, Integer> votes = new LinkedHashMap<>();
        walkOnScreen(root, null, 0, (node, bounds) -> {
            Matcher matcher = MONTH_YEAR.matcher(AccessibilityTree.textOf(node));
            if (!matcher.find()) return;
            Month month = monthFromName(matcher.group(1));
            if (month == null) return;
            YearMonth candidate = YearMonth.of(Integer.parseInt(matcher.group(2)), month);
            Integer seen = votes.get(candidate);
            votes.put(candidate, seen == null ? 1 : seen + 1);
        });
        YearMonth best = null;
        int bestVotes = 0;
        for (Map.Entry<YearMonth, Integer> entry : votes.entrySet()) {
            if (entry.getValue() > bestVotes) {
                best = entry.getKey();
                bestVotes = entry.getValue();
            }
        }
        return best;
    }

    /**
     * The day the calendar currently has selected, read from the on-screen cells only;
     * null when the widget does not expose a selected state.
     */
    public static LocalDate selectedDate(AccessibilityNodeInfo root) {
        if (root == null) return null;
        final LocalDate[] full = new LocalDate[1];
        final Integer[] bareDay = new Integer[1];
        walkOnScreen(root, null, 0, (node, bounds) -> {
            if (!isMarkedSelected(node)) return;
            String label = AccessibilityTree.textOf(node);
            LocalDate parsed = parseDateFromLabel(label);
            if (parsed != null) {
                if (full[0] == null) full[0] = parsed;
                return;
            }
            Matcher bare = BARE_DAY.matcher(
                    label.replaceAll("(?i)\\b(selected|today)\\b", "").trim());
            if (bare.matches() && bareDay[0] == null) bareDay[0] = Integer.parseInt(bare.group(1));
        });
        if (full[0] != null) return full[0];
        YearMonth month = visibleMonth(root);
        Integer day = bareDay[0];
        if (month != null && day != null && day >= 1 && day <= month.lengthOfMonth()) {
            return month.atDay(day);
        }
        return null;
    }

    /** "Wed, 18 Nov" or "Wed, Nov 18": the dialog's headline, with the day and month captured. */
    private static final Pattern HEADLINE_PARTS = Pattern.compile(
            "\\b(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)[a-z]*,\\s*"
                    + "(?:(\\d{1,2})\\s+([A-Z][a-z]{2,8})|([A-Z][a-z]{2,8})\\s+(\\d{1,2}))\\b");
    private static final Pattern BARE_YEAR = Pattern.compile("^\\s*(20\\d{2})\\s*$");

    /**
     * The date the dialog has highlighted <b>right now</b> - the orange disc.
     *
     * <p>This is the bot's only notion of "today". When the dialog first opens for a
     * retailer the highlighted date is T, and the dates the bot may choose are T-1 to T-5
     * counted from it. The phone's own clock is never consulted, so a phone set to the
     * wrong date, or a run that crosses midnight, cannot shift which day is copied.</p>
     *
     * <p>Read from the selected day cell when the grid exposes one, otherwise from the
     * headline ("2026" above "Wed, 18 Nov"), which always shows the selected date. Null
     * when neither can be read; the caller then looks at the screen itself.</p>
     */
    public static LocalDate highlightedDate(AccessibilityNodeInfo root) {
        LocalDate cell = selectedDate(root);
        if (cell != null) return cell;
        return headlineDate(root);
    }

    /**
     * The date written in the dialog's headline, or null. The year comes from the year
     * line above the headline, or failing that from a month title naming the same month.
     */
    public static LocalDate headlineDate(AccessibilityNodeInfo root) {
        if (root == null) return null;
        final int[] dayMonth = new int[2];          // day, month number
        final java.util.Set<Integer> years = new java.util.LinkedHashSet<>();
        final boolean[] clash = new boolean[1];
        walkOnScreen(root, null, 0, (node, bounds) -> {
            String label = AccessibilityTree.textOf(node);
            if (label == null || label.isEmpty()) return;
            Matcher year = BARE_YEAR.matcher(label);
            if (year.matches()) {
                years.add(Integer.parseInt(year.group(1)));
                return;
            }
            // A complete date ("17 November 2026") is a day cell, not the headline.
            if (parseDateFromLabel(label) != null) return;
            Matcher headline = HEADLINE_PARTS.matcher(label);
            if (!headline.find()) return;
            String dayText = headline.group(1) != null ? headline.group(1) : headline.group(4);
            String monthText = headline.group(2) != null ? headline.group(2) : headline.group(3);
            Month month = monthFromName(monthText);
            if (month == null) return;
            int day = Integer.parseInt(dayText);
            if (dayMonth[0] != 0 && (dayMonth[0] != day || dayMonth[1] != month.getValue())) {
                clash[0] = true;                    // two different headlines: refuse to guess
            }
            dayMonth[0] = day;
            dayMonth[1] = month.getValue();
        });
        if (clash[0] || dayMonth[0] == 0) return null;
        Integer year = null;
        if (years.size() == 1) {
            year = years.iterator().next();
        } else if (years.isEmpty()) {
            YearMonth shown = visibleMonth(root);
            if (shown != null && shown.getMonthValue() == dayMonth[1]) year = shown.getYear();
        }
        if (year == null) return null;
        return parseSafe(year, dayMonth[1], dayMonth[0]);
    }

    private static boolean isMarkedSelected(AccessibilityNodeInfo node) {
        return node.isSelected() || node.isChecked();
    }

    // -------------------------------------------------------------- selection

    /**
     * Taps {@code target} in the visible calendar.
     *
     * <p>Never changes the month unless the target sits in the immediately preceding
     * month (only possible for T-1..T-5 at the start of a month), and never beyond
     * that one step. On {@link Outcome#STEPPED_BACK_MONTH} the caller should wait for
     * the UI and call again.</p>
     *
     * @param today    the date the dialog opened on (see {@link #highlightedDate}). It and
     *                 every later date may never be selected. This is <b>not</b> the
     *                 phone's clock: the caller reads it from the dialog itself
     * @param physical true to press the cell with a real touch instead of an
     *                 accessibility click — used when a first attempt reported success
     *                 but the highlight did not move
     */
    public static Outcome selectDate(AccessibilityNodeInfo root, LocalDate target,
                                     AccessibilityTree.Gestures gestures,
                                     LocalDate today, boolean physical) {
        if (root == null || target == null) return Outcome.NOT_A_CALENDAR;
        if (!isCalendar(root)) return Outcome.NOT_A_CALENDAR;
        // Hard safety rule: the bot never orders for the day the dialog opened on (the
        // present day, as the app sees it) or a later one.
        if (today != null && !target.isBefore(today)) return Outcome.DAY_NOT_FOUND;

        // NOTE: an already-*highlighted* cell is deliberately NOT treated as "target
        // chosen". This dialog opens with the present day highlighted, so trusting the
        // highlight made a T-1 request return ALREADY_SELECTED without tapping anything —
        // the bot then confirmed today's date, which is never allowed. The day is always
        // tapped explicitly below, so the requested date is the one that ends up selected.

        // 1. A cell that is on screen and names the complete target date IS the target.
        //    Nothing else needs to agree: the label carries day, month and year, so it
        //    cannot be the 17th of some other month. This is the stock dialog's path, and
        //    it must come before any header reading — the header there is painted, and the
        //    only month text in the tree belongs to the day cells themselves.
        List<AccessibilityNodeInfo> matches = onScreenCellsFor(root, target);
        if (matches.size() == 1) {
            return tapCell(matches.get(0), gestures, physical)
                    ? Outcome.TAPPED : Outcome.DAY_NOT_FOUND;
        }
        if (matches.size() > 1) {
            // Two cells claim the same date: ambiguous, so refuse to guess.
            return Outcome.DAY_NOT_FOUND;
        }

        // 2. The target is not on screen. Either another month is showing, or the cells
        //    carry bare numbers.
        YearMonth shown = visibleMonth(root);
        if (shown != null && !shown.equals(YearMonth.from(target))) {
            // The header is only ever moved one month at a time, and only backwards, so a
            // date a few days away (T-1..T-5 across a month boundary) still resolves while
            // a wild jump is impossible: each call re-reads the header before acting.
            long monthsBack = java.time.temporal.ChronoUnit.MONTHS.between(
                    YearMonth.from(target), shown);
            if (monthsBack < 1 || monthsBack > MAX_MONTHS_BACK) {
                return Outcome.MONTH_NOT_AVAILABLE;
            }
            if (!tapPreviousMonth(root, gestures)) return Outcome.MONTH_NOT_AVAILABLE;
            return Outcome.STEPPED_BACK_MONTH;
        }
        // A bare "17" says nothing about its month, so a readable month that matches is
        // required before one may be tapped.
        if (shown == null) return Outcome.MONTH_NOT_AVAILABLE;

        // 3. Grids whose cells carry only the day number.
        matches = findBareDayCells(root, target.getDayOfMonth(), 0);
        if (matches.isEmpty()) return Outcome.DAY_NOT_FOUND;
        if (matches.size() > 1) {
            // Two cells claim the same date: ambiguous, so refuse to guess.
            return Outcome.DAY_NOT_FOUND;
        }
        return tapCell(matches.get(0), gestures, physical)
                ? Outcome.TAPPED : Outcome.DAY_NOT_FOUND;
    }

    /**
     * Presses one day cell — the cell itself, never an ancestor.
     *
     * <p>{@link AccessibilityTree#click} climbs to the nearest clickable ancestor when a
     * node refuses the click. That is right for a list row and wrong here: the ancestor of
     * a day is the whole month, and "clicking" that reports success while selecting
     * nothing. A day is pressed either by its own accessibility click or by a real touch in
     * the middle of its own bounds, which is exactly what a finger does.</p>
     */
    private static boolean tapCell(AccessibilityNodeInfo cell, AccessibilityTree.Gestures gestures,
                                   boolean physical) {
        if (cell == null) return false;
        if (!physical && cell.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        if (AccessibilityTree.tapBounds(cell, gestures)) return true;
        return physical && cell.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    /**
     * On-screen cells whose label is the complete {@code target} date.
     *
     * <p>A container may echo its child's label, so of two matches where one encloses the
     * other only the inner one — the real cell — is kept.</p>
     */
    private static List<AccessibilityNodeInfo> onScreenCellsFor(AccessibilityNodeInfo root,
                                                                final LocalDate target) {
        final List<AccessibilityNodeInfo> found = new ArrayList<>(2);
        final List<Rect> rects = new ArrayList<>(2);
        walkOnScreen(root, null, 0, (node, bounds) -> {
            if (!target.equals(parseDateFromLabel(AccessibilityTree.textOf(node)))) return;
            // Walk order is parent-first, so an earlier match that encloses this one is
            // the echoing container: replace it.
            for (int i = found.size() - 1; i >= 0; i--) {
                Rect outer = rects.get(i);
                if (outer.left <= bounds.left && outer.top <= bounds.top
                        && outer.right >= bounds.right && outer.bottom >= bounds.bottom) {
                    found.remove(i);
                    rects.remove(i);
                }
            }
            found.add(node);
            rects.add(new Rect(bounds));
        });
        if (found.size() > 1) {
            // A headline that repeats the selected date is not a cell; a cell is clickable.
            List<AccessibilityNodeInfo> clickable = new ArrayList<>(1);
            for (AccessibilityNodeInfo node : found) {
                if (node.isClickable()) clickable.add(node);
            }
            if (clickable.size() == 1) return clickable;
        }
        return found;
    }

    /**
     * Screen bounds of the cell for {@code target} when exactly one is on screen, else
     * null. Lets the caller check the highlight on the pixels when the widget exposes no
     * selected state.
     */
    public static Rect cellBounds(AccessibilityNodeInfo root, LocalDate target) {
        if (root == null || target == null) return null;
        List<AccessibilityNodeInfo> matches = onScreenCellsFor(root, target);
        if (matches.isEmpty() && YearMonth.from(target).equals(visibleMonth(root))) {
            matches = findBareDayCells(root, target.getDayOfMonth(), 0);
        }
        if (matches.size() != 1) return null;
        return boundsOfNode(matches.get(0));
    }

    /**
     * One line saying what the tree offers for {@code target}. Shown on the panel when a
     * day cannot be found, so a failure names its cause instead of just "not found".
     */
    public static String diagnose(AccessibilityNodeInfo root, LocalDate target) {
        if (root == null) return "no window";
        final int[] onScreen = new int[2];   // [0] full-date cells, [1] bare day cells
        walkOnScreen(root, null, 0, (node, bounds) -> {
            String label = AccessibilityTree.textOf(node);
            if (parseDateFromLabel(label) != null) onScreen[0]++;
            else if (BARE_DAY.matcher(label).matches()) onScreen[1]++;
        });
        int all = countDateCells(root, 0);
        YearMonth month = visibleMonth(root);
        LocalDate selected = selectedDate(root);
        return "month " + (month == null ? "unreadable" : month.toString())
                + ", dated cells " + onScreen[0] + " on screen +" + Math.max(0, all - onScreen[0])
                + " off, bare cells " + onScreen[1]
                + ", selected " + (selected == null ? "unreadable" : selected.toString())
                + ", target cells " + (target == null ? 0 : onScreenCellsFor(root, target).size());
    }

    private static int countDateCells(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > MAX_DEPTH) return 0;
        int count = parseDateFromLabel(AccessibilityTree.textOf(node)) != null ? 1 : 0;
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) count += countDateCells(node.getChild(i), depth + 1);
        return count;
    }

    /** Bare-number cells ("16") - only unambiguous when a single one is on screen. */
    private static List<AccessibilityNodeInfo> findBareDayCells(AccessibilityNodeInfo node,
                                                                int day, int depth) {
        List<AccessibilityNodeInfo> out = new ArrayList<>(2);
        Rect band = dialogGridBand(node);
        collectBareDayCells(node, day, band, out, depth);
        if (out.size() > 1) {
            // Adjacent-month filler days can duplicate a day number (e.g. a greyed "1"
            // from December next to the real "1"). Disabled cells are never the target:
            // future and filler days are disabled, past days are enabled.
            List<AccessibilityNodeInfo> enabled = new ArrayList<>(1);
            for (AccessibilityNodeInfo candidate : out) {
                if (candidate.isEnabled()) enabled.add(candidate);
            }
            if (enabled.size() == 1) return enabled;
        }
        return out;
    }

    /**
     * The vertical/horizontal band the day grid occupies: below the month header and above
     * the CANCEL/OK footer.
     *
     * <p>Used only for grids whose cells are bare numbers. Such cells are scoped by
     * <b>geometry</b>, not by tree ancestry, because geometry does not depend on how the
     * widget happens to nest its views. (The stock dialog this app opens never gets here:
     * its cells carry the full date and are matched by {@link #onScreenCellsFor}.)</p>
     *
     * <p>Returns an empty rect when neither header nor footer is readable, in which case
     * no filtering is applied.</p>
     */
    private static Rect dialogGridBand(AccessibilityNodeInfo root) {
        // Vertical only, deliberately. The header text and a single footer button are both
        // narrower than the dialog, so any horizontal bound derived from them would cut
        // off real cells on the other side. Vertically the grid is unambiguous: it sits
        // below the header and above the footer. And while the dialog is the active
        // window, the order form behind it is not in this tree at all.
        Rect band = new Rect();
        Rect header = boundsOfNode(findHeaderNode(root, 0));
        Rect footer = boundsOfNode(findFooterNode(root, 0));
        if (header != null) band.top = header.bottom;
        if (footer != null) band.bottom = footer.top;
        return band;
    }

    /**
     * The month-header node ("November 2026"), or null.
     *
     * <p>Full-date day cells such as "17 November 2026" also contain the month+year, so a
     * node that parses as a complete date is excluded — only the bare header qualifies.</p>
     */
    private static AccessibilityNodeInfo findHeaderNode(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > MAX_DEPTH) return null;
        String label = AccessibilityTree.textOf(node);
        if (MONTH_YEAR.matcher(label).find() && parseDateFromLabel(label) == null) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (node.isVisibleToUser() && bounds.width() > 0 && bounds.height() > 0) {
                return node;
            }
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo found = findHeaderNode(node.getChild(i), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    /** A visible CANCEL or OK footer button, or null. */
    private static AccessibilityNodeInfo findFooterNode(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > MAX_DEPTH) return null;
        String label = AccessibilityTree.textOf(node);
        if (AccessibilityTree.equalsLabel(label, "CANCEL")
                || AccessibilityTree.equalsLabel(label, "Cancel")
                || AccessibilityTree.equalsLabel(label, "OK")
                || AccessibilityTree.equalsLabel(label, "Ok")) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (node.isVisibleToUser() && bounds.width() > 0 && bounds.height() > 0) {
                return node;
            }
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo found = findFooterNode(node.getChild(i), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    /** Bounds of {@code node}, or null when it has no area on screen. */
    private static Rect boundsOfNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        return rect.width() > 0 && rect.height() > 0 ? rect : null;
    }

    private static void collectBareDayCells(AccessibilityNodeInfo node, int day, Rect band,
                                            List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > MAX_DEPTH) return;
        // A bare "17" is ambiguous: the order form behind the dialog can contain one too
        // (in a rate, an address or a quantity). Only cells inside the dialog's grid band
        // (below the header, above the footer) may be tapped.
        Matcher bare = BARE_DAY.matcher(AccessibilityTree.textOf(node));
        if (bare.matches() && Integer.parseInt(bare.group(1)) == day) {
            // Only a cell that is actually laid out can be tapped. An invisible or
            // zero-sized node means the tap would land on its coordinates anyway, which may
            // well be the highlighted present-day cell sitting in the same grid.
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (node.isVisibleToUser() && bounds.width() > 0 && bounds.height() > 0
                    && insideBand(bounds, band)) {
                out.add(node);
            }
            return;
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            collectBareDayCells(node.getChild(i), day, band, out, depth + 1);
        }
    }

    /** True when the cell centre lies inside the grid band (empty band = no filtering). */
    private static boolean insideBand(Rect bounds, Rect band) {
        if (band == null || (band.top == 0 && band.bottom == 0)) return true;
        float cy = bounds.exactCenterY();
        if (band.top != 0 && cy < band.top) return false;
        if (band.bottom != 0 && cy > band.bottom) return false;
        return true;
    }

    /** Taps OK / CONFIRM / SELECT / DONE and reports whether a control was found. */
    public static boolean confirm(AccessibilityNodeInfo root) {
        if (root == null) return false;
        for (String label : new String[]{"OK", "CONFIRM", "SELECT", "DONE", "SET"}) {
            AccessibilityNodeInfo node = findVisibleExact(root, label, 0);
            if (node == null) continue;
            if (AccessibilityTree.click(node, null)) return true;
            // Some dialog buttons expose no clickable ancestor; a gesture on the bounds of
            // the button that is actually on screen still commits the selection.
            if (AccessibilityTree.tapBounds(node, null)) return true;
        }
        return false;
    }

    /**
     * Like {@link AccessibilityTree#findExact} but ignores nodes that are not visible or
     * have no area on screen.
     *
     * <p>This matters because the calendar footer holds CANCEL next to OK while the order
     * form behind it can carry its own hidden buttons. Tapping an off-screen "OK" either
     * does nothing or lands on whatever is at those coordinates — which is how the bot
     * ended up re-confirming the present date.</p>
     */
    private static AccessibilityNodeInfo findVisibleExact(AccessibilityNodeInfo node,
                                                          String label, int depth) {
        if (node == null || depth > MAX_DEPTH) return null;
        if (AccessibilityTree.equalsLabel(AccessibilityTree.textOf(node), label)) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (node.isVisibleToUser() && bounds.width() > 0 && bounds.height() > 0) {
                return node;
            }
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo found = findVisibleExact(node.getChild(i), label, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    /** True when a dialog offers an OK-style button (used to detect the dialog closed). */
    public static boolean hasConfirmButton(AccessibilityNodeInfo root) {
        for (String label : new String[]{"OK", "CONFIRM", "SELECT", "DONE", "SET"}) {
            if (AccessibilityTree.findExact(root, label) != null) return true;
        }
        return false;
    }

    // ----------------------------------------------------------------- helpers

    /** All distinct day numbers currently rendered (used for calendar detection). */
    private static Set<Integer> visibleDayCells(AccessibilityNodeInfo root) {
        Set<Integer> days = new LinkedHashSet<>();
        collectDayNumbers(root, days, 0);
        return days;
    }

    private static void collectDayNumbers(AccessibilityNodeInfo node, Set<Integer> out, int depth) {
        if (node == null || depth > MAX_DEPTH) return;
        String label = AccessibilityTree.textOf(node);
        Matcher bare = BARE_DAY.matcher(label);
        if (bare.matches()) {
            int day = Integer.parseInt(bare.group(1));
            if (day >= 1 && day <= 31) out.add(day);
        } else {
            LocalDate parsed = parseDateFromLabel(label);
            if (parsed != null) out.add(parsed.getDayOfMonth());
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) collectDayNumbers(node.getChild(i), out, depth + 1);
    }

    /** Parses the date formats third-party calendars expose; null when no full date. */
    public static LocalDate parseDateFromLabel(String raw) {
        if (raw == null) return null;
        String label = raw.replaceAll("(?i)\\b(selected|today|available|unavailable)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (label.isEmpty()) return null;

        try {
            Matcher dmy = DAY_MONTH_YEAR.matcher(label);
            if (dmy.find()) {
                Month month = monthFromName(dmy.group(2));
                if (month != null) return parseSafe(Integer.parseInt(dmy.group(3)),
                        month.getValue(), Integer.parseInt(dmy.group(1)));
            }
            Matcher mdy = MONTH_DAY_YEAR.matcher(label);
            if (mdy.find()) {
                Month month = monthFromName(mdy.group(1));
                if (month != null) return parseSafe(Integer.parseInt(mdy.group(3)),
                        month.getValue(), Integer.parseInt(mdy.group(2)));
            }
            Matcher iso = ISO.matcher(label);
            if (iso.find()) {
                return parseSafe(Integer.parseInt(iso.group(1)),
                        Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
            }
            Matcher dmySlash = DMY.matcher(label);
            if (dmySlash.find()) {
                int first = Integer.parseInt(dmySlash.group(1));
                int second = Integer.parseInt(dmySlash.group(2));
                int year = Integer.parseInt(dmySlash.group(3));
                // Day-first is the norm in this market; fall back when impossible.
                LocalDate dayFirst = parseSafe(year, second, first);
                if (dayFirst != null) return dayFirst;
                return parseSafe(year, first, second);
            }
        } catch (Exception ignored) {
            // Label simply is not a date.
        }
        return null;
    }

    private static LocalDate parseSafe(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Month monthFromName(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        for (Month month : Month.values()) {
            if (month.name().toLowerCase(Locale.ROOT).startsWith(value)) return month;
            if (month.getDisplayName(TextStyle.SHORT, Locale.US).toLowerCase(Locale.ROOT).equals(value)) {
                return month;
            }
            if (month.getDisplayName(TextStyle.FULL, Locale.US).toLowerCase(Locale.ROOT).equals(value)) {
                return month;
            }
        }
        // "sept" and similar abbreviations.
        if (value.startsWith("sep")) return Month.SEPTEMBER;
        return null;
    }

    private static boolean tapPreviousMonth(AccessibilityNodeInfo root, AccessibilityTree.Gestures gestures) {
        AccessibilityNodeInfo previous = findPreviousMonth(root, 0);
        if (previous == null) return false;
        return AccessibilityTree.click(previous, gestures);
    }

    private static AccessibilityNodeInfo findPreviousMonth(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > MAX_DEPTH) return null;
        String label = AccessibilityTree.textOf(node).toLowerCase(Locale.ROOT);
        boolean namedPrev = label.contains("previous month")
                || label.contains("prev month")
                || label.equals("<")
                || label.equals("←")
                || label.contains("previous");
        if (namedPrev && (node.isClickable() || node.isVisibleToUser())) {
            AccessibilityNodeInfo clickable = AccessibilityTree.clickableAncestor(node, 3);
            return clickable != null ? clickable : node;
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo found = findPreviousMonth(node.getChild(i), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    /** Epoch-millis helper used only for logging. */
    public static String describe(LocalDate date) {
        if (date == null) return "null";
        return date.toString() + " (" + Date.from(
                date.atStartOfDay(ZoneId.systemDefault()).toInstant()) + ")";
    }

    /** Bounds of a node, mainly for logging. */
    static Rect boundsOf(AccessibilityNodeInfo node) {
        Rect rect = new Rect();
        if (node != null) node.getBoundsInScreen(rect);
        return rect;
    }
}
