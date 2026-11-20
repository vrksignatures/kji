import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import com.vrksignatures.sudhaautobot.AccessibilityTree;
import com.vrksignatures.sudhaautobot.ListScroller;
import com.vrksignatures.sudhaautobot.RetailerPolicy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Self-test for scrolling the retailer list. Runs the app's own ListScroller against a
 * model of the Customers screen on a plain JVM.
 *
 * <p>The loop is {@link ListRunner}, which follows the same order as
 * SudhaAccessibilityService.onRetailerList(): look at the rows, open the retailer that comes
 * after the one handled last, and only when there is none on this screen ask the scroller
 * for the next screenful.</p>
 *
 * <p>What this shows: for every way the list might answer a scroll request, the bot reaches
 * every retailer, passes none by, and stops at the end. What it cannot show: how the real
 * list on the phone answers. That is why the scroller checks the rows after every attempt
 * instead of assuming.</p>
 */
public final class ListScrollSelfTest {
    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        previousBehaviour();
        everyListBehaviour();
        thingsInTheWay();
        endOfList();
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

    /** What one simulated run did. */
    private static final class Outcome {
        final List<String> handled = new ArrayList<>();
        ListScroller.Step last;
        int passes, scrollSteps, blockedPasses;
        String note = "";
    }

    /**
     * Runs the bot's list loop until the scroller reports the end (or {@code maxPasses}).
     *
     * @param backToTop the list reopens at the top after each retailer, as it does when the
     *                  app reloads the Customers screen
     */
    private static Outcome run(RetailerListModel model, boolean backToTop, int maxPasses) {
        ListRunner runner = new ListRunner(model, backToTop);
        runner.run(maxPasses);
        Outcome out = new Outcome();
        out.handled.addAll(runner.opened);
        out.last = runner.lastStep;
        out.passes = runner.passes;
        out.scrollSteps = runner.scrollSteps;
        out.blockedPasses = runner.blockedPasses;
        out.note = runner.note;
        return out;
    }

    private static List<String> retailersOf(RetailerListModel model) {
        List<String> all = new ArrayList<>();
        for (String id : model.ids) if (id.startsWith("SMR")) all.add(id);
        return all;
    }

    // ------------------------------------------------------------------ A
    /** The scrolling code as it was, on the same models: where it stands still. */
    private static void previousBehaviour() {
        section("A. The previous scrolling code on the same list models");
        for (RetailerListModel.Mode mode : RetailerListModel.Mode.values()) {
            RetailerListModel model = new RetailerListModel(23);
            model.mode = mode;
            boolean accepted = false;
            for (int i = 0; i < 6; i++) accepted |= AccessibilityTree.scrollForward(model.build(), model);
            System.out.println("  " + mode + ": 6 scroll calls, reported "
                    + (accepted ? "\"scrolled\"" : "\"cannot scroll\"") + ", list moved " + model.scroll + " px");
        }
        RetailerListModel decoy = new RetailerListModel(23);
        decoy.decoyStripFirst = true;
        boolean accepted = false;
        for (int i = 0; i < 6; i++) accepted |= AccessibilityTree.scrollForward(decoy.build(), decoy);
        System.out.println("  another scrollable strip first on the screen: reported "
                + (accepted ? "\"scrolled\"" : "\"cannot scroll\"") + ", list moved " + decoy.scroll
                + " px (" + decoy.actionsOnDecoy + " requests went to the strip)");
        check(decoy.scroll == 0 && accepted, "previous code: scrolls the wrong container and reports success");
        RetailerListModel plain = new RetailerListModel(23);
        plain.mode = RetailerListModel.Mode.NOT_SCROLLABLE;
        check(!AccessibilityTree.scrollForward(plain.build(), plain) && plain.drags == 0,
                "previous code: sends no swipe at all when the list is not marked scrollable");
    }

    // ------------------------------------------------------------------ B
    private static void everyListBehaviour() {
        section("B. Every retailer is reached, none is passed by, and the run ends");
        int[] sizes = {1, 3, 5, 6, 23, 125};
        int scenarios = 0;
        for (RetailerListModel.Mode mode : RetailerListModel.Mode.values()) {
            for (int size : sizes) {
                for (boolean backToTop : new boolean[]{false, true}) {
                    for (int bottomBar : new int[]{0, 160}) {
                        for (int fling : new int[]{0, 120}) {
                            scenarios++;
                            RetailerListModel model = new RetailerListModel(size);
                            model.mode = mode;
                            model.listBottom = RetailerListModel.SCREEN_H - bottomBar;
                            model.flingAfterDrag = fling;
                            Outcome out = run(model, backToTop, 60000);
                            String label = mode + ", " + size + " retailers"
                                    + (backToTop ? ", list reopens at the top" : "")
                                    + (bottomBar > 0 ? ", bottom bar" : "")
                                    + (fling > 0 ? ", list runs on after a swipe" : "");
                            check(out.handled.equals(retailersOf(model)),
                                    label + ": handled " + out.handled.size() + " of " + size
                                            + " in list order");
                            check(out.last == ListScroller.Step.END_OF_LIST,
                                    label + ": ended with " + out.last + " (" + out.note + ")");
                            check(model.dragsOnPanel == 0, label + ": no swipe started on the bot panel");
                            if (size == 125 && !backToTop && bottomBar == 0 && fling == 0) {
                                System.out.println("  " + mode + ": 125 retailers handled with "
                                        + out.scrollSteps + " scroll steps (" + model.actionRequests
                                        + " requests to the list, " + model.drags + " swipes); "
                                        + out.note);
                            }
                        }
                    }
                }
            }
        }
        System.out.println("  " + scenarios + " scenarios");
    }

    // ------------------------------------------------------------------ C
    private static void thingsInTheWay() {
        section("C. Things in the way");
        // Another scrollable container earlier on the screen must be left alone.
        for (RetailerListModel.Mode mode : RetailerListModel.Mode.values()) {
            RetailerListModel model = new RetailerListModel(23);
            model.mode = mode;
            model.decoyStripFirst = true;
            Outcome out = run(model, false, 5000);
            check(out.handled.equals(retailersOf(model)) && model.actionsOnDecoy == 0,
                    mode + " with a scrollable strip first: all handled, strip never touched ("
                            + out.handled.size() + ", " + model.actionsOnDecoy + ")");
        }
        // A tab pager around a list that is not itself scrollable: "forward" there would
        // turn the page, so it must never be asked.
        {
            RetailerListModel model = new RetailerListModel(23);
            model.mode = RetailerListModel.Mode.NOT_SCROLLABLE;
            model.insidePager = true;
            Outcome out = run(model, false, 5000);
            check(out.handled.equals(retailersOf(model)) && model.actionsOnPager == 0,
                    "list inside a tab pager: all handled, pager never asked to scroll ("
                            + out.handled.size() + ", " + model.actionsOnPager + ")");
            System.out.println("  tab pager around the list: " + out.handled.size()
                    + " handled, pager asked " + model.actionsOnPager + " times");
        }
        // The bot panel lying on different parts of the list.
        Rect[] panels = {
                new Rect(16, 100, 500, 420),          // where it sits by default
                new Rect(120, 700, 600, 1020),        // middle of the list
                new Rect(0, 900, 720, 1600),          // the whole lower half
                new Rect(0, 430, 720, 1000),          // the whole upper half
                new Rect(200, 430, 720, 1600),        // everything but a strip on the left
        };
        for (Rect panel : panels) {
            RetailerListModel model = new RetailerListModel(23);
            model.mode = RetailerListModel.Mode.NOT_SCROLLABLE;
            model.panel = panel;
            Outcome out = run(model, false, 5000);
            check(out.handled.equals(retailersOf(model)) && model.dragsOnPanel == 0,
                    "panel at " + panel.left + "," + panel.top + "-" + panel.right + "," + panel.bottom
                            + ": all handled (" + out.handled.size() + "), swipes on the panel "
                            + model.dragsOnPanel);
        }
        // The panel over the whole list: nothing can be swiped. The run must NOT be
        // declared finished; it must say what is in the way and keep waiting.
        {
            RetailerListModel model = new RetailerListModel(23);
            model.mode = RetailerListModel.Mode.NOT_SCROLLABLE;
            model.panel = new Rect(0, 300, 720, 1600);
            Outcome out = run(model, false, 40);
            check(out.last == ListScroller.Step.BLOCKED && out.blockedPasses >= 30 && model.drags == 0,
                    "panel over the whole list: reports BLOCKED, never 'end of list' (" + out.last + ")");
            System.out.println("  panel over the whole list: " + out.last + " - " + out.note);
            // ...and carries on as soon as the panel is moved away.
            model.panel = new Rect(16, 100, 500, 420);
            out = run(model, false, 5000);
            check(out.handled.equals(retailersOf(model)), "after the panel is moved: all handled");
        }
        // A swipe that Android cancelled (a finger on the screen) is not held against the list.
        {
            ListScroller scroller = new ListScroller();
            RetailerListModel frozen = new RetailerListModel(23) {
                @Override
                public boolean dragUp(int x, int fromY, int toY) {
                    drags++;
                    return true;                       // sent, cancelled, nothing moved
                }
            };
            frozen.mode = RetailerListModel.Mode.NOT_SCROLLABLE;
            int sent = 0;
            ListScroller.Step step = null;
            for (int pass = 0; pass < 40 && step != ListScroller.Step.END_OF_LIST; pass++) {
                List<AccessibilityTree.RetailerRow> rows = AccessibilityTree.retailerRows(frozen.build());
                step = scroller.advance(rows, 720, 1600, frozen.panel, frozen);
                if (step == ListScroller.Step.SCROLLED_BY_DRAG) {
                    sent++;
                    if (sent <= 3) scroller.dragCancelled();
                }
            }
            check(sent == 6, "3 cancelled swipes are not counted: 6 sent before giving up (" + sent + ")");
        }
    }

    // ------------------------------------------------------------------ D
    private static void endOfList() {
        section("D. Knowing the list has ended");
        for (RetailerListModel.Mode mode : RetailerListModel.Mode.values()) {
            RetailerListModel model = new RetailerListModel(23);
            model.mode = mode;
            Outcome out = run(model, false, 5000);
            int rowsFromEnd = (model.maxScroll() - model.scroll) / RetailerListModel.ROW_H;
            check(model.scroll == model.maxScroll(),
                    mode + ": the run ends at the very bottom (" + rowsFromEnd + " rows short)");
            check(model.everShown.equals(retailersOf(model)),
                    mode + ": every retailer was on screen at some point, in order");
        }
        // No drag covers more than 70% of the list, wherever the panel lies.
        int worst = 0;
        int listHeight = RetailerListModel.SCREEN_H * 90 / 100 - RetailerListModel.LIST_TOP;
        for (int top = 430; top <= 1500; top += 37) {
            RetailerListModel model = new RetailerListModel(40);
            model.mode = RetailerListModel.Mode.NOT_SCROLLABLE;
            model.panel = new Rect(100, top, 620, top + 300);
            run(model, false, 5000);
            worst = Math.max(worst, model.longestDrag * 100 / listHeight);
        }
        check(worst > 0 && worst <= 70, "longest drag is " + worst + "% of the list height (limit 70%)");
        System.out.println("  longest drag: " + worst + "% of the list height");
    }
}
