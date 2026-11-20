import android.view.accessibility.AccessibilityNodeInfo;

import com.vrksignatures.sudhaautobot.AccessibilityTree;
import com.vrksignatures.sudhaautobot.ListScroller;
import com.vrksignatures.sudhaautobot.RetailerSequence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The bot's loop over the customer list, as SudhaAccessibilityService.onRetailerList() runs
 * it: look at the rows, ask {@link RetailerSequence} which retailer is next, open it, and
 * when there is none on this screen ask {@link ListScroller} for the next screenful.
 *
 * <p>The service itself needs Android to run, so this is a hand-written copy of its order
 * of decisions. The two classes that make the decisions are the app's own.</p>
 */
final class ListRunner {
    final RetailerListModel model;
    /** The app reopens the list at the top after every retailer (as seen on the device). */
    final boolean backToTop;
    final ListScroller scroller = new ListScroller();
    final RetailerSequence sequence = new RetailerSequence();

    /** The day's ledger: every retailer with an outcome. */
    final Set<String> done = new LinkedHashSet<>();
    /** The ledger's "handled last". */
    String last;

    /** Retailers the bot opened, in the order it opened them. */
    final List<String> opened = new ArrayList<>();
    ListScroller.Step lastStep;
    int passes, scrollSteps, blockedPasses, restarts;
    /** Scroll steps spent before each retailer was opened. */
    int scrollsSinceOpen, mostScrollsForOne;
    String note = "";
    boolean finished;

    ListRunner(RetailerListModel model, boolean backToTop) {
        this.model = model;
        this.backToTop = backToTop;
    }

    // ---- Scroll memory, as SudhaAccessibilityService.scrollMemorySteps() runs it. Off
    //      unless a test turns it on, so every older test runs the loop it always ran.
    boolean memory;
    /** What RunHistory keeps: the retailer and how many scrolls down it was. */
    String savedId;
    int savedCount;
    com.vrksignatures.sudhaautobot.ScrollMemory.Seek seek;
    int seekVisit = -1;
    boolean memoryOff;
    /** How the searches ended, in order. */
    final List<com.vrksignatures.sudhaautobot.ScrollMemory.End> ends = new ArrayList<>();
    /** Times the rows were read (each is a walk over the whole screen on the phone). */
    int looks;
    int blindScrolls, upScrolls;
    /** A test may move the list or change the saved count just before a retailer is looked for. */
    Runnable beforeSeek;

    private boolean memorySteps(List<AccessibilityTree.RetailerRow> rows, List<String> ids) {
        if (!memory || memoryOff) return false;
        int visit = scroller.visit();
        if (seekVisit != visit) {
            seek = null;
            if (scroller.scrolledDown() || last == null) return false;
            seekVisit = visit;
            if (ids.contains(last)) return false;
            if (beforeSeek != null) beforeSeek.run();
            int count = last.equals(savedId) ? savedCount : 0;
            if (!com.vrksignatures.sudhaautobot.ScrollMemory.usable(count)) return false;
            AccessibilityNodeInfo list = scroller.jumpTarget(rows);
            if (list == null) return false;
            seek = new com.vrksignatures.sudhaautobot.ScrollMemory.Seek(last, count);
            for (int left = seek.burst(); left > 0; left--) {
                if (!scroller.jump(list, true)) {
                    seek.refused();
                    savedId = null;
                    break;
                }
                blindScrolls++;
            }
            return true;
        }
        if (seek == null || seek.over()) return false;
        com.vrksignatures.sudhaautobot.ScrollMemory.Move move =
                seek.next(ids, done::contains, ListScroller.snapshot(rows));
        if (move == com.vrksignatures.sudhaautobot.ScrollMemory.Move.WAIT) return true;
        if (move != com.vrksignatures.sudhaautobot.ScrollMemory.Move.NONE) {
            boolean down = move == com.vrksignatures.sudhaautobot.ScrollMemory.Move.FORWARD;
            if (scroller.jump(scroller.jumpTarget(rows), down)) {
                if (!down) upScrolls++;
                return true;
            }
            seek.refused();
        }
        ends.add(seek.end());
        if (seek.end() == com.vrksignatures.sudhaautobot.ScrollMemory.End.GAVE_UP
                || seek.end() == com.vrksignatures.sudhaautobot.ScrollMemory.End.REFUSED) {
            memoryOff = true;
            savedId = null;
        }
        return false;
    }

    /** The "expected retailers" setting; by default what the list really holds. */
    int expectedCount = -1;
    /** When set, the expected count is read off the screen (and {@link #configuredCount} is the fallback). */
    com.vrksignatures.sudhaautobot.Roster roster;
    int configuredCount = 125;
    boolean swept;
    int sweeps;

    private int expected() {
        if (expectedCount >= 0) return expectedCount;
        int count = 0;
        for (String id : model.ids) if (id.startsWith("SMR")) count++;
        return count;
    }

    /** Pressing START: counters are cleared, the ledger is kept. */
    void start() {
        scroller.reset();
        sequence.newVisit();
        swept = false;
        finished = false;
        seek = null;
        seekVisit = -1;
        memoryOff = false;
    }

    void run(int maxPasses) {
        while (passes++ < maxPasses) {
            AccessibilityNodeInfo root = model.build();
            if (roster != null) {
                // As the service does at the top of every look: Aakriti reads the screen
                // and the expected count follows what the app itself prints.
                roster.see(AccessibilityTree.allText(root).toLowerCase(java.util.Locale.ROOT), "today");
                expectedCount = roster.expected(configuredCount);
            }
            List<AccessibilityTree.RetailerRow> rows = AccessibilityTree.retailerRows(root);
            looks++;
            scroller.observe(rows);

            List<String> ids = new ArrayList<>();
            for (AccessibilityTree.RetailerRow row : rows) ids.add(row.id);
            if (!scroller.scrolledDown()) sequence.newVisit();
            if (memorySteps(rows, ids)) continue;
            RetailerSequence.Decision decision = sequence.decide(ids, last, done::contains);
            if (decision.action == RetailerSequence.Action.OPEN) {
                if (memory && !memoryOff) {
                    int depth = scroller.replayableDepth();
                    savedId = com.vrksignatures.sudhaautobot.ScrollMemory.usable(depth) ? decision.id : null;
                    savedCount = depth;
                }
                // ... details, orders, copy, pay (or skip) happen here; the outcome is
                // written to the ledger, which also makes this retailer "handled last".
                done.add(decision.id);
                last = decision.id;
                opened.add(decision.id);
                mostScrollsForOne = Math.max(mostScrollsForOne, scrollsSinceOpen);
                scrollsSinceOpen = 0;
                scroller.leftList();
                sequence.newVisit();
                if (backToTop) model.scroll = 0;
                continue;
            }
            boolean seekingLast = decision.action == RetailerSequence.Action.SCROLL_TO_LAST;

            lastStep = scroller.advance(rows, RetailerListModel.SCREEN_W, RetailerListModel.SCREEN_H,
                    model.panel, model);
            note = scroller.note();
            if (lastStep == ListScroller.Step.END_OF_LIST) {
                if (seekingLast) {
                    // Bottom reached, "handled last" never seen: forget the place, go back
                    // to the first page and come down the list again from the top.
                    last = null;
                    sequence.newVisit();
                    scroller.leftList();
                    model.scroll = 0;
                    restarts++;
                    continue;
                }
                if (!swept && done.size() < expected()) {
                    // Fewer handled than the list should hold: once more from the top.
                    swept = true;
                    sweeps++;
                    last = null;
                    sequence.newVisit();
                    scroller.leftList();
                    model.scroll = 0;
                    continue;
                }
                last = null;
                finished = true;
                return;
            }
            if (lastStep == ListScroller.Step.BLOCKED) blockedPasses++;
            if (lastStep == ListScroller.Step.SCROLLED_BY_ACTION
                    || lastStep == ListScroller.Step.SCROLLED_BY_DRAG) {
                scrollSteps++;
                scrollsSinceOpen++;
            }
        }
    }
}
