import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import com.vrksignatures.sudhaautobot.AccessibilityTree;

import java.util.ArrayList;
import java.util.List;

/**
 * A model of the Customers screen with "Show Retailers" ticked: a fixed header, then a
 * list of rows that scrolls under it. The first row is the distributor (SM65); the rest are
 * retailers SMR2255, SMR2254, ... as on the device.
 *
 * <p>How the list answers a scroll request is the part nobody has seen on the phone, so it
 * is a knob ({@link #mode}), and the scroller is tested against every setting.</p>
 */
class RetailerListModel implements AccessibilityTree.Gestures {
    /** How the list container behaves towards the accessibility service. */
    enum Mode {
        /** Scrolls a page per request and refuses at the end (a well-behaved RecyclerView). */
        ACTION_WORKS,
        /** Says "done" to every request and never moves. */
        ACTION_ACCEPTED_BUT_IGNORED,
        /** Is marked scrollable but refuses every request. */
        ACTION_REFUSED,
        /** Is not marked scrollable at all (custom-drawn or cross-platform list). */
        NOT_SCROLLABLE
    }

    static final int SCREEN_W = 720, SCREEN_H = 1600;
    static final int LIST_TOP = 430, ROW_H = 200, TOUCH_SLOP = 16;

    final List<String> ids = new ArrayList<>();
    Mode mode = Mode.ACTION_WORKS;
    /** Bottom edge of the list on screen (a bottom bar may sit below it). */
    int listBottom = SCREEN_H;
    /** The bot's floating panel: swallows any touch that starts on it. */
    Rect panel = new Rect(16, 100, 500, 420);
    /** A sideways-scrolling strip above the list that comes FIRST in the tree. */
    boolean decoyStripFirst;
    /** Wrap the list in a tab pager (scrollable, but "forward" turns the page). */
    boolean insidePager;
    /** Extra distance the list runs on after a drag (a fling), in pixels. */
    int flingAfterDrag;

    /** The figure printed above the list; by default every row, the distributor included. */
    int headerCount = -1;

    int scroll;
    int actionRequests, actionsOnDecoy, actionsOnPager, drags, dragsOnPanel;
    /** Requests to scroll up (only the scroll memory makes them). */
    int backRequests;
    /** Longest drag sent so far, in pixels. */
    int longestDrag;
    /** Every retailer that has been on screen at some point, in order of first appearance. */
    final List<String> everShown = new ArrayList<>();

    RetailerListModel(int retailers) {
        ids.add("SM65");
        for (int i = 0; i < retailers; i++) ids.add("SMR" + (2255 - i));
    }

    int maxScroll() {
        return Math.max(0, ids.size() * ROW_H - (listBottom - LIST_TOP));
    }

    private void scrollBy(int dy) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll + dy));
    }

    AccessibilityNodeInfo build() {
        AccessibilityNodeInfo root = node("android.widget.FrameLayout", null, 0, 0, SCREEN_W, SCREEN_H);
        root.add(node("android.widget.TextView", "Customers", 0, 60, SCREEN_W, 160));
        root.add(node("android.widget.TextView",
                "Total Customers: " + (headerCount >= 0 ? headerCount : ids.size()), 30, 180, 400, 230));
        root.add(node("android.widget.TextView", "All Distributors", 30, 250, 400, 300));
        root.add(node("android.widget.CheckBox", "Show Retailers", 30, 340, 400, 400));

        if (decoyStripFirst) {
            AccessibilityNodeInfo strip = node("android.widget.HorizontalScrollView", null, 0, 300, SCREEN_W, 340);
            strip.scrollable = true;
            strip.handler = (n, action) -> {
                if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) actionsOnDecoy++;
                return true;                       // "scrolled" - sideways, which helps nobody
            };
            root.add(strip);
        }

        AccessibilityNodeInfo holder = root;
        if (insidePager) {
            AccessibilityNodeInfo pager = node("androidx.viewpager.widget.ViewPager", null, 0, LIST_TOP, SCREEN_W, listBottom);
            pager.scrollable = true;
            pager.handler = (n, action) -> {
                if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) actionsOnPager++;
                return true;
            };
            root.add(pager);
            holder = pager;
        }

        AccessibilityNodeInfo list = node("androidx.recyclerview.widget.RecyclerView", null, 0, LIST_TOP, SCREEN_W, listBottom);
        list.scrollable = mode != Mode.NOT_SCROLLABLE;
        list.handler = (n, action) -> {
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                // Up a page; refused at the top, as a RecyclerView does.
                backRequests++;
                if (mode != Mode.ACTION_WORKS || scroll <= 0) return false;
                scrollBy(-(listBottom - LIST_TOP));
                return true;
            }
            if (action != AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return false;
            actionRequests++;
            switch (mode) {
                case ACTION_WORKS:
                    if (scroll >= maxScroll()) return false;
                    scrollBy(listBottom - LIST_TOP);
                    return true;
                case ACTION_ACCEPTED_BUT_IGNORED:
                    return true;
                default:
                    return false;
            }
        };
        holder.add(list);

        for (int i = 0; i < ids.size(); i++) {
            int top = LIST_TOP + i * ROW_H - scroll;
            int bottom = top + ROW_H;
            if (bottom <= LIST_TOP || top >= listBottom) continue;     // not attached
            int shownTop = Math.max(top, LIST_TOP), shownBottom = Math.min(bottom, listBottom);
            AccessibilityNodeInfo row = node("android.view.ViewGroup", null, 0, shownTop, SCREEN_W, shownBottom);
            row.clickable = true;
            row.handler = (n, action) -> action == AccessibilityNodeInfo.ACTION_CLICK;
            row.add(node("android.widget.TextView", "Delivery Point (Retailer)", 30, shownTop, 500, shownTop + 1));
            row.add(node("android.widget.TextView", "Customer Id: " + ids.get(i), 30, shownTop, 500, shownBottom));
            list.add(row);
            if (ids.get(i).startsWith("SMR") && !everShown.contains(ids.get(i))) everShown.add(ids.get(i));
        }
        return root;
    }

    private static AccessibilityNodeInfo node(String className, String text, int l, int t, int r, int b) {
        AccessibilityNodeInfo node = new AccessibilityNodeInfo();
        node.className = className;
        node.text = text;
        node.bounds.set(l, t, r, b);
        return node;
    }

    // ------------------------------------------------------------- touches

    @Override
    public boolean tap(Rect bounds) {
        return true;
    }

    /** The old fast swipe: only ever called by the previous scrolling code. */
    @Override
    public boolean swipeUp(Rect area) {
        return dragUp(Math.round(area.exactCenterX()), area.top + Math.round(area.height() * 0.78f),
                area.top + Math.round(area.height() * 0.28f));
    }

    @Override
    public boolean dragUp(int x, int fromY, int toY) {
        drags++;
        longestDrag = Math.max(longestDrag, fromY - toY);
        if (panel != null && panel.contains(x, fromY)) {
            dragsOnPanel++;
            return true;                           // dispatched, and swallowed by the panel
        }
        boolean onList = x >= 0 && x < SCREEN_W && fromY >= LIST_TOP && fromY < listBottom;
        if (onList) scrollBy(Math.max(0, fromY - toY - TOUCH_SLOP) + flingAfterDrag);
        return true;
    }
}
