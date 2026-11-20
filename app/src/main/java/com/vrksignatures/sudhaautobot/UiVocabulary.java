package com.vrksignatures.sudhaautobot;

import java.util.ArrayList;
import java.util.List;

/**
 * The words the bot relies on in the Sudha app, written down in one place so that Aakriti
 * can notice when one of them has been renamed.
 *
 * <p>This table does not drive the bot - {@link SudhaAccessibilityService} still looks for
 * its own words exactly as before. It only says, for each of those words: which screen it
 * belongs to, whether it is something to read or something to press, and whether a new name
 * for it may be adopted on the spot or has to be shown to a person first.</p>
 *
 * <p>The payment and success screens are deliberately <b>not</b> here. Aakriti never learns
 * a new name for anything on a screen where money moves; a change there is reported to a
 * person instead.</p>
 */
public final class UiVocabulary {

    public enum Role {
        /** Text the bot reads to know where it is. */
        MARKER,
        /** Something the bot presses; a new name must belong to something pressable. */
        BUTTON
    }

    public enum Trust {
        /** A confident new name is used straight away. */
        AUTO,
        /** A confident new name is only proposed; a person has to allow it. */
        ASK
    }

    public static final class Slot {
        public final String key;
        public final String screen;
        public final Role role;
        public final Trust trust;
        /** What it is, for a person. */
        public final String says;
        /** The names the bot knows today, lower case; the first is the main one. */
        public final String[] accepts;

        Slot(String key, String screen, Role role, Trust trust, String says, String... accepts) {
            this.key = key;
            this.screen = screen;
            this.role = role;
            this.trust = trust;
            this.says = says;
            this.accepts = accepts;
        }

        public String primary() {
            return accepts[0];
        }

        public boolean accepts(String phraseLower) {
            for (String phrase : accepts) {
                if (phrase.equals(phraseLower)) return true;
            }
            return false;
        }
    }

    public static final class Screen {
        public final String key;
        public final String title;
        /** Bot states in which this screen is the one expected. */
        public final String[] states;
        /** Other words that belong to this screen; they show that it is this screen. */
        public final String[] anchors;
        /** How many anchors must be present before a rename is believed here. */
        public final int needAnchors;
        /** Words that belong to a different screen; any one of them rules this screen out. */
        public final String[] excludes;

        Screen(String key, String title, String[] states, int needAnchors, String[] anchors,
               String[] excludes) {
            this.key = key;
            this.title = title;
            this.states = states;
            this.needAnchors = needAnchors;
            this.anchors = anchors;
            this.excludes = excludes;
        }
    }

    /** Stands for "three or more retailer ids are on screen" in a screen's anchors. */
    public static final String ANCHOR_RETAILER_ROWS = "#retailer-rows";

    public static final Screen[] SCREENS = {
            // "Total Customers" is printed on the home screen and above the list alike, so
            // home is only believed when nothing of the list is showing.
            new Screen("home", "Home", new String[]{"HOME"}, 1,
                    new String[]{"total customers", "daily orders", "daily order"},
                    new String[]{"show retailers", "all distributors", ANCHOR_RETAILER_ROWS}),
            new Screen("list", "Retailer list", new String[]{"RETAILER_LIST"}, 1,
                    new String[]{ANCHOR_RETAILER_ROWS, "all distributors", "show retailers"},
                    new String[]{"customer details", "order created on", "mqty"}),
            new Screen("details", "Customer details", new String[]{"DETAILS"}, 1,
                    new String[]{"customer details", "view orders", "distributor name", "transactions"},
                    new String[]{"order created on", "mqty", "eqty"}),
            // History and the order form carry the same title ("New Order Milk"), so the
            // title proves neither.
            new Screen("history", "Order history", new String[]{"HISTORY"}, 1,
                    new String[]{"order created on", "order qty", "order id"},
                    new String[]{"mqty", "eqty", "view order summary"}),
            new Screen("form", "Order form", new String[]{"COPY_ORDER", "PLACE_ORDER"}, 2,
                    new String[]{"mqty", "eqty", "item group", "view order summary", "copy order",
                            "place order", "party"},
                    new String[]{"order created on", "customer details"}),
    };

    public static final Slot[] SLOTS = {
            new Slot("home.total", "home", Role.MARKER, Trust.AUTO,
                    "the customer count on the home screen", "total customers"),
            new Slot("home.customers", "home", Role.BUTTON, Trust.AUTO,
                    "the tile that opens the customer list",
                    "customers", "customer", "retailers", "customer list"),
            new Slot("list.total", "list", Role.MARKER, Trust.AUTO,
                    "the customer count above the list", "total customers"),
            new Slot("list.show", "list", Role.MARKER, Trust.AUTO,
                    "the Show Retailers tick box", "show retailers", "show retailer"),
            new Slot("details.title", "details", Role.MARKER, Trust.AUTO,
                    "the title of the details screen", "customer details"),
            new Slot("details.orders", "details", Role.MARKER, Trust.AUTO,
                    "the View Orders section", "view orders"),
            new Slot("details.distributor", "details", Role.MARKER, Trust.AUTO,
                    "the distributor line", "distributor name"),
            new Slot("details.milk", "details", Role.BUTTON, Trust.AUTO,
                    "the Milk tab", "milk", "milk order", "milk orders"),
            new Slot("history.created", "history", Role.MARKER, Trust.AUTO,
                    "the date line of a past order", "order created on"),
            new Slot("history.qty", "history", Role.MARKER, Trust.AUTO,
                    "the quantity line of a past order", "order qty"),
            new Slot("form.copy", "form", Role.BUTTON, Trust.AUTO,
                    "the Copy Order link", "copy order"),
            // The one button that spends money. A new name for it is never used unasked.
            new Slot("form.place", "form", Role.BUTTON, Trust.ASK,
                    "the PLACE ORDER button", "place order"),
    };

    private UiVocabulary() {
    }

    public static Slot slot(String key) {
        for (Slot slot : SLOTS) {
            if (slot.key.equals(key)) return slot;
        }
        return null;
    }

    public static Screen screen(String key) {
        for (Screen screen : SCREENS) {
            if (screen.key.equals(key)) return screen;
        }
        return null;
    }

    /** The screen the bot expects while in {@code state}, or null. */
    public static Screen screenForState(String state) {
        for (Screen screen : SCREENS) {
            for (String name : screen.states) {
                if (name.equals(state)) return screen;
            }
        }
        return null;
    }

    public static List<Slot> slotsOf(String screenKey) {
        List<Slot> out = new ArrayList<>();
        for (Slot slot : SLOTS) {
            if (slot.screen.equals(screenKey)) out.add(slot);
        }
        return out;
    }

    /** True for any phrase the bot already knows - a slot's name or a screen's anchor. */
    public static boolean isKnown(String phraseLower) {
        for (Slot slot : SLOTS) {
            if (slot.accepts(phraseLower)) return true;
        }
        for (Screen screen : SCREENS) {
            for (String anchor : screen.anchors) {
                if (anchor.equals(phraseLower)) return true;
            }
        }
        return false;
    }
}
