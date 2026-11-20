package com.vrksignatures.sudhaautobot;

import java.util.Locale;

/**
 * Short messages the Sudha app shows that the bot has to act on.
 *
 * <p>"Today Order already created" is shown as a <b>toast</b> when PLACE ORDER is pressed
 * for a retailer who has already ordered today. A toast is not part of the window's
 * accessibility tree, so reading the screen never finds it; it reaches the service only as
 * a notification-state event. The order form simply stays where it is, which is why the
 * bot used to sit there waiting for a payment screen that was never going to open.</p>
 *
 * <p>"Orders list is empty. Retry?" is shown as a bar at the bottom of the retailer's
 * "New Order Milk" page (the one opened with Milk), with a YES button, when the app finds
 * no order to list. The page under it is white: a title, the month, the "+" and nothing
 * else. The bar goes away by itself after about three seconds and the white page stays.
 * None of the words the bot knows that page by ("Order created on", "Order Qty", ...) are
 * there, so it used to wait on "a recognised Sudha screen" until it stopped the run.</p>
 */
public final class AppNotices {
    private AppNotices() {
    }

    /**
     * True when {@code said} tells us the retailer already has an order for today.
     *
     * <p>Seen on the device: "Today Order already created". Small variations in wording
     * are accepted, but the sentence must be about an order that already exists.</p>
     */
    public static boolean isAlreadyOrdered(CharSequence said) {
        if (said == null) return false;
        String text = said.toString().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (text.isEmpty() || text.length() > 160) return false;
        if (!text.contains("already")) return false;
        if (!text.contains("order")) return false;
        return text.contains("already created")
                || text.contains("already placed")
                || text.contains("already exist")
                || text.contains("already done")
                || text.contains("already submitted");
    }

    // ------------------------------------------------- "Orders list is empty"

    /** Words that are only on screen when an order card, or the order form, is showing. */
    private static final String[] ORDER_PAGE_HAS_CONTENT = {
            "order created on", "order qty", "order id", "products",
            "place order", "view order summary", "mqty", "eqty", "copy order", "item group",
    };

    /** Words of the other pages and of the app's own "please wait" boxes. */
    private static final String[] NOT_THE_ORDER_PAGE = {
            "customer details", "view orders", "distributor name", "total customers",
            "show retailers", "all distributors", "loading", "getting placed", "in progress",
            "please wait", "making payment", "payment", "success", "zone", "party",
    };

    private static String flat(CharSequence text) {
        return text == null ? "" : text.toString().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static boolean hasAny(String text, String[] words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    /**
     * True when the app is saying that the retailer's order list is empty.
     *
     * <p>Seen on the device: <b>"Orders list is empty. Retry?"</b> with a YES button. Given
     * either the words of a toast or everything written on the screen. The singular
     * ("Order list is empty") and the form without "is" are accepted too. A screen that
     * shows an order card or the order form is never taken for it, whatever else it says.</p>
     */
    public static boolean isEmptyOrderList(CharSequence said) {
        String text = flat(said);
        if (text.isEmpty()) return false;
        if (hasAny(text, ORDER_PAGE_HAS_CONTENT)) return false;
        return text.contains("orders list is empty") || text.contains("order list is empty")
                || text.contains("orders list empty") || text.contains("order list empty");
    }

    /**
     * True for the white "New Order Milk" page that is left once the "Orders list is empty"
     * bar has gone: the title is there, and no order card, no order form, no other page and
     * no "please wait" box. Given everything written on the screen.
     *
     * <p>The same page looks like this for a moment while it is still opening, so one look
     * is not proof: the caller has to see it stay like this for a few seconds.</p>
     */
    public static boolean isBlankOrderList(CharSequence screen) {
        String text = flat(screen);
        if (!text.contains("new order milk")) return false;
        if (text.length() > 160) return false;
        return !hasAny(text, ORDER_PAGE_HAS_CONTENT) && !hasAny(text, NOT_THE_ORDER_PAGE);
    }
}
