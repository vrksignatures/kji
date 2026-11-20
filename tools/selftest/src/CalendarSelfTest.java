import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import com.vrksignatures.sudhaautobot.AccessibilityTree;
import com.vrksignatures.sudhaautobot.CalendarDateSelector;
import com.vrksignatures.sudhaautobot.CalendarGridFinder;
import com.vrksignatures.sudhaautobot.DateRollbackPolicy;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;

import javax.imageio.ImageIO;

/**
 * Self-test for the Copy Order calendar step. Runs the app's own CalendarDateSelector and
 * CalendarGridFinder on a plain JVM.
 *
 * <p>What it can and cannot show:</p>
 * <ul>
 *   <li>Part A uses real frames cut from the device's screen recording, so "the reader
 *       finds the days and the highlight on this phone's calendar" is tested against the
 *       real thing.</li>
 *   <li>Parts B-D use a model of the stock date dialog. They show the logic is right for
 *       the dialog as modelled, across months, screen sizes and failure modes. They cannot
 *       show how the phone reacts to a touch; only a run on the phone can.</li>
 *   <li>Part D drives the look-press-check loop with a copy of the decision order used in
 *       SudhaAccessibilityService.onCalendarLook(), because the service itself needs
 *       Android to run.</li>
 * </ul>
 */
public final class CalendarSelfTest {
    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        recordedFrames();
        paintedCalendars();
        treeSelector();
        wholeStep();
        highlightedDateIsT();
        System.out.println();
        System.out.println(failures == 0
                ? "ALL " + checks + " CHECKS PASSED"
                : failures + " OF " + checks + " CHECKS FAILED");
        if (failures != 0) System.exit(1);
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL  " + what);
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title);
    }

    // ------------------------------------------------------------------ A
    /** Real frames from the phone: Nov 2026, 720x1600, highlight moved by hand 18 -> 14. */
    private static void recordedFrames() throws Exception {
        section("A. Frames from the device recording (720x1600, November 2026)");
        YearMonth november = YearMonth.of(2026, 11);
        // Day centres measured by hand on the full-resolution frames.
        double[] colX = {160.5, 227, 292.5, 359, 424.5, 491.5, 556.5};
        double[] rowY = {719, 794, 869, 944, 1019};
        for (int day : new int[]{18, 17, 16, 15, 14}) {
            int[] size = new int[2];
            int[] argb = frame("recording-day" + day + ".png", size);
            CalendarGridFinder.Reading r =
                    CalendarGridFinder.read(argb, size[0], size[1], november, DayOfWeek.SUNDAY);
            check(r.ok(), "frame with " + day + " highlighted is readable: " + r);
            if (!r.ok()) continue;
            check(r.selectedDay == day, "highlight read as " + r.selectedDay + ", expected " + day);
            double worst = 0;
            for (int d = 1; d <= 30; d++) {
                Rect cell = r.cellOf(d);
                int index = d - 1;                       // 1 Nov 2026 is a Sunday
                double dx = cell.exactCenterX() - colX[index % 7];
                double dy = cell.exactCenterY() - rowY[index / 7];
                worst = Math.max(worst, Math.max(Math.abs(dx), Math.abs(dy)));
            }
            check(worst <= 3.0, "every day is within 3 px of its measured centre (worst " + worst + ")");
            check(r.previousMonthArrow != null
                            && Math.abs(r.previousMonthArrow.exactCenterX() - 161) <= 4
                            && Math.abs(r.previousMonthArrow.exactCenterY() - 561) <= 4,
                    "previous-month arrow found at 161,561");
            for (int d = 13; d <= 17; d++) {
                if (d == day) continue;
                check(CalendarGridFinder.looksSelectable(argb, size[0], size[1], r.cellOf(d)),
                        d + " is drawn in dark ink (can be chosen)");
            }
            check(!CalendarGridFinder.looksSelectable(argb, size[0], size[1], r.cellOf(19)),
                    "19 (tomorrow) is drawn pale (cannot be chosen)");
            check(!CalendarGridFinder.read(argb, size[0], size[1], november.minusMonths(1),
                    DayOfWeek.SUNDAY).ok(), "the same frame is refused as October");
            check(!CalendarGridFinder.read(argb, size[0], size[1], november.plusMonths(1),
                    DayOfWeek.SUNDAY).ok(), "the same frame is refused as December");
            System.out.println("  day " + day + ": " + r + ", worst offset " + worst + " px");
        }
        int[] size = new int[2];
        int[] argb = frame("recording-between.png", size);
        CalendarGridFinder.Reading r =
                CalendarGridFinder.read(argb, size[0], size[1], november, DayOfWeek.SUNDAY);
        check(!r.ok() || r.selectedDay == 0,
                "a frame caught mid-animation never reports a highlighted day: " + r);
        System.out.println("  mid-animation frame: " + r);
    }

    /** Puts a card cut from the recording back where it was on a 720x1600 screen. */
    private static int[] frame(String name, int[] size) throws Exception {
        BufferedImage cut = ImageIO.read(new File("fixtures", name));
        int width = 720, height = 1600, atX = 78, atY = 486;
        int[] argb = new int[width * height];
        java.util.Arrays.fill(argb, Painter.SCRIM);
        for (int y = 0; y < cut.getHeight(); y++) {
            for (int x = 0; x < cut.getWidth(); x++) {
                argb[(atY + y) * width + atX + x] = cut.getRGB(x, y);
            }
        }
        size[0] = width;
        size[1] = height;
        return argb;
    }

    // ------------------------------------------------------------------ B
    /** Other months (4, 5 and 6 week rows), other screen sizes, other first weekdays. */
    private static void paintedCalendars() {
        section("B. Painted calendars: months, screen sizes, first day of week");
        YearMonth[] months = {YearMonth.of(2026, 11), YearMonth.of(2026, 8), YearMonth.of(2026, 2),
                YearMonth.of(2026, 12), YearMonth.of(2027, 1), YearMonth.of(2027, 2), YearMonth.of(2027, 5)};
        double[] scales = {1.0, 1.5, 2.0};
        DayOfWeek[] starts = {DayOfWeek.SUNDAY, DayOfWeek.MONDAY};
        Rect panel = new Rect(16, 100, 500, 420);
        int combos = 0;
        for (YearMonth month : months) {
            for (double scale : scales) {
                for (DayOfWeek start : starts) {
                    LocalDate today = month.atDay(Math.min(18, month.lengthOfMonth()));
                    for (int chosen : new int[]{1, 9, 17, month.lengthOfMonth()}) {
                        combos++;
                        String label = month + " x" + scale + " " + start + " day " + chosen;
                        Painter p = new Painter(scale).dialog(month, start, month.atDay(chosen),
                                month.atEndOfMonth(), null, panel);
                        CalendarGridFinder.Reading r = CalendarGridFinder.read(p.argb, p.width,
                                p.height, month, start);
                        check(r.ok(), label + " readable: " + r);
                        if (!r.ok()) continue;
                        check(r.selectedDay == chosen, label + ": highlight read as " + r.selectedDay);
                        double worst = 0;
                        for (int d = 1; d <= month.lengthOfMonth(); d++) {
                            double[] at = Painter.centreOf(month, start, d);
                            Rect cell = r.cellOf(d);
                            worst = Math.max(worst, Math.abs(cell.exactCenterX() - at[0] * scale) / (66 * scale));
                            worst = Math.max(worst, Math.abs(cell.exactCenterY() - at[1] * scale) / (75 * scale));
                        }
                        check(worst < 0.10, label + ": cells within a tenth of a cell (worst " + worst + ")");
                        check(!CalendarGridFinder.read(p.argb, p.width, p.height, month.minusMonths(1), start).ok(),
                                label + ": refused as the previous month");
                        check(!CalendarGridFinder.read(p.argb, p.width, p.height, month.plusMonths(1), start).ok(),
                                label + ": refused as the next month");
                    }
                    // A month in which nothing is highlighted (the chosen day is elsewhere).
                    Painter none = new Painter(scale).dialog(month, start, month.plusMonths(1).atDay(2),
                            month.plusMonths(1).atDay(2), null, panel);
                    CalendarGridFinder.Reading r = CalendarGridFinder.read(none.argb, none.width,
                            none.height, month, start);
                    check(r.ok() && r.selectedDay == 0,
                            month + " x" + scale + " without a highlight reads as 'no highlight': " + r);
                    // Today's own (unselected, accent-coloured) number is not a highlight.
                    Painter plain = new Painter(scale).dialog(month, start, month.atDay(3), today, null, panel);
                    r = CalendarGridFinder.read(plain.argb, plain.width, plain.height, month, start);
                    check(r.ok() && r.selectedDay == 3,
                            month + " x" + scale + ": today's coloured number is not mistaken for the highlight: " + r);
                }
            }
        }
        // Not a calendar at all.
        Painter blank = new Painter(1.0);
        check(!CalendarGridFinder.read(blank.argb, blank.width, blank.height,
                YearMonth.of(2026, 11), DayOfWeek.SUNDAY).ok(), "a screen without a dialog is refused");
        System.out.println("  " + combos + " month/size/weekday/highlight combinations");
    }

    // ------------------------------------------------------------------ C
    private static void treeSelector() {
        section("C. Accessibility-tree selector on the stock dialog model");
        LocalDate today = LocalDate.of(2026, 11, 18);
        for (boolean previousFirst : new boolean[]{true, false}) {
            for (int back = 1; back <= 5; back++) {
                StockDatePicker picker = new StockDatePicker(today);
                picker.previousPageFirst = previousFirst;
                LocalDate target = today.minusDays(back);
                CalendarDateSelector.Outcome outcome =
                        CalendarDateSelector.selectDate(picker.build(), target, picker, today, false);
                check(outcome == CalendarDateSelector.Outcome.TAPPED && target.equals(picker.selected),
                        "T-" + back + " with the off-screen month listed "
                                + (previousFirst ? "first" : "last") + ": " + outcome
                                + ", dialog selects " + picker.selected);
                check(target.equals(CalendarDateSelector.selectedDate(picker.build())),
                        "selection read back as " + CalendarDateSelector.selectedDate(picker.build()));
            }
        }
        // The present day and later days are never pressed, by any route.
        for (int ahead = 0; ahead <= 2; ahead++) {
            for (boolean physical : new boolean[]{false, true}) {
                StockDatePicker picker = new StockDatePicker(today);
                picker.selected = today.minusDays(3);          // so pressing today WOULD show
                CalendarDateSelector.Outcome outcome = CalendarDateSelector.selectDate(
                        picker.build(), today.plusDays(ahead), picker, today, physical);
                check(outcome != CalendarDateSelector.Outcome.TAPPED
                                && picker.nodeClicks == 0 && picker.touches == 0
                                && picker.selected.equals(today.minusDays(3)),
                        "today+" + ahead + " is refused without touching anything (" + outcome + ")");
            }
        }
        System.out.println("  T-1..T-5 in both page orders; today and later refused");
    }

    // ------------------------------------------------------------------ D
    private enum Result { CONFIRMED, CANCELLED, STUCK }

    /**
     * One Copy Order date attempt, decided in the same order as the service:
     * tree press -> look at the pixels -> OK only if the highlight is seen on the target,
     * otherwise press the drawn cell and look again; CANCEL when it cannot be done.
     */
    private static Result attempt(StockDatePicker picker, LocalDate target, LocalDate today,
                                  Rect panel, StringBuilder trace) {
        int day = target.getDayOfMonth();
        YearMonth month = YearMonth.from(target);
        for (int tick = 0; tick < 4; tick++) {            // PICK_DATE
            CalendarDateSelector.Outcome outcome =
                    CalendarDateSelector.selectDate(picker.build(), target, picker, today, false);
            trace.append(outcome).append(' ');
            if (outcome != CalendarDateSelector.Outcome.STEPPED_BACK_MONTH) break;
        }
        int verify = 0, monthSteps = 0;
        for (int tick = 0; tick < 20; tick++) {           // CONFIRM_DATE
            AccessibilityNodeInfo root = picker.build();
            Painter shot = new Painter(1.0).dialog(picker.shown, DayOfWeek.SUNDAY, picker.selected,
                    picker.today, picker.minDate, panel);
            CalendarGridFinder.Reading r = CalendarGridFinder.read(shot.argb, shot.width,
                    shot.height, month, DayOfWeek.SUNDAY);
            if (r.ok()) {
                if (r.selectedDay == day) {
                    trace.append("seen-on-").append(day).append(" OK ");
                    CalendarDateSelector.confirm(root);
                    return Result.CONFIRMED;
                }
                if (verify++ >= 5) break;
                Rect cell = r.cellOf(day);
                if (!CalendarGridFinder.looksSelectable(shot.argb, shot.width, shot.height, cell)) {
                    trace.append("greyed ");
                    break;
                }
                trace.append("press-").append(day).append(' ');
                AccessibilityTree.tapRect(picker, cell);
                continue;
            }
            CalendarGridFinder.Reading later = CalendarGridFinder.read(shot.argb, shot.width,
                    shot.height, month.plusMonths(1), DayOfWeek.SUNDAY);
            if (monthSteps < 2 && later.ok() && later.previousMonthArrow != null) {
                monthSteps++;
                trace.append("arrow ");
                AccessibilityTree.tapRect(picker, later.previousMonthArrow);
                continue;
            }
            trace.append("unreadable(").append(r.problem).append(") ");
            if (target.equals(CalendarDateSelector.selectedDate(root))) {
                CalendarDateSelector.confirm(root);
                return Result.CONFIRMED;
            }
            if (verify++ >= 5) break;
            CalendarDateSelector.selectDate(root, target, picker, today, true);
        }
        trace.append("CANCEL ");
        return CalendarDateSelector.cancel(picker.build()) ? Result.CANCELLED : Result.STUCK;
    }

    private static void wholeStep() {
        section("D. Whole step: press, look, confirm (dialog model)");
        LocalDate today = LocalDate.of(2026, 11, 18);
        Rect panel = new Rect(16, 100, 500, 420);
        String[] names = {"dialog behaves normally", "dialog accepts the click but ignores it",
                "dialog exposes no day cells at all", "no day cells, and clicks ignored"};
        for (int mode = 0; mode < 4; mode++) {
            for (boolean previousFirst : new boolean[]{true, false}) {
                for (int back = 1; back <= 5; back++) {
                    StockDatePicker picker = new StockDatePicker(today);
                    picker.previousPageFirst = previousFirst;
                    picker.ignoreNodeClicks = mode == 1 || mode == 3;
                    picker.exposeDayNodes = mode != 2 && mode != 3;
                    LocalDate target = today.minusDays(back);
                    StringBuilder trace = new StringBuilder();
                    Result result = attempt(picker, target, today, panel, trace);
                    check(result == Result.CONFIRMED && picker.confirmed
                                    && target.equals(picker.selected),
                            names[mode] + ", T-" + back + ": " + result + ", dialog confirmed "
                                    + picker.selected + " [" + trace + "| " + picker.log + "]");
                    if (previousFirst && back == 1) {
                        System.out.println("  " + names[mode] + ": " + trace + "-> " + picker.log);
                    }
                }
            }
        }

        // First days of a month: T-1..T-5 reach into the month before.
        LocalDate second = LocalDate.of(2026, 12, 2);
        for (int mode = 0; mode < 4; mode++) {
            for (int back = 1; back <= 5; back++) {
                StockDatePicker picker = new StockDatePicker(second);
                picker.ignoreNodeClicks = mode == 1 || mode == 3;
                picker.exposeDayNodes = mode != 2 && mode != 3;
                LocalDate target = second.minusDays(back);
                StringBuilder trace = new StringBuilder();
                Result result = attempt(picker, target, second, panel, trace);
                check(result == Result.CONFIRMED && target.equals(picker.selected),
                        "2 Dec, " + names[mode] + ", T-" + back + " (" + target + "): " + result
                                + ", dialog confirmed " + picker.selected + " [" + trace + "| "
                                + picker.log + "]");
                if (back == 3) {
                    System.out.println("  across the month, " + names[mode] + ": " + trace + "-> " + picker.log);
                }
            }
        }

        // Cases where the day cannot be chosen: the dialog must be closed with CANCEL and
        // must never be confirmed on the present day.
        {
            StockDatePicker picker = new StockDatePicker(today);
            picker.ignoreNodeClicks = true;
            picker.overlay = new Rect(0, 600, 720, 1100);       // something swallows every touch
            StringBuilder trace = new StringBuilder();
            Result result = attempt(picker, today.minusDays(1), today, panel, trace);
            check(result == Result.CANCELLED && !picker.confirmed && picker.cancelled,
                    "touches swallowed: closed with CANCEL, not OK (" + result + ") [" + trace + "]");
            System.out.println("  touches swallowed: " + trace + "-> " + picker.log);
        }
        {
            StockDatePicker picker = new StockDatePicker(today);
            picker.minDate = today;                              // every earlier day greyed out
            StringBuilder trace = new StringBuilder();
            Result result = attempt(picker, today.minusDays(1), today, panel, trace);
            check(result == Result.CANCELLED && !picker.confirmed,
                    "day greyed out: closed with CANCEL, not OK (" + result + ") [" + trace + "]");
            System.out.println("  day greyed out: " + trace + "-> " + picker.log);
        }
    }

    // ------------------------------------------------------------------ E
    /**
     * T is the date the dialog has highlighted when it opens, and the dates tried are
     * T-1 ... T-5 counted from it. The phone's clock is not consulted anywhere: this test
     * runs on whatever day the build machine thinks it is, with dialogs opened on dates
     * years away from it, and must give the same answers every time.
     */
    private static void highlightedDateIsT() {
        section("E. T is the highlighted date; T-1 to T-5 are counted from it");
        Rect panel = new Rect(16, 100, 500, 420);
        LocalDate[] opensOn = {
                LocalDate.of(2026, 11, 18),      // the recording
                LocalDate.of(2026, 11, 20),      // the operator's example: 20 -> 19
                LocalDate.of(2026, 12, 2),       // T-2 onwards are in the month before
                LocalDate.of(2027, 1, 1),        // T-1 is in the year before
                LocalDate.of(2028, 3, 1),        // T-1 is 29 February
                LocalDate.of(2031, 7, 15),       // nowhere near any real "today"
                LocalDate.of(2019, 5, 3)};
        String[] names = {"dialog behaves normally", "dialog accepts the click but ignores it",
                "dialog exposes no day cells at all", "no day cells, and clicks ignored"};

        // 1. Reading T, three ways.
        for (LocalDate t : opensOn) {
            for (boolean previousFirst : new boolean[]{true, false}) {
                StockDatePicker picker = new StockDatePicker(t);
                picker.previousPageFirst = previousFirst;
                check(t.equals(CalendarDateSelector.highlightedDate(picker.build())),
                        "opens on " + t + ": read from the selected day ("
                                + CalendarDateSelector.highlightedDate(picker.build()) + ")");
                picker.exposeDayNodes = false;
                check(t.equals(CalendarDateSelector.highlightedDate(picker.build())),
                        "opens on " + t + ", no day cells: read from the headline ("
                                + CalendarDateSelector.highlightedDate(picker.build()) + ")");
                check(t.equals(CalendarDateSelector.headlineDate(picker.build())),
                        "opens on " + t + ": headline alone gives the full date");
            }
            // From the screen: the orange disc, with the month the dialog names.
            StockDatePicker picker = new StockDatePicker(t);
            YearMonth month = CalendarDateSelector.visibleMonth(picker.build());
            Painter shot = new Painter(1.0).dialog(picker.shown, DayOfWeek.SUNDAY, picker.selected,
                    picker.today, picker.minDate, panel);
            CalendarGridFinder.Reading r = month == null ? null
                    : CalendarGridFinder.read(shot.argb, shot.width, shot.height, month, DayOfWeek.SUNDAY);
            check(r != null && r.ok() && month.atDay(Math.max(1, r.selectedDay)).equals(t),
                    "opens on " + t + ": orange disc on the screen read as "
                            + (r == null || !r.ok() ? "nothing" : String.valueOf(month.atDay(Math.max(1, r.selectedDay)))));
        }

        // 2. The ladder: T is read once, at the first opening, and every date is counted
        //    from it - whether the dialog reopens on T or on the date chosen last time.
        for (LocalDate t : opensOn) {
            for (int mode = 0; mode < 4; mode++) {
                for (boolean reopensOnLastChoice : new boolean[]{false, true}) {
                    LocalDate anchor = null;
                    StringBuilder dates = new StringBuilder();
                    for (int back = 1; back <= DateRollbackPolicy.MAX_LOOK_BACK_DAYS; back++) {
                        StockDatePicker picker = new StockDatePicker(t);
                        picker.ignoreNodeClicks = mode == 1 || mode == 3;
                        picker.exposeDayNodes = mode != 2 && mode != 3;
                        if (reopensOnLastChoice && back > 1) {
                            picker.selected = t.minusDays(back - 1);
                            picker.shown = YearMonth.from(picker.selected);
                        }
                        if (anchor == null) {
                            anchor = CalendarDateSelector.highlightedDate(picker.build());
                            check(t.equals(anchor), "T read as " + anchor + " for a dialog opened on " + t);
                            if (anchor == null) break;
                        }
                        LocalDate target = DateRollbackPolicy.candidateArray(anchor)[back - 1];
                        StringBuilder trace = new StringBuilder();
                        Result result = attempt(picker, target, anchor, panel, trace);
                        check(result == Result.CONFIRMED && picker.confirmed
                                        && t.minusDays(back).equals(picker.selected),
                                "opens on " + t + ", " + names[mode]
                                        + (reopensOnLastChoice ? ", reopens on the last choice" : "")
                                        + ", T-" + back + ": " + result + ", dialog confirmed "
                                        + picker.selected + " [" + trace + "]");
                        dates.append(picker.selected.getDayOfMonth()).append(' ');
                    }
                    if (mode == 0 && !reopensOnLastChoice) {
                        System.out.println("  opens on " + t + " -> tries " + dates.toString().trim());
                    }
                }
            }
        }

        // 3. T itself, or anything after it, is never chosen.
        for (LocalDate t : opensOn) {
            for (int ahead = 0; ahead <= 2; ahead++) {
                StockDatePicker picker = new StockDatePicker(t);
                picker.selected = t;
                CalendarDateSelector.Outcome outcome = CalendarDateSelector.selectDate(
                        picker.build(), t.plusDays(ahead), picker, t, false);
                check(outcome != CalendarDateSelector.Outcome.TAPPED && picker.nodeClicks == 0,
                        "opens on " + t + ": T+" + ahead + " is refused (" + outcome + ")");
            }
        }

        // 4. No T, no dates: the rule cannot fall back on the phone's date.
        boolean refused = false;
        try {
            DateRollbackPolicy.candidates(null);
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        check(refused, "the date rule refuses to work without a T read from the calendar");
        check(!DateRollbackPolicy.isWithinWindow(null, LocalDate.of(2026, 11, 17)),
                "no date is \"within the window\" when T is unknown");

        // 5. The calendar code does not ask the phone what day it is.
        String app = "../../app/src/main/java/com/vrksignatures/sudhaautobot/";
        String[] clockCalls = {"LocalDate.now", "YearMonth.now", "LocalDateTime.now", "ZonedDateTime.now",
                "Instant.now", "currentTimeMillis", "new Date(", "Clock."};
        for (String file : new String[]{"CalendarDateSelector.java", "CalendarGridFinder.java",
                "DateRollbackPolicy.java"}) {
            String source = read(app + file);
            String found = null;
            for (String call : clockCalls) if (source.contains(call)) found = call;
            check(!source.isEmpty() && found == null,
                    file + " never reads the phone's date" + (found == null ? "" : " (found " + found + ")"));
        }
        String service = read(app + "SudhaAccessibilityService.java");
        int nowCalls = service.split("LocalDate\\.now", -1).length - 1;
        check(nowCalls == 1 && service.contains("ledger.sessionDate().equals(LocalDate.now())"),
                "the service reads the phone's date once only, to start a new day's ledger - not for the calendar ("
                        + nowCalls + ")");
        check(service.contains("DateRollbackPolicy.candidateArray(calendarAnchor)"),
                "the service counts the dates from the calendar's highlighted date");
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
