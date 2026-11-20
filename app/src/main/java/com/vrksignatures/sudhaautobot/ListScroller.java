package com.vrksignatures.sudhaautobot;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Moves the retailer list down one step at a time, and knows when the list has ended.
 *
 * <p>The rule that shapes everything here: <b>a scroll only counts when the rows on screen
 * are seen to have moved.</b> "The scroll request was accepted" proves nothing - a list can
 * accept the request and stay where it is, and the bot then waits for rows that never come.
 * So each attempt is followed by a look at the rows, and the method is changed when the
 * rows have not moved:</p>
 * <ol>
 *   <li>ask the list itself to scroll (the container the rows actually sit in - not the
 *       first scrollable thing found anywhere on the screen);</li>
 *   <li>drag the list with a real touch, as a finger would.</li>
 * </ol>
 *
 * <p>A drag never covers more than 70% of the list's height, so the last rows of one screen
 * are still on the next: no retailer can pass by unseen. The drag starts where the bot's
 * own floating panel is not, because the panel takes any touch that lands on it.</p>
 *
 * <p>The list has ended when drags no longer move it (three in a row; one is enough once
 * the list's own scrolling has been seen to work and now refuses).</p>
 *
 * <p>Holds no Android state beyond the nodes passed in, so the self-test runs this class
 * against a model of the list on a plain JVM.</p>
 */
public final class ListScroller {

    /** What {@link #advance} did. */
    public enum Step {
        /** The list was asked to scroll; look again next tick. */
        SCROLLED_BY_ACTION,
        /** A drag was sent; look again when it has finished. */
        SCROLLED_BY_DRAG,
        /** Nothing sent: reading the rows once more before judging the last attempt. */
        SETTLING,
        /** Drags no longer move the list. */
        END_OF_LIST,
        /** No drag could be made (the panel covers the list, or Android refused it). */
        BLOCKED
    }

    /** Drags that must fail to move the list before it is taken to have ended. */
    public static final int MAX_STUCK_DRAGS = 3;
    /** Drags that Android reported as cancelled and that are therefore not held against the list. */
    private static final int MAX_VOID_DRAGS = 3;
    private static final int MAX_ANCESTOR_HOPS = 14;

    private static final int NONE = 0;
    private static final int ACTION = 1;
    private static final int DRAG = 2;

    /** Method of the attempt whose effect has not been judged yet. */
    private int pending = NONE;
    /** Rows as they stood before that attempt. */
    private String baseline = "";
    private boolean rereadDone;
    private boolean lastDragCancelled;

    /** The list's own scroll action has moved this list at least once. */
    private boolean actionProven;
    /** The action was accepted and moved nothing, before ever being seen to work. */
    private boolean actionUseless;
    /** The (proven) action moved nothing at this position; drag until something moves. */
    private boolean actionSpent;

    private int stuckDrags;
    private int voidDrags;
    /** Confirmed scrolls since the list was last entered. */
    private int depth;
    private boolean everMoved;
    private String note = "";
    /** A drag moved the list since it was entered: the depth is then not a count of scroll actions. */
    private boolean draggedThisVisit;
    /** Goes up each time the list is left, so a caller can tell one visit from the next. */
    private int visit;

    /** Forgets everything, including what was learned about the list. Call on START. */
    public void reset() {
        leftList();
        actionProven = false;
        actionUseless = false;
        everMoved = false;
        note = "";
    }

    /** The bot is leaving the list (a retailer was opened, or the list was re-expanded). */
    public void leftList() {
        pending = NONE;
        baseline = "";
        rereadDone = false;
        lastDragCancelled = false;
        actionSpent = false;
        stuckDrags = 0;
        voidDrags = 0;
        depth = 0;
        draggedThisVisit = false;
        visit++;
    }

    // ------------------------------------------------- for the scroll memory
    // Additions used by ScrollMemory only. advance() and observe() do not depend on them.

    /** Which visit to the list this is; changes whenever {@link #leftList} is called. */
    public int visit() {
        return visit;
    }

    /**
     * How many scroll actions it took to get here since the list was entered, or -1 when
     * that cannot be replayed (a drag was among them, or a scroll is still being judged).
     */
    public int replayableDepth() {
        return draggedThisVisit || pending != NONE ? -1 : depth;
    }

    /**
     * The list the rows sit in, to be handed to {@link #jump}; null when there is none, when
     * its scroll action is known to do nothing here, or while a checked scroll is under way.
     */
    public AccessibilityNodeInfo jumpTarget(List<AccessibilityTree.RetailerRow> rows) {
        if (pending != NONE || actionUseless) return null;
        return scrollableFor(rows);
    }

    /**
     * Sends one scroll action without judging whether it worked - the caller is counting,
     * not looking. Down counts one deeper; up one less, but never back to "not scrolled",
     * because only the list itself can say that it is at its top.
     *
     * @param list what {@link #jumpTarget} returned
     * @return false when nothing was sent (no list, or the list refused)
     */
    public boolean jump(AccessibilityNodeInfo list, boolean down) {
        if (list == null || pending != NONE) return false;
        boolean accepted;
        try {
            accepted = list.performAction(down
                    ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        } catch (Exception e) {
            accepted = false;
        }
        if (!accepted) return false;
        depth = down ? depth + 1 : Math.max(1, depth - 1);
        note = down ? "scrolled down by count" : "scrolled up to look";
        return true;
    }

    /** Android reported the last drag as cancelled (for instance a finger touched the screen). */
    public void dragCancelled() {
        if (pending == DRAG) lastDragCancelled = true;
    }

    /** True once the list has been scrolled, or a scroll is under way, since it was entered. */
    public boolean scrolledDown() {
        return depth > 0 || pending != NONE;
    }

    /** True when any scroll has been seen to move the list during this run. */
    public boolean everMoved() {
        return everMoved;
    }

    /** Short description of the last decision, for the panel and the log. */
    public String note() {
        return note;
    }

    /**
     * Notes the rows now on screen. If a scroll was under way and the rows have moved, the
     * scroll is recorded as having worked. Call on every pass over the list, before rows
     * are acted on, so that a scroll which reveals a new retailer is still learned from.
     */
    public void observe(List<AccessibilityTree.RetailerRow> rows) {
        if (pending == NONE) return;
        if (snapshot(rows).equals(baseline)) return;
        if (pending == ACTION) actionProven = true;
        if (pending == DRAG) draggedThisVisit = true;
        pending = NONE;
        rereadDone = false;
        lastDragCancelled = false;
        actionSpent = false;
        stuckDrags = 0;
        voidDrags = 0;
        depth++;
        everMoved = true;
    }

    /**
     * Makes the next move. Call only when every retailer on screen has been dealt with.
     *
     * @param rows     the retailer rows on screen
     * @param screenW  screen width in pixels
     * @param screenH  screen height in pixels
     * @param blocked  where the bot's floating panel sits, or null
     * @param gestures touch dispatcher
     */
    public Step advance(List<AccessibilityTree.RetailerRow> rows, int screenW, int screenH,
                        Rect blocked, AccessibilityTree.Gestures gestures) {
        observe(rows);
        if (pending != NONE) {
            // The rows have not moved. Read once more before judging: the screen may
            // simply not have caught up with the scroll yet, and scrolling again on a
            // stale reading is how rows would be passed over.
            if (!rereadDone) {
                rereadDone = true;
                note = "checking whether the list moved";
                return Step.SETTLING;
            }
            int method = pending;
            pending = NONE;
            rereadDone = false;
            if (method == ACTION) {
                if (actionProven) actionSpent = true;
                else actionUseless = true;
            } else if (lastDragCancelled && voidDrags < MAX_VOID_DRAGS) {
                voidDrags++;
            } else {
                stuckDrags++;
            }
            lastDragCancelled = false;
        }

        int needed = actionProven ? 1 : MAX_STUCK_DRAGS;
        if (stuckDrags >= needed) {
            note = stuckDrags + " swipe" + (stuckDrags == 1 ? "" : "s") + " no longer moved the list";
            return Step.END_OF_LIST;
        }

        baseline = snapshot(rows);
        AccessibilityNodeInfo scrollable = scrollableFor(rows);
        if (scrollable != null && !actionUseless && !actionSpent) {
            boolean accepted;
            try {
                accepted = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            } catch (Exception e) {
                accepted = false;
            }
            if (accepted) {
                pending = ACTION;
                note = "asked the list to scroll";
                return Step.SCROLLED_BY_ACTION;
            }
        }

        Rect area = listArea(rows, scrollable, screenW, screenH);
        int[] plan = dragPlan(area, blocked, stuckDrags + voidDrags);
        if (plan == null) {
            note = "the bot panel is covering the list";
            return Step.BLOCKED;
        }
        if (gestures == null || !gestures.dragUp(plan[0], plan[1], plan[2])) {
            note = "Android refused the swipe";
            return Step.BLOCKED;
        }
        pending = DRAG;
        note = String.format(Locale.US, "swiped up at x=%d from y=%d to y=%d", plan[0], plan[1], plan[2]);
        return Step.SCROLLED_BY_DRAG;
    }

    // ------------------------------------------------------------- geometry

    /** Which retailers are on screen and where: changes whenever the list moves at all. */
    public static String snapshot(List<AccessibilityTree.RetailerRow> rows) {
        StringBuilder builder = new StringBuilder();
        if (rows == null) return "";
        for (AccessibilityTree.RetailerRow row : rows) {
            builder.append(row.id).append('@').append(row.bounds.top).append(';');
        }
        return builder.toString();
    }

    /**
     * The scrolling container the rows sit in: the nearest scrollable ancestor of the first
     * row that also holds the last one. Null when the app does not expose one.
     *
     * <p>Pagers are passed over: asking a pager to "scroll forward" turns to the next tab.</p>
     */
    static AccessibilityNodeInfo scrollableFor(List<AccessibilityTree.RetailerRow> rows) {
        if (rows == null || rows.isEmpty()) return null;
        AccessibilityNodeInfo last = rows.get(rows.size() - 1).node;
        AccessibilityNodeInfo current = rows.get(0).node;
        for (int hop = 0; current != null && hop <= MAX_ANCESTOR_HOPS; hop++) {
            if (current.isScrollable() && !turnsPages(current) && isAncestorOrSelf(current, last)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static boolean turnsPages(AccessibilityNodeInfo node) {
        CharSequence name = node.getClassName();
        if (name == null) return false;
        String lower = name.toString().toLowerCase(Locale.ROOT);
        return lower.contains("viewpager") || lower.contains("horizontalscrollview")
                || lower.contains("tablayout");
    }

    private static boolean isAncestorOrSelf(AccessibilityNodeInfo ancestor, AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int hop = 0; current != null && hop <= MAX_ANCESTOR_HOPS; hop++) {
            if (current.equals(ancestor)) return true;
            current = current.getParent();
        }
        return false;
    }

    /**
     * The part of the screen the list occupies and that is safe to drag in: the scrolling
     * container if there is one, otherwise the span of the rows, kept clear of the status
     * bar at the top and of the system's gesture strip at the bottom (a swipe up that
     * starts on that strip leaves the app).
     */
    static Rect listArea(List<AccessibilityTree.RetailerRow> rows, AccessibilityNodeInfo scrollable,
                         int screenW, int screenH) {
        if (screenW <= 0 || screenH <= 0) return null;
        int safeTop = Math.round(screenH * 0.06f);
        int safeBottom = Math.round(screenH * 0.90f);
        Rect area = null;
        if (scrollable != null) {
            Rect bounds = new Rect();
            scrollable.getBoundsInScreen(bounds);
            area = clip(bounds, screenW, safeTop, safeBottom);
        }
        if (!usable(area, screenW, screenH) && rows != null && !rows.isEmpty()) {
            int left = Integer.MAX_VALUE, right = Integer.MIN_VALUE;
            int top = Integer.MAX_VALUE, bottom = Integer.MIN_VALUE;
            for (AccessibilityTree.RetailerRow row : rows) {
                Rect b = row.bounds;
                if (b == null || b.width() <= 0 || b.height() <= 0) continue;
                left = Math.min(left, b.left);
                right = Math.max(right, b.right);
                top = Math.min(top, b.top);
                bottom = Math.max(bottom, b.bottom);
            }
            if (right > left && bottom > top) {
                area = clip(new Rect(left, top, right, bottom), screenW, safeTop, safeBottom);
            }
        }
        if (!usable(area, screenW, screenH)) {
            // Nothing measurable: the lower middle of the screen is where a list is.
            area = new Rect(0, Math.round(screenH * 0.30f), screenW, safeBottom);
        }
        return area;
    }

    private static Rect clip(Rect bounds, int screenW, int safeTop, int safeBottom) {
        return new Rect(Math.max(0, bounds.left), Math.max(safeTop, bounds.top),
                Math.min(screenW, bounds.right), Math.min(safeBottom, bounds.bottom));
    }

    private static boolean usable(Rect area, int screenW, int screenH) {
        return area != null && area.width() >= screenW * 0.30f && area.height() >= screenH * 0.12f;
    }

    /**
     * Where to drag: {x, fromY, toY}, or null when no clear stretch of the list is left.
     *
     * <p>Three columns are considered (middle, right, left). In each, the longest stretch
     * not covered by {@code blocked} is taken, and the drag runs from 85% down that stretch
     * to 15% - at most 70% of the list's height. {@code attempt} rotates through the
     * columns so a repeat does not press the very same spot.</p>
     */
    static int[] dragPlan(Rect area, Rect blocked, int attempt) {
        if (area == null || area.width() <= 0 || area.height() <= 0) return null;
        float[] columns = {0.50f, 0.80f, 0.20f};
        int margin = 12;
        int minLength = Math.max(100, Math.round(area.height() * 0.30f));
        List<int[]> plans = new ArrayList<>(3);
        for (float column : columns) {
            int x = area.left + Math.round(area.width() * column);
            int top = area.top;
            int bottom = area.bottom;
            if (blocked != null && x >= blocked.left - margin && x < blocked.right + margin
                    && blocked.bottom + margin > area.top && blocked.top - margin < area.bottom) {
                int above = (blocked.top - margin) - area.top;
                int below = area.bottom - (blocked.bottom + margin);
                if (above >= below) bottom = blocked.top - margin;
                else top = blocked.bottom + margin;
            }
            int length = bottom - top;
            if (length < minLength) continue;
            plans.add(new int[]{x, top + Math.round(length * 0.85f), top + Math.round(length * 0.15f), length});
        }
        if (plans.isEmpty()) return null;
        // Longest clear stretch first; equal stretches keep the middle-right-left order.
        for (int i = 1; i < plans.size(); i++) {
            int[] plan = plans.get(i);
            int j = i - 1;
            while (j >= 0 && plans.get(j)[3] < plan[3]) {
                plans.set(j + 1, plans.get(j));
                j--;
            }
            plans.set(j + 1, plan);
        }
        int[] chosen = plans.get(Math.max(0, attempt) % plans.size());
        return new int[]{chosen[0], chosen[1], chosen[2]};
    }
}
