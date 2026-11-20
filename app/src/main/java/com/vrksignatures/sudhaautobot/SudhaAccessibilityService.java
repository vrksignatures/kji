package com.vrksignatures.sudhaautobot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;

/**
 * The bot. One tick per UI change (plus debounced retries) walks this machine:
 *
 * <pre>
 * HOME --"Customers"--> RETAILER_LIST --[ensure expanded]--> click an unprocessed SMR
 *   --> DETAILS --"Milk"--> HISTORY --"+"--> ORDER FORM --"Copy Order"--> CALENDAR
 *   --> pick T-1/T-2/T-3 --> items?>0 --> PLACE ORDER --> PAY/PROCEED/CONFIRM/OK
 *   --> SUCCESS ("Done"/"OK"/BACK) --> RETAILER_LIST (re-expand if collapsed) --> ...
 * </pre>
 *
 * <h3>Safety model</h3>
 * <ul>
 *   <li><b>Package isolation.</b> Nothing is read or tapped unless the window belongs
 *       to the configured package.</li>
 *   <li><b>Strict identity.</b> Only {@code SMR<digits>} tokens are ever clicked, and
 *       the details screen must show the same id before "Milk" is opened.</li>
 *   <li><b>No duplicates.</b> Any ledger record removes the retailer from today's
 *       queue; a pre-submit "pending" marker is flushed to disk before PLACE ORDER.</li>
 *   <li><b>Bounded retries.</b> Every wait has a tick budget; when it runs out the run
 *       pauses with a precise message instead of tapping randomly.</li>
 * </ul>
 */
public final class SudhaAccessibilityService extends AccessibilityService {
    private static final String TAG = "SudhaAutoBot";

    public static final String ACTION_START = "com.vrksignatures.sudhaautobot.action.START";
    public static final String ACTION_PAUSE = "com.vrksignatures.sudhaautobot.action.PAUSE";
    public static final String ACTION_RESET = "com.vrksignatures.sudhaautobot.action.RESET";
    public static final String ACTION_SHOW_PANEL = "com.vrksignatures.sudhaautobot.action.SHOW_PANEL";
    /** Read-only: dumps the current screen tree to a file. Safe while paused or running. */
    public static final String ACTION_CAPTURE = "com.vrksignatures.sudhaautobot.action.CAPTURE";
    public static final String ACTION_SHARE_CAPTURE = "com.vrksignatures.sudhaautobot.action.SHARE_CAPTURE";
    public static final String ACTION_STOP_ALARM = "com.vrksignatures.sudhaautobot.action.STOP_ALARM";

    // Timing. Oppo/Vivo mid-range devices repaint slowly, so these are generous.
    private static final long DEBOUNCE_MS = 320;
    private static final long IDLE_DELAY_MS = 1200;
    private static final long ACTION_DELAY_MS = 900;
    private static final long FAST_DELAY_MS = 550;
    private static final long MIN_ACTION_GAP_MS = 420;

    // Bounded waits (in ticks).
    private static final int MAX_HOME_TICKS = 14;
    private static final int MAX_SHOW_RETAILERS_CLICKS = 4;
    private static final int MAX_DETAILS_TICKS = 12;
    /** History ticks = tap+wait cycles. 14 gives ~7 real attempts (2 ticks each). */
    private static final int MAX_HISTORY_TICKS = 14;
    private static final int MAX_COPY_FORM_TICKS = 10;
    private static final int MAX_CALENDAR_WAIT_TICKS = 10;
    /** OK presses allowed for a single popup before giving up on it. */
    private static final int MAX_POPUP_DISMISS_TICKS = 8;
    /**
     * Attempts allowed to find a day cell before giving up on the retailer.
     *
     * <p>Small on purpose: if the grid is custom-drawn and exposes no cell text, retrying
     * costs time and will never succeed. Three covers "still rendering".</p>
     */
    private static final int MAX_CALENDAR_MISSES = 3;
    /**
     * Looks allowed at the calendar, per date, for the highlight to land on the requested
     * day. Each look that finds it elsewhere presses the day again, so this is also the
     * number of presses. When it runs out the dialog is closed with CANCEL, never OK.
     */
    private static final int MAX_CALENDAR_VERIFY_TICKS = 5;
    /** Ticks spent closing a date dialog that is open although no copy is in progress. */
    private static final int MAX_STRAY_CALENDAR_TICKS = 3;
    private static final int MAX_DATE_PICK_TICKS = 6;
    private static final int MAX_COPY_RESULT_TICKS = 12;
    private static final int MAX_ORDER_READY_TICKS = 8;
    /**
     * How long to wait after confirming the date before concluding no error dialog is
     * coming. At the 900 ms action delay this is roughly 3 seconds, matching the "wait
     * two or three seconds, then assume it worked" rule.
     */
    private static final int COPY_SETTLE_TICKS = 3;
    private static final int MAX_PAYMENT_ACTIONS = 12;
    private static final int MAX_PENDING_TICKS = 40;
    private static final int MAX_BACK_STEPS = 6;
    private static final int MAX_SAME_ROW_CLICKS = 3;
    /** Guards against counting one popup twice and burning two dates for one error. */
    private boolean awaitingRetryAfterPopup;
    /** Dismissals of a popup that will not go away. */
    private int popupDismissTicks;

    /** Calendar dates tried per retailer before giving up on that retailer. */
    private static final int MAX_DATE_ATTEMPTS = DateRollbackPolicy.MAX_LOOK_BACK_DAYS;
    /** Restarts of the whole copy flow; must exceed MAX_DATE_ATTEMPTS so it never cuts it short. */
    private static final int MAX_COPY_CYCLES = MAX_DATE_ATTEMPTS + 2;
    private static final int MAX_UNKNOWN_TICKS = 16;

    /** Sub-phase of the Copy Order dialog dance. */
    private enum CopyPhase {
        /** No copy flow running. */
        NONE,
        /** "New Order Milk" is open; find and tap Copy Order. */
        OPEN_COPY,
        /** Copy Order was tapped; wait for the calendar dialog. */
        AWAIT_CALENDAR,
        /** Calendar visible; tap the exact day. */
        PICK_DATE,
        /** Day tapped; tap OK/CONFIRM. */
        CONFIRM_DATE,
        /** Dialog accepted; wait to see whether an error dialog appears. */
        AWAIT_RESULT
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final WorkflowStateMachine workflow = new WorkflowStateMachine();

    private DailyLedger ledger;
    private AlarmController alarmController;
    private int preShowRetailersTotal = -1;
    private int networkRetryCount = 0;
    private int filterCheckDoneVisit = -1;
    private FloatingPanelController panel;
    private SharedPreferences prefs;

    private String targetPackage = "";
    private int expectedCount = RetailerPolicy.EXPECTED_RETAILER_COUNT;
    /** The figure from the setup screen; used when the app itself prints no total. */
    private int configuredCount = RetailerPolicy.EXPECTED_RETAILER_COUNT;

    private boolean running;
    private boolean receiverRegistered;
    private int cachedWidth;
    private int cachedHeight;
    private int cachedDensity;
    private boolean pendingRecovery;
    private long lastActionAt;
    private long runStartedAt;

    // Per-screen budgets.
    private int homeTicks;
    private int detailsTicks;
    private int historyTicks;
    /** True on the tick right after a + tap, before judging whether it worked. */
    private boolean historyAwaitingResult;
    private int copyFormTicks;
    private int calendarWaitTicks;
    private int datePickTicks;
    private int resultTicks;
    private int orderReadyTicks;
    private int unknownTicks;
    private int backSteps;
    private int paymentActions;
    private boolean returningToRetailerList;
    private int pendingTicks;
    private String lastClickedRetailerId;
    private int sameRetailerClickCount;
    private int copyCycles;

    // Retailer list bookkeeping.
    private int showRetailersClicks;
    private int listExpansionCooldownTicks;
    private int scrollsWithoutProgress;
    /**
     * Set when the last "+" tap attempt was aimed with a real screenshot location, so a
     * retry after it fails knows to sit tight and wait rather than tapping blind.
     */
    private boolean fabViaScreenshot;
    /** True while a screenshot request is in flight; prevents piling up requests. */
    private boolean fabScanInFlight;
    /** Retries waited out after a screenshot aim landed nothing, before giving up. */
    private static final int FAB_SCREENSHOT_GRACE = 2;

    /** Consecutive list passes where nothing could be scrolled or tapped. */
    private int idleListPolls;
    private String lastListFingerprint = "";
    /** Scrolls the retailer list and checks that it moved; see {@link ListScroller}. */
    private final ListScroller listScroller = new ListScroller();
    /** Until this moment a swipe on the list is still under way; nothing may be pressed. */
    private long listBusyUntil;
    /** Length of the last swipe, start to lift. */
    private long lastDragMs;

    // Copy Order bookkeeping.
    private CopyPhase copyPhase = CopyPhase.NONE;
    private int dateAttempt;
    /**
     * Failed attempts to <b>locate</b> a day cell, kept separate from {@link #dateAttempt}.
     *
     * <p>These are bot-side misses, not the app's answer. Conflating them was a real defect:
     * an unreadable cell was funnelled into {@code onCopyFailure()}, which advanced the date
     * ladder and left the calendar open, so a single unreadable cell burned a date slot and
     * then abandoned the retailer.</p>
     */
    private int calendarMisses;
    /** Looks taken at the calendar for the current date; see MAX_CALENDAR_VERIFY_TICKS. */
    private int calendarVerifyTicks;
    /** "Previous month" presses made for the current date. */
    private int calendarMonthSteps;
    /** True while a screenshot of the calendar is being taken; prevents piling up requests. */
    private boolean calendarScanInFlight;
    /** What the last look at the calendar's pixels said, for the panel and the skip reason. */
    private String calendarScreenNote = "";
    private int strayCalendarTicks;
    private LocalDate[] candidates = new LocalDate[0];
    /**
     * T for the retailer in hand: the date the calendar had highlighted (the orange disc)
     * the first time it opened for this retailer. Every date the bot chooses is counted
     * back from it - T-1, then T-2 ... T-5. It is read from the dialog; the phone's clock
     * plays no part. Null until the dialog has been read.
     */
    private LocalDate calendarAnchor;
    /** Ticks spent trying to read {@link #calendarAnchor} from an open dialog. */
    private int anchorTicks;
    private static final int MAX_ANCHOR_TICKS = 6;

    /** Decides which retailer comes next: always the one after the retailer handled last. */
    private final RetailerSequence sequence = new RetailerSequence();

    /** When PLACE ORDER was pressed for the order in flight (0 = not known). */
    private long placeTappedAt;
    /** Consecutive looks that found the order form still open after PLACE ORDER. */
    private int formAfterPlaceTicks;
    /**
     * How long the order form may stay open after PLACE ORDER before the bot stops waiting
     * for a payment screen. On the device the payment screen follows in about four seconds.
     */
    private static final long PLACE_NO_RESPONSE_MS = 15_000;
    private static final int MIN_FORM_AFTER_PLACE_TICKS = 4;
    /** Looks at the app's "Loading" box while walking back to the list. */
    private int busyTicks;
    /** True once Back has been tried against the "Loading" box now on screen. */
    private boolean busyBackSent;
    private static final int MAX_BUSY_TICKS = 25;
    /** True once this run has gone back to the top for retailers that were passed over. */
    private boolean sweptFromTop;
    /** Times the Sudha app was re-opened because Back presses did not reach the list. */
    private int relaunches;
    private static final int MAX_RELAUNCHES = 3;

    /** Coordinate gestures for rows that ignore ACTION_CLICK. */
    private final AccessibilityTree.Gestures gestures = new AccessibilityTree.Gestures() {
        @Override
        public boolean tap(Rect bounds) {
            if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) return false;
            Path path = new Path();
            path.moveTo(bounds.exactCenterX(), bounds.exactCenterY());
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 60));
            try {
                return dispatchGesture(builder.build(), null, null);
            } catch (Exception e) {
                Log.w(TAG, "dispatchGesture(tap) failed", e);
                return false;
            }
        }

        @Override
        public boolean swipeUp(Rect area) {
            if (area == null || area.width() <= 0 || area.height() < 40) return false;
            float x = area.exactCenterX();
            float fromY = area.top + area.height() * 0.78f;
            float toY = area.top + area.height() * 0.28f;
            Path path = new Path();
            path.moveTo(x, fromY);
            path.lineTo(x, toY);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 260));
            try {
                return dispatchGesture(builder.build(), null, null);
            } catch (Exception e) {
                Log.w(TAG, "dispatchGesture(swipe) failed", e);
                return false;
            }
        }

        /**
         * A finger dragged straight up and lifted without a flick.
         *
         * <p>A plain fast swipe ends in a fling: the list keeps running after the finger
         * lifts, by an amount nobody controls, and rows fly past unread. Here the finger
         * moves at walking pace and then rests for a moment before lifting, so the list
         * stops exactly where it was dragged to. The rest is drawn as a tiny sideways
         * shuffle (3 px, at the same height): one continuous stroke, and no vertical
         * speed left at the moment of lifting.</p>
         */
        @Override
        public boolean dragUp(int x, int fromY, int toY) {
            int distance = fromY - toY;
            if (distance < 40) return false;
            final float pxPerMs = 2.0f;
            // Long enough that the last touch samples before lifting are all at rest, even
            // on Android versions that sample a gesture only every 100 ms.
            final long restMs = 220;
            Path path = new Path();
            path.moveTo(x, fromY);
            path.lineTo(x, toY);
            float shuffle = restMs * pxPerMs;
            boolean right = true;
            for (float done = 0; done < shuffle; done += 3f) {
                path.lineTo(right ? x + 1.5f : x - 1.5f, toY);
                right = !right;
            }
            long duration = Math.round((distance + shuffle) / pxPerMs);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
            try {
                boolean sent = dispatchGesture(builder.build(), new GestureResultCallback() {
                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        // Usually a real finger on the screen. Not the list's fault, so it
                        // must not count towards "the list has ended".
                        Log.w(TAG, "List swipe was cancelled");
                        listScroller.dragCancelled();
                    }
                }, null);
                if (sent) {
                    lastDragMs = duration;
                    listBusyUntil = System.currentTimeMillis() + duration + 120;
                }
                return sent;
            } catch (Exception e) {
                Log.w(TAG, "dispatchGesture(drag) failed", e);
                return false;
            }
        }
    };

    private final Runnable stepRunnable = this::step;
    private final Runnable debouncedStep = () -> {
        // While a timed sequence of the bot's own is under way (see quick()), the next look
        // is already booked; a change on the screen must not push it back.
        if (running && System.currentTimeMillis() >= this.quietUntil) scheduleStep(DEBOUNCE_MS);
    };

    private final BroadcastReceiver controlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null || intent.getAction() == null ? "" : intent.getAction();
            // if/else instead of a switch: ACTION_* fields belong to the enclosing class and
            // are not compile-time constants inside this anonymous subclass.
            if (ACTION_START.equals(action)) {
                startRun();
            } else if (ACTION_PAUSE.equals(action)) {
                pause("Paused by user");
            } else if (ACTION_RESET.equals(action)) {
                resetLedger();
            } else if (ACTION_SHOW_PANEL.equals(action)) {
                ensurePanel();
            } else if (ACTION_CAPTURE.equals(action)) {
                captureScreen(true);
            } else if (ACTION_SHARE_CAPTURE.equals(action)) {
                shareLatestCapture();
            } else if (ACTION_STOP_ALARM.equals(action)) {
                stopAlarm();
            }
        }
    };

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        reloadConfig();
        ledger = new DailyLedger(this);
        alarmController = new AlarmController(this);
        registerControlReceiver();
        applyServiceInfo();
        dayWatchBegin();
        workflow.setListener((state, retailerId, message) -> refreshPanel());
        ensurePanel();
        refreshPanel();
        Log.i(TAG, "Connected; target=" + targetPackage + " session=" + (ledger == null ? "-" : ledger.sessionDate()));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!running || event == null) return;
        CharSequence packageName = event.getPackageName();
        if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            // A toast. It is not in any window's tree, so this event is the only place its
            // words can be read. Real status-bar notifications arrive here too and carry a
            // Notification object; those are not what the app is saying on screen.
            if (event.getParcelableData() instanceof android.app.Notification) return;
            String said = spokenText(event);
            if (AppNotices.isAlreadyOrdered(said)) {
                Log.i(TAG, "Toast from " + packageName + ": " + said);
                onAlreadyOrdered(said);
            } else if (said.toLowerCase(Locale.ROOT).contains("failed to connect")
                    || said.toLowerCase(Locale.ROOT).contains("internal error")) {
                Log.w(TAG, "Network/Server toast from " + packageName + ": " + said);
                onNetworkOrUnknownRetry("App toast: " + said);
            } else if (AppNotices.isEmptyOrderList(said)
                    && RetailerPolicy.isRetailerId(workflow.retailerId())) {
                // The same message, on a build that shows it as a toast instead of a bar.
                // Only with a retailer open: a toast alone does not say which page is up.
                Log.i(TAG, "Toast from " + packageName + ": " + said);
                onEmptyOrderList(said, false);
            }
            return;
        }
        if (packageName == null || !targetPackage.contentEquals(packageName)) return;
        // Collapse the event storm into a single delayed inspection.
        handler.removeCallbacks(debouncedStep);
        handler.postDelayed(debouncedStep, DEBOUNCE_MS);
        // Fast mode: also peek at the screen as soon as it has stopped changing.
        tempoChanged(event);
    }

    /** The words carried by an event (for a toast: its message). */
    private static String spokenText(AccessibilityEvent event) {
        StringBuilder words = new StringBuilder();
        try {
            List<CharSequence> parts = event.getText();
            if (parts != null) {
                for (CharSequence part : parts) {
                    if (part == null) continue;
                    if (words.length() > 0) words.append(' ');
                    words.append(part);
                }
            }
            if (words.length() == 0 && event.getContentDescription() != null) {
                words.append(event.getContentDescription());
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read the event text", e);
        }
        return words.toString().trim();
    }

    /**
     * The app has said "Today Order already created": the retailer in hand already has
     * today's order. There is nothing to wait for - no payment screen is coming - so the
     * retailer is recorded as done and the bot leaves at once for the next SMR.
     *
     * <p>Before this existed the bot never saw the message (it is a toast), kept waiting
     * for the payment screen, and finally stopped the whole run with "never reached a
     * payment or success screen".</p>
     */
    private void onAlreadyOrdered(String said) {
        if (!running || ledger == null) return;
        // Already on the way out (for instance the message was seen twice).
        if (returningToRetailerList) return;
        String retailerId = workflow.retailerId();
        if (!RetailerPolicy.isRetailerId(retailerId)) retailerId = ledger.pendingId();
        if (!RetailerPolicy.isRetailerId(retailerId)) {
            Log.w(TAG, "\"" + said + "\" seen with no retailer in hand; ignored");
            return;
        }
        // A retailer the bot itself has just paid for is not "already ordered".
        if (ledger.statusOf(retailerId) == LedgerStatus.SUCCESS) return;

        // record() also clears this retailer's in-flight marker.
        if (!ledger.record(retailerId, LedgerStatus.ALREADY_ORDERED,
                "The app said: " + said)) {
            fail("Could not record that " + retailerId + " already has today's order");
            return;
        }
        Log.i(TAG, retailerId + " already ordered today (\"" + said + "\"); moving on");
        pendingRecovery = false;
        lastClickedRetailerId = null;
        sameRetailerClickCount = 0;
        resetCopyFlow();
        paymentActions = 0;
        pendingTicks = 0;
        unknownTicks = 0;
        detailsTicks = 0;
        historyTicks = 0;
        popupDismissTicks = 0;
        awaitingRetryAfterPopup = false;
        workflow.clearRetailer();

        handler.removeCallbacks(debouncedStep);
        returningToRetailerList = true;
        backSteps = 1;
        relaunches = 0;
        workflow.moveTo(BotState.RETURNING_TO_LIST,
                retailerId + " already has today's order; going to the next SMR");
        refreshPanel();
        performGlobalAction(GLOBAL_ACTION_BACK);
        lastActionAt = System.currentTimeMillis();
        next(ACTION_DELAY_MS);
    }

    // ------------------------------------------------ "Orders list is empty"

    /** How long the white order page has to stay white before it is taken as empty. */
    private static final long BLANK_ORDER_LIST_MS = 4000;
    private static final int MIN_BLANK_ORDER_LIST_TICKS = 3;
    private long blankOrderListSince;
    private int blankOrderListTicks;

    /**
     * Step 1c of {@link #inspect}: the retailer's order page says "Orders list is empty.
     * Retry?", or is the white page that message leaves behind.
     *
     * @return true when this screen was dealt with here (the caller must do nothing more)
     */
    private boolean leavesEmptyOrderList(String text) {
        // An order that may already be on its way is never abandoned from here; that case
        // has its own, stricter handler (step 5).
        if (ledger == null || pendingRecovery || ledger.hasPending()) return false;
        if (AppNotices.isEmptyOrderList(text)) {
            return onEmptyOrderList("Orders list is empty. Retry?", false);
        }
        if (!AppNotices.isBlankOrderList(text)) {
            blankOrderListSince = 0;
            blankOrderListTicks = 0;
            return false;
        }
        // White page, no message (it lasts about three seconds and may have been missed).
        // The page also looks like this for a moment while it opens, so it has to stay.
        long now = System.currentTimeMillis();
        if (blankOrderListSince <= 0) blankOrderListSince = now;
        blankOrderListTicks++;
        if (now - blankOrderListSince < BLANK_ORDER_LIST_MS
                || blankOrderListTicks < MIN_BLANK_ORDER_LIST_TICKS) {
            workflow.moveTo(workflow.state(), "The order list looks empty; checking once more");
            refreshPanel();
            next(FAST_DELAY_MS);
            return true;
        }
        return onEmptyOrderList("the order page stayed empty", true);
    }

    /**
     * The app has said "Orders list is empty. Retry?" for the retailer in hand: there is
     * no earlier order to copy. Nothing is waited for and YES (retry) is not pressed. The
     * retailer is put on record as skipped and the bot leaves at once: Back, as many times
     * as it takes to reach the customer list, Show Retailers again, then the next SMR.
     *
     * <p>Before this existed the white page matched none of the screens the bot knows, so
     * it waited on "a recognised Sudha screen" and then stopped the whole run.</p>
     *
     * @param blankPage true when the page was only seen empty, without the message
     * @return true when the bot is now on its way back to the list
     */
    private boolean onEmptyOrderList(String said, boolean blankPage) {
        if (!running || ledger == null) return false;
        // Already on the way out (for instance the message was seen twice).
        if (returningToRetailerList) return false;
        if (pendingRecovery || ledger.hasPending()) return false;
        String retailerId = workflow.retailerId();
        boolean known = RetailerPolicy.isRetailerId(retailerId);
        // The outcome of a retailer already on record is left as it is.
        if (known && !ledger.isProcessed(retailerId)) {
            String note = blankPage
                    ? "The order list stayed empty; nothing to copy"
                    : "The app said: " + said + " Nothing to copy";
            // record() also clears this retailer's in-flight marker.
            if (!ledger.record(retailerId, LedgerStatus.SKIPPED_NO_HISTORY, note)) {
                fail("Could not record the empty order list for " + retailerId);
                return true;
            }
            skipLog(retailerId, SkipLine.EMPTY_LIST, note);
        }
        Log.i(TAG, (known ? retailerId : "No retailer in hand") + ": order list is empty (" + said
                + "); going back to the list");
        blankOrderListSince = 0;
        blankOrderListTicks = 0;
        lastClickedRetailerId = null;
        sameRetailerClickCount = 0;
        resetCopyFlow();
        unknownTicks = 0;
        detailsTicks = 0;
        historyTicks = 0;
        popupDismissTicks = 0;
        awaitingRetryAfterPopup = false;
        workflow.clearRetailer();

        handler.removeCallbacks(debouncedStep);
        returningToRetailerList = true;
        backSteps = 1;
        relaunches = 0;
        workflow.moveTo(BotState.RETURNING_TO_LIST,
                (known ? retailerId + ": order list is empty" : "Order list is empty")
                        + "; going to the next SMR");
        refreshPanel();
        performGlobalAction(GLOBAL_ACTION_BACK);
        lastActionAt = System.currentTimeMillis();
        next(ACTION_DELAY_MS);
        return true;
    }

    @Override
    public void onInterrupt() {
        pause("Android interrupted the accessibility service");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        running = false;
        detachPanel();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            try {
                unregisterReceiver(controlReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
        if (alarmController != null) alarmController.stopAlarm();
        dayWatchEnd();
        detachPanel();
        super.onDestroy();
    }

    private void registerControlReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_START);
        filter.addAction(ACTION_PAUSE);
        filter.addAction(ACTION_RESET);
        filter.addAction(ACTION_SHOW_PANEL);
        filter.addAction(ACTION_CAPTURE);
        filter.addAction(ACTION_SHARE_CAPTURE);
        filter.addAction(ACTION_STOP_ALARM);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(controlReceiver, filter);
        }
        receiverRegistered = true;
    }

    /**
     * Restricts the service to the configured package and asks for the extras the
     * bot needs (view ids, unimportant views) without pulling key events.
     */
    private void applyServiceInfo() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        // The system's own package is listed as well: on some Android builds a toast is
        // reported under the package that draws it rather than the app that asked for it.
        // Everything except toasts is still ignored unless it comes from the Sudha app.
        info.packageNames = targetPackage.isEmpty() ? null
                : new String[]{targetPackage, "com.android.systemui", "android"};
        // Notification-state events are how toasts ("Today Order already created") arrive.
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_SCROLLED
                | AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 120;
        setServiceInfo(info);
    }

    private void reloadConfig() {
        if (prefs == null) prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        targetPackage = prefs.getString(MainActivity.KEY_TARGET_PACKAGE, "").trim();
        int configured = prefs.getInt(MainActivity.KEY_EXPECTED_COUNT,
                RetailerPolicy.EXPECTED_RETAILER_COUNT);
        expectedCount = configured < 1 || configured > 2000
                ? RetailerPolicy.EXPECTED_RETAILER_COUNT : configured;
        configuredCount = expectedCount;
    }

    // ---------------------------------------------------------------- control

    /** Validates preconditions and starts (or resumes) the loop. */
    private void startRun() {
        reloadConfig();
        applyServiceInfo();

        if (targetPackage.isEmpty()) {
            workflow.fail("Set the Sudha app package in SudhaAutoBot first");
            refreshPanel();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            workflow.fail("Allow \"display over other apps\" for Sutara");
            refreshPanel();
            return;
        }
        if (ledger == null) ledger = new DailyLedger(this);
        ensurePanel();

        // A new local day starts a new ledger file; reload so we notice midnight.
        if (!ledger.sessionDate().equals(LocalDate.now())) {
            ledger = new DailyLedger(this);
        }
        startPointAtStart();
        tempoAtStart();

        resetRunCounters();
        running = true;
        runStartedAt = System.currentTimeMillis();

        if (ledger.hasPending()) {
            pendingRecovery = true;
            String pendingId = ledger.pendingId();
            workflow.clearRetailer();
            if (RetailerPolicy.isRetailerId(pendingId)) workflow.beginRetailer(pendingId);
            workflow.moveTo(BotState.PAYMENT,
                    "Resuming in-flight order " + pendingId + " (no duplicate submission)");
        } else {
            if (alarmController != null) alarmController.stopAlarm();
            if (panel != null) panel.setAlarmActive(false);
            networkRetryCount = 0;
            pendingRecovery = false;
            workflow.clearRetailer();
            workflow.reset("Detecting current screen...");
            AccessibilityNodeInfo currentRoot = resolveRoot();
            String currentText = currentRoot == null ? "" : AccessibilityTree.allText(currentRoot).toLowerCase(Locale.ROOT);
            if (looksLikeRetailerListText(currentText)) {
                workflow.moveTo(BotState.RETAILER_LIST, "On Customers list; starting immediately");
            } else {
                workflow.moveTo(BotState.HOME, "Open Customers screen in Sudha Daily Orders");
            }
        }
        refreshPanel();
        scheduleStep(FAST_DELAY_MS);
    }

    private void pause(String reason) {
        running = false;
        handler.removeCallbacks(stepRunnable);
        handler.removeCallbacks(debouncedStep);
        workflow.pause(reason);
        refreshPanel();
        Log.i(TAG, "Paused: " + reason);
    }

    /** Safety stop: the run will not continue without a human looking at the phone. */
    private void fail(String reason) {
        running = false;
        handler.removeCallbacks(stepRunnable);
        handler.removeCallbacks(debouncedStep);
        workflow.fail(reason);
        refreshPanel();
        Log.w(TAG, "Stopped: " + reason);
        aakritiAfterStop(reason);
        if (!Aakriti.recovering()) {
            if (alarmController != null) alarmController.startHighVolumeAlarm();
            if (panel != null) panel.setAlarmActive(true);
        }
    }

    private void stopAlarm() {
        if (alarmController != null) alarmController.stopAlarm();
        if (panel != null) panel.setAlarmActive(false);
        networkRetryCount = 0;
        workflow.pause("Alarm silenced — review screen then tap START");
        refreshPanel();
    }

    /** Section 9: Retry network/unknown errors up to 5 times, then halt + high-volume alarm. */
    private void onNetworkOrUnknownRetry(String detail) {
        if (!running) return;
        networkRetryCount++;
        Log.w(TAG, "Retry " + networkRetryCount + "/5: " + detail);
        if (networkRetryCount >= 5) {
            fail("Failed after 5 retries: " + detail);
            return;
        }
        AccessibilityNodeInfo root = resolveRoot();
        if (root != null) tapDialogOk(root);
        workflow.moveTo(workflow.state(), "Network/server retry (" + networkRetryCount + "/5): " + detail);
        refreshPanel();
        next(1800L);
    }

    // ------------------------------------------------------------ start point
    // The day's history and the panel's "Last done ID" box (see RunHistory, StartPoint).
    // They only tell the ledger which retailer to carry on after; choosing and opening the
    // next retailer is still RetailerSequence's job. Each call is fenced: if one fails,
    // the run goes on exactly as it did before they were added.

    /** Follows the bot down the list to a start point; null when there is none. */
    private StartPoint.Seeker startSeeker;

    // The history belongs to one calendar date. Android says when the date changes
    // (midnight, or the clock or the time zone being set), and the history is wiped then
    // and there. An addition, fenced: without it the wipe still happens at the next look.
    private boolean dayWatching;
    private final BroadcastReceiver dayWatch = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            dayTurned();
        }
    };
    private final Runnable dayTick = this::dayTurned;

    private void dayWatchBegin() {
        try {
            RunHistory.expire(this);
            if (!dayWatching) {
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_DATE_CHANGED);
                filter.addAction(Intent.ACTION_TIME_CHANGED);
                filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(dayWatch, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(dayWatch, filter);
                }
                dayWatching = true;
            }
            dayTickBook();
        } catch (Throwable e) {
            Log.w(TAG, "Day watch not started", e);
        }
    }

    /** A second trigger for phones that are late with the broadcast: a timer set for just after midnight. */
    private void dayTickBook() {
        handler.removeCallbacks(dayTick);
        handler.postDelayed(dayTick, StartPoint.untilMidnight(System.currentTimeMillis(),
                java.util.TimeZone.getDefault()) + 1500L);
    }

    private void dayTurned() {
        try {
            if (RunHistory.expire(this)) {
                Log.i(TAG, "The date changed: the day's history is cleared");
                // A run that is going on keeps its place; only what was remembered for
                // the next START is gone.
                if (!running) startSeeker = null;
                if (panel != null) refreshPanel();
            }
            dayTickBook();
        } catch (Throwable e) {
            Log.w(TAG, "Day watch skipped", e);
        }
    }

    private void dayWatchEnd() {
        try {
            handler.removeCallbacks(dayTick);
            if (dayWatching) unregisterReceiver(dayWatch);
        } catch (Throwable ignored) {
        }
        dayWatching = false;
    }

    /** START: a number typed in the box (or today's remembered place) becomes "handled last". */
    private void startPointAtStart() {
        try {
            startSeeker = RunHistory.atStart(this, ledger, startSeeker);
        } catch (Throwable e) {
            Log.w(TAG, "Start point skipped", e);
        }
    }

    /** The retailer list is on screen: rows above the start point are noted, not opened. */
    private void startPointSees(List<String> idsOnScreen) {
        if (startSeeker == null) return;
        try {
            startSeeker = RunHistory.sees(this, ledger, startSeeker, idsOnScreen);
        } catch (Throwable e) {
            startSeeker = null;
            Log.w(TAG, "Start point skipped", e);
        }
    }

    /** Remembers, with the time, the retailer handled last. */
    private void startPointNote() {
        try {
            RunHistory.note(this, ledger);
        } catch (Throwable e) {
            Log.w(TAG, "History not saved", e);
        }
    }

    /** Reset clears the history as well as the ledger. */
    private void startPointForget() {
        try {
            startSeeker = null;
            RunHistory.forget(this);
        } catch (Throwable e) {
            Log.w(TAG, "History not cleared", e);
        }
    }

    // ----------------------------------------------------------- scroll memory
    // An addition (see ScrollMemory.java). Which retailer is opened is still decided by
    // RetailerSequence from the rows on screen; this only brings the list to the right
    // place faster. Fenced: when it has nothing to say, fails, or is refused, the list is
    // read exactly as before.

    private ScrollMemory.Seek scrollSeek;
    private int scrollSeekVisit = -1;
    /** The counted scrolls are being sent; nothing is read until they are done. */
    private boolean scrollBursting;
    private long scrollBurstDeadline;
    /** A search failed in this run: the memory is left alone until the next START. */
    private boolean scrollMemoryOff;
    /** Pause between two counted scrolls; 0 until read from the settings. */
    private long scrollGapMs;

    private long scrollGap() {
        if (scrollGapMs <= 0) {
            scrollGapMs = ScrollMemory.nextGap(getSharedPreferences(COPY_PREFS, MODE_PRIVATE)
                    .getLong(SCROLL_GAP, ScrollMemory.BLIND_GAP_MS), ScrollMemory.End.NOT_ENDED);
        }
        return scrollGapMs;
    }

    private void scrollMemoryReset() {
        scrollSeek = null;
        scrollSeekVisit = -1;
        scrollBursting = false;
        scrollMemoryOff = false;
        quietUntil = 0;
    }

    /** A retailer is about to be opened: remember how many scrolls down the list it is. */
    private void scrollMemoryNote(String retailerId) {
        try {
            if (scrollMemoryOff) return;
            RunHistory.noteScroll(this, retailerId, listScroller.replayableDepth());
        } catch (Throwable e) {
            Log.w(TAG, "Scroll count not saved", e);
        }
    }

    /**
     * The expanded retailer list is on screen. At the top of a freshly opened list, with a
     * count saved for the retailer handled last, the counted scrolls are sent without
     * reading the rows; then the rows are read, with a look around if the retailer is not
     * where the count said.
     *
     * @return true when this pass was used up here (the caller must do nothing more)
     */
    private boolean scrollMemorySteps(List<AccessibilityTree.RetailerRow> rows, List<String> ids) {
        try {
            if (scrollMemoryOff || ledger == null) return false;
            long now = System.currentTimeMillis();
            if (scrollBursting) {
                if (now < scrollBurstDeadline) {
                    // Still counting. Make sure a look is booked for when it should be over.
                    handler.removeCallbacks(stepRunnable);
                    handler.postDelayed(stepRunnable, scrollBurstDeadline - now + 50);
                    return true;
                }
                // The burst never reported back. Give the list to the ordinary scan.
                scrollBursting = false;
                if (scrollSeek != null) scrollSeek.refused();
            }
            int visit = listScroller.visit();
            if (scrollSeekVisit != visit) {
                scrollSeek = null;
                // Only from the top, and never while a typed start point is being followed
                // down: that one has to see every row on the way.
                if (startSeeker != null || listScroller.scrolledDown()) return false;
                String last = ledger.lastHandledId();
                if (last == null) return false;
                scrollSeekVisit = visit;
                if (ids.contains(last)) return false;
                int count = RunHistory.scrollCount(this, last);
                if (!ScrollMemory.usable(count)) return false;
                AccessibilityNodeInfo list = listScroller.jumpTarget(rows);
                if (list == null) return false;
                scrollSeek = new ScrollMemory.Seek(last, count);
                final ScrollMemory.Seek seek = scrollSeek;
                final int scrolls = seek.burst();
                scrollBursting = true;
                scrollBurstDeadline = now + scrolls * scrollGap() + 4000;
                quietUntil = scrollBurstDeadline;
                handler.removeCallbacks(stepRunnable);
                handler.removeCallbacks(debouncedStep);
                // A look is booked for the latest the counting can take, in case it is cut short.
                handler.postDelayed(stepRunnable, scrollBurstDeadline - now + 50);
                Log.i(TAG, "Scroll memory: " + scrolls + " scrolls down to " + last);
                workflow.moveTo(BotState.RETAILER_LIST, "Going straight down " + scrolls
                        + " scrolls to " + last + " (done last)");
                refreshPanel();
                scrollBurst(list, seek, scrolls);
                return true;
            }
            if (scrollSeek == null || scrollSeek.over()) return false;

            ScrollMemory.Move move = scrollSeek.next(ids, ledger::isProcessed, ListScroller.snapshot(rows));
            if (move == ScrollMemory.Move.WAIT) {
                quick(ScrollMemory.LOOK_GAP_MS);
                return true;
            }
            if (move == ScrollMemory.Move.FORWARD || move == ScrollMemory.Move.BACK) {
                boolean down = move == ScrollMemory.Move.FORWARD;
                if (listScroller.jump(listScroller.jumpTarget(rows), down)) {
                    lastActionAt = now;
                    workflow.moveTo(BotState.RETAILER_LIST, scrollSeek.id + " is not where the count said; looking "
                            + (down ? "below" : "above"));
                    refreshPanel();
                    quick(scrollSeek.blind() ? scrollGap() : ScrollMemory.LOOK_GAP_MS);
                    return true;
                }
                scrollSeek.refused();
            }
            ScrollMemory.End end = scrollSeek.end();
            Log.i(TAG, "Scroll memory for " + scrollSeek.id + ": " + end);
            long gap = ScrollMemory.nextGap(scrollGap(), end);
            if (gap != scrollGapMs) {
                // The counted scrolls fell short: they are sent further apart from now on.
                scrollGapMs = gap;
                getSharedPreferences(COPY_PREFS, MODE_PRIVATE).edit().putLong(SCROLL_GAP, gap).apply();
            }
            if (end == ScrollMemory.End.GAVE_UP || end == ScrollMemory.End.REFUSED) {
                // The count led nowhere. The ordinary scan takes over, and the memory is
                // not used again in this run.
                scrollMemoryOff = true;
                RunHistory.noteScroll(this, null, 0);
            }
            return false;
        } catch (Throwable e) {
            Log.w(TAG, "Scroll memory skipped", e);
            scrollBursting = false;
            scrollMemoryOff = true;
            return false;
        }
    }

    /** Sends the counted scrolls, one every {@link #scrollGap()} ms, reading nothing. */
    private void scrollBurst(final AccessibilityNodeInfo list, final ScrollMemory.Seek seek, final int left) {
        try {
            if (!running || seek != scrollSeek || !scrollBursting) return;
            if (left <= 0) {
                scrollBursting = false;
                quick(ScrollMemory.LOOK_GAP_MS);
                return;
            }
            if (!listScroller.jump(list, true)) {
                // The list would not take it (its end came sooner than the count said).
                // Forget the count, read the rows and carry on the ordinary way.
                scrollBursting = false;
                seek.refused();
                RunHistory.noteScroll(this, null, 0);
                quick(ScrollMemory.LOOK_GAP_MS);
                return;
            }
            lastActionAt = System.currentTimeMillis();
            handler.postDelayed(() -> scrollBurst(list, seek, left - 1), scrollGap());
        } catch (Throwable e) {
            Log.w(TAG, "Scroll memory stopped", e);
            scrollBursting = false;
            scrollMemoryOff = true;
            next(ACTION_DELAY_MS);
        }
    }

    // ------------------------------------------------------ skipped-retailer log
    // An addition: a text file the person can read later (see SkipLog.java). The bot never
    // reads it, and a failure to write it changes nothing.

    private void skipLog(String retailerId, String kind, String note) {
        try {
            SkipLog.add(this, retailerId, kind, note);
        } catch (Throwable e) {
            Log.w(TAG, "Skipped-retailer log not written", e);
        }
    }

    // ------------------------------------------------- the copied order, quickly
    // An addition (see CopyOutcome.java). The rule is unchanged - no error dialog means the
    // copy worked - but instead of always waiting about three seconds for a dialog that may
    // not come, the form is looked at every 150 ms, and the wait ends as soon as the form
    // has been rewritten. If it never is, the old wait applies in full.

    private static final String COPY_PREFS = "copy_outcome";
    private static final String COPY_DISTRUST_AT = "distrust_at";
    private static final String SCROLL_GAP = "scroll_gap_ms";
    private static final long COPY_DISTRUST_MS = 24L * 60 * 60 * 1000;

    /** Everything written on the order form just before Copy Order was pressed. */
    private String formBeforeCopy = "";
    private CopyOutcome.Watch copyWatch;

    private void copyOutcomeBefore(String formText) {
        formBeforeCopy = formText == null ? "" : formText;
        copyWatch = null;
    }

    /** The calendar's OK has just been pressed: a new wait begins. */
    private void copyOutcomeBegins() {
        copyWatch = null;
    }

    private boolean copyOutcomeTrusted() {
        long at = getSharedPreferences(COPY_PREFS, MODE_PRIVATE).getLong(COPY_DISTRUST_AT, 0);
        long age = System.currentTimeMillis() - at;
        return at <= 0 || age < 0 || age > COPY_DISTRUST_MS;
    }

    /**
     * No error dialog is on screen. Decides whether to keep waiting for one.
     *
     * @return true to wait (the next look is already booked); false to go on to PLACE ORDER
     */
    private boolean copyOutcomeWaits(AccessibilityNodeInfo root, String formText) {
        try {
            long now = System.currentTimeMillis();
            if (copyWatch == null) {
                // The same length as the wait this replaces (the look after OK, then three more).
                copyWatch = new CopyOutcome.Watch(copyOutcomeTrusted() ? formBeforeCopy : "",
                        (COPY_SETTLE_TICKS + 1) * ACTION_DELAY_MS);
            }
            boolean canPlace = hasPlaceOrder(root) && !CalendarDateSelector.isCalendar(root);
            CopyOutcome.Verdict verdict = copyWatch.look(formText, canPlace, now);
            if (verdict == CopyOutcome.Verdict.WAIT) {
                quick(CopyOutcome.POLL_MS);
                return true;
            }
            Log.i(TAG, "Copy result: " + verdict);
            return false;
        } catch (Throwable e) {
            Log.w(TAG, "Quick copy check skipped", e);
            return false;
        }
    }

    /**
     * The error dialog is on screen. If the form had been seen rewritten before it came,
     * the quick check misjudged this app, and it is not used again for a day.
     */
    private void copyOutcomeRefused() {
        try {
            if (copyWatch != null && copyWatch.sawChange()) {
                Log.w(TAG, "The form changed and the error dialog still came; quick copy check off for a day");
                getSharedPreferences(COPY_PREFS, MODE_PRIVATE).edit()
                        .putLong(COPY_DISTRUST_AT, System.currentTimeMillis()).apply();
            }
            copyWatch = null;
        } catch (Throwable e) {
            Log.w(TAG, "Quick copy check note skipped", e);
        }
    }

    // ---------------------------------------------------------------- Aakriti
    // The three places the bot talks to Aakriti (see Aakriti.java). She reads; she never
    // presses anything in the Sudha app. Each call is fenced: if she fails, nothing here
    // behaves differently from before she was added.

    private Aakriti aakriti() {
        return Aakriti.with(this);
    }

    /**
     * After a stop has fully happened, Aakriti studies the screen. If the stop was a label
     * the app has renamed and she worked out the new name, the run is started again by the
     * same code the START button runs - a moment later, and only if nobody has touched
     * anything in between. Otherwise she writes a report for a person.
     */
    private void aakritiAfterStop(String reason) {
        try {
            if (!aakriti().afterStop(this, resolveRoot(), reason)) return;
            handler.postDelayed(() -> {
                if (!running && workflow.state() == BotState.ERROR) startRun();
            }, 1500);
        } catch (Throwable e) {
            Log.w(TAG, "Aakriti could not look at the stop", e);
        }
    }

    /**
     * Aakriti reads the screen the bot is about to judge. Returns the text to judge: the
     * same string, unless a label she has learned a new name for had to be put back. Also
     * settles how many retailers to expect - the app's own "Total Customers" figure when it
     * prints one, otherwise the figure from the setup screen as before.
     */
    private String aakritiSees(String textLower) {
        try {
            String text = aakriti().see(textLower, workflow.state());
            expectedCount = aakriti().expectedCount(configuredCount);
            return text == null ? textLower : text;
        } catch (Throwable e) {
            Log.w(TAG, "Aakriti could not read the screen", e);
            expectedCount = configuredCount;
            return textLower;
        }
    }

    /** One retailer was given up; Aakriti looks at why so the next one may fare better. */
    private void aakritiAfterRetailerError(String reason) {
        try {
            aakriti().afterRetailerError(this, resolveRoot(), reason);
        } catch (Throwable e) {
            Log.w(TAG, "Aakriti could not look at the retailer error", e);
        }
    }

    /**
     * Gives up on the current retailer but keeps the batch running.
     *
     * <p>Used for problems that belong to one retailer — a control that will not open, a
     * screen that will not walk back. Stopping the whole run for those means one bad row
     * abandons the other 124, which is what happened on the first on-device run.</p>
     *
     * <p>Deliberately <b>not</b> used for anything that could double-order: if an order may
     * already be placed, the run must stop for a human to look at it.</p>
     */
    private void skipRetailer(String retailerId, String reason) {
        boolean recorded = RetailerPolicy.isRetailerId(retailerId)
                && ledger.record(retailerId, LedgerStatus.ERROR, reason);
        Log.w(TAG, "Skipping retailer " + retailerId + ": " + reason);

        // Forget the row so it is not tapped straight back into.
        lastClickedRetailerId = null;
        sameRetailerClickCount = 0;
        resetCopyFlow();
        detailsTicks = 0;
        historyTicks = 0;
        unknownTicks = 0;
        popupDismissTicks = 0;
        awaitingRetryAfterPopup = false;
        pendingRecovery = false;

        if (!recorded) {
            // Without a ledger entry the row stays eligible, so retrying would loop forever.
            fail("Could not record the skip for " + retailerId + "; stopping instead");
            return;
        }

        returningToRetailerList = true;
        backSteps = 0;
        workflow.moveTo(BotState.RETURNING_TO_LIST,
                "Skipped " + retailerId + ": " + reason + " — continuing with the next SMR");
        refreshPanel();
        next(ACTION_DELAY_MS);
    }

    private void resetLedger() {
        if (ledger == null) ledger = new DailyLedger(this);
        // Reset is a deliberate human action, so it clears an in-flight marker too.
        // Otherwise a marker left by an interrupted copy flow could never be removed and
        // every later START would wait forever for a payment screen that does not exist.
        if (ledger.forceReset()) {
            pendingRecovery = false;
            resetRunCounters();
            workflow.reset("Today's ledger was cleared");
            startPointForget();
            if (panel != null) panel.toastState("Ledger reset — all retailers eligible again");
        } else if (panel != null) {
            panel.toastState("Reset failed: the ledger file could not be written");
        }
        refreshPanel();
    }

    private void resetRunCounters() {
        homeTicks = 0;
        detailsTicks = 0;
        historyTicks = 0;
        historyAwaitingResult = false;
        copyFormTicks = 0;
        calendarWaitTicks = 0;
        datePickTicks = 0;
        resultTicks = 0;
        orderReadyTicks = 0;
        unknownTicks = 0;
        backSteps = 0;
        paymentActions = 0;
        showRetailersClicks = 0;
        listExpansionCooldownTicks = 0;
        scrollsWithoutProgress = 0;
        idleListPolls = 0;
        lastListFingerprint = "";
        listScroller.reset();
        scrollMemoryReset();
        listBusyUntil = 0;
        returningToRetailerList = false;
        pendingTicks = 0;
        lastClickedRetailerId = null;
        sameRetailerClickCount = 0;
        copyCycles = 0;
        strayCalendarTicks = 0;
        sequence.newVisit();
        sweptFromTop = false;
        busyTicks = 0;
        busyBackSent = false;
        placeTappedAt = 0;
        formAfterPlaceTicks = 0;
        relaunches = 0;
        resetCopyFlow();
    }

    private void resetCopyFlow() {
        copyPhase = CopyPhase.NONE;
        historyAwaitingResult = false;
        dateAttempt = 0;
        copyFormTicks = 0;
        calendarWaitTicks = 0;
        datePickTicks = 0;
        resultTicks = 0;
        orderReadyTicks = 0;
        calendarMisses = 0;
        calendarVerifyTicks = 0;
        calendarMonthSteps = 0;
        calendarScreenNote = "";
        candidates = new LocalDate[0];
        // T belongs to one retailer's visit to the calendar; the next retailer reads it anew.
        calendarAnchor = null;
        anchorTicks = 0;
    }

    // ------------------------------------------------------------------- panel

    private void ensurePanel() {
        if (panel != null) return;
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission missing; panel not shown");
            return;
        }
        FloatingPanelController created = new FloatingPanelController(this,
                new FloatingPanelController.Actions() {
                    @Override
                    public void onStart() {
                        startRun();
                    }

                    @Override
                    public void onPause() {
                        pause("Paused from the floating panel");
                    }

                    @Override
                    public void onReset() {
                        resetLedger();
                    }

                    @Override
                    public void onCapture() {
                        captureScreen(true);
                    }

                    @Override
                    public void onStopAlarm() {
                        stopAlarm();
                    }

                    @Override
                    public void onHide() {
                        pause("Panel hidden; run paused");
                        detachPanel();
                        workflow.moveTo(BotState.PAUSED, "Panel hidden — reopen SudhaAutoBot to show it again");
                    }
                });
        if (created.show()) panel = created;
    }

    /**
     * Writes the current screen to a text file so the user can verify the package name
     * and the exact labels the bot must match. Never taps anything.
     */
    private void captureScreen(boolean announce) {
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Exception e) {
            root = null;
        }
        java.io.File file = ScreenCapture.save(this, root);
        if (file == null) {
            if (announce && panel != null) panel.toastState("Capture failed: storage unavailable");
            return;
        }
        CharSequence packageName = root == null ? null : root.getPackageName();
        String captured = packageName == null ? "nothing" : packageName.toString();
        Log.i(TAG, "Screen captured to " + file.getName() + " (" + captured + ")");
        if (announce && panel != null) {
            panel.toastState("Captured " + captured + " → " + file.getName());
        }
    }

    /**
     * Opens the Android share sheet with the newest capture as plain text, so it can be
     * pasted straight into a chat. Sharing the text (rather than the file) needs no
     * FileProvider and no storage permission.
     */
    private void shareLatestCapture() {
        java.io.File[] files = ScreenCapture.list(this);
        if (files.length == 0) {
            captureScreen(false);
            files = ScreenCapture.list(this);
        }
        if (files.length == 0) {
            if (panel != null) panel.toastState("Nothing captured yet");
            return;
        }
        String content;
        try {
            byte[] bytes = new byte[(int) files[0].length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(files[0])) {
                int total = 0;
                while (total < bytes.length) {
                    int read = in.read(bytes, total, bytes.length - total);
                    if (read <= 0) break;
                    total += read;
                }
            }
            content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "Could not read capture", e);
            if (panel != null) panel.toastState("Could not read the capture file");
            return;
        }
        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_SUBJECT, "SudhaAutoBot screen capture");
            share.putExtra(Intent.EXTRA_TEXT, content);
            share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(share, "Share screen capture")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.w(TAG, "Share sheet unavailable", e);
            if (panel != null) panel.toastState("Capture saved: " + files[0].getName());
        }
    }

    private void detachPanel() {
        if (panel == null) return;
        panel.remove();
        panel = null;
    }

    private void refreshPanel() {
        if (panel == null || ledger == null) return;
        startPointNote();
        panel.updateFromLedger(workflow.state(), workflow.retailerId(), workflow.message(),
                running, ledger, expectedCount);
    }

    // -------------------------------------------------------------- scheduling

    private void scheduleStep(long delay) {
        handler.removeCallbacks(stepRunnable);
        handler.postDelayed(stepRunnable, Math.max(delay, MIN_ACTION_GAP_MS));
    }

    /** Debounced scheduling used after an action, so the UI has time to settle. */
    private void next(long delay) {
        scheduleStep(delay);
    }

    /** Until when screen changes are not allowed to re-book the next look. */
    private long quietUntil;

    /**
     * Books the next look sooner than {@link #next} allows. Only for the two places that
     * poll on purpose: the wait for the copied order, and the scroll memory.
     */
    private void quick(long delay) {
        long wait = Math.max(60, delay);
        handler.removeCallbacks(stepRunnable);
        handler.removeCallbacks(debouncedStep);
        quietUntil = System.currentTimeMillis() + wait + 40;
        handler.postDelayed(stepRunnable, wait);
    }

    // ------------------------------------------------------------- main tick

    private void step() {
        if (!running) return;
        try {
            AccessibilityNodeInfo root = resolveRoot();
            long actedAt = lastActionAt;
            // While the copy check or the scroll memory is polling, the screen is not read twice.
            String sawAtStart = System.currentTimeMillis() < quietUntil ? "" : tempoSignature(root);
            try {
                inspect(root);
            } finally {
                tempoStepped(sawAtStart, actedAt);
            }
        } catch (Exception e) {
            Log.e(TAG, "Tick failed", e);
            next(IDLE_DELAY_MS);
        }
    }

    // ---------------------------------------------------------------- fast mode
    // An addition (see Tempo.java). The bot's own look - inspect() - and its timers are as
    // they were, to the line; this only takes the look that was coming anyway some 420 to
    // 620 ms sooner, when a new page is seen to have come up and to be still. Not used while
    // an order is being placed and paid for. Fenced: switched off, or on any fault, the old
    // timing is all there is.

    private final Tempo tempo = new Tempo();
    private boolean fastMode;
    private float animationScale = 1f;
    private static final int TEMPO_MOST_NODES = 500;

    /** Read at START: is fast mode on, and how fast does this phone animate its windows? */
    private void tempoAtStart() {
        try {
            tempo.reset();
            handler.removeCallbacks(tempoPeek);
            fastMode = prefs == null || prefs.getBoolean(MainActivity.KEY_FAST_MODE, true);
            float transitions = Settings.Global.getFloat(getContentResolver(),
                    Settings.Global.TRANSITION_ANIMATION_SCALE, 1f);
            float windows = Settings.Global.getFloat(getContentResolver(),
                    Settings.Global.WINDOW_ANIMATION_SCALE, 1f);
            animationScale = Math.max(transitions, windows);
            Log.i(TAG, "Fast mode " + (fastMode ? "on" : "off") + "; animation scale " + animationScale);
        } catch (Throwable e) {
            Log.w(TAG, "Fast mode not set up", e);
            fastMode = false;
        }
    }

    /** A change on the Sudha app's screen: book a peek. */
    private void tempoChanged(AccessibilityEvent event) {
        if (!fastMode) return;
        try {
            long now = System.currentTimeMillis();
            boolean opened = event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            long wait = tempo.changed(now, opened);
            handler.removeCallbacks(tempoPeek);
            handler.postDelayed(tempoPeek, wait);
        } catch (Throwable e) {
            Log.w(TAG, "Fast mode switched off", e);
            fastMode = false;
        }
    }

    private final Runnable tempoPeek = new Runnable() {
        @Override
        public void run() {
            if (!running || !fastMode) return;
            try {
                long now = System.currentTimeMillis();
                // The copy check and the scroll memory keep their own time.
                if (now < quietUntil) return;
                // From just before PLACE ORDER until the order is on record - the confirmation,
                // the payment - the bot keeps the timing it always had. Money is not hurried.
                if (ledger == null || ledger.hasPending() || pendingRecovery) return;
                Tempo.Answer answer = tempo.peek(now, tempoSignature(resolveRoot()), animationScale, lastActionAt);
                if (answer.verdict == Tempo.Verdict.PEEK) {
                    handler.postDelayed(this, answer.delay);
                } else if (answer.verdict == Tempo.Verdict.STEP) {
                    // Tempo only says so while the bot's own pause after the last change on
                    // the screen is still running - the pause that would have booked a look
                    // 420 ms after it ended. That look is taken now instead: the pause and
                    // the look already booked are dropped, exactly as that look would have
                    // dropped them, and the look books its own next as every look does.
                    handler.removeCallbacks(debouncedStep);
                    handler.removeCallbacks(stepRunnable);
                    step();
                }
            } catch (Throwable e) {
                Log.w(TAG, "Fast mode switched off", e);
                fastMode = false;
            }
        }
    };

    private void tempoStepped(String sawAtStart, long actedAt) {
        if (!fastMode) return;
        try {
            tempo.stepped(System.currentTimeMillis(), sawAtStart, lastActionAt != actedAt, lastActionAt);
        } catch (Throwable e) {
            fastMode = false;
        }
    }

    /**
     * What the Sudha app's screen reads as, in the form Tempo compares: the window's number,
     * then every node's words, state and place, in order. Two screens with the same
     * signature look the same to the bot. Empty when the app is not on screen. Nothing is
     * kept or sent anywhere.
     */
    private String tempoSignature(AccessibilityNodeInfo root) {
        if (!fastMode || !isTargetRoot(root)) return "";
        try {
            StringBuilder out = new StringBuilder(2048);
            out.append(root.getWindowId());
            int[] left = {TEMPO_MOST_NODES};
            tempoWalk(root, out, left, new Rect(), 0);
            return out.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    private static void tempoWalk(AccessibilityNodeInfo node, StringBuilder out, int[] left,
                                  Rect box, int depth) {
        if (node == null || left[0]-- <= 0 || depth > 40) return;
        CharSequence text = node.getText();
        CharSequence said = node.getContentDescription();
        node.getBoundsInScreen(box);
        out.append(Tempo.NODE);
        tempoWords(out, text);
        out.append(' ');
        tempoWords(out, said);
        out.append(Tempo.WORDS_END)
                .append(node.isEnabled() ? 'e' : '-').append(node.isChecked() ? 'c' : '-')
                .append(node.isSelected() ? 's' : '-').append(node.isClickable() ? 'k' : '-')
                .append(box.left).append(',').append(box.top).append(',')
                .append(box.right).append(',').append(box.bottom);
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            tempoWalk(node.getChild(i), out, left, box, depth + 1);
        }
    }

    /** A node's words with the two marks Tempo splits on taken out. */
    private static void tempoWords(StringBuilder out, CharSequence words) {
        if (words == null) return;
        int length = Math.min(words.length(), 200);
        for (int i = 0; i < length; i++) {
            char c = words.charAt(i);
            out.append(c == Tempo.NODE || c == Tempo.WORDS_END ? ' ' : c);
        }
    }

    /**
     * Active window root belonging to the target package.
     *
     * <p>While a Copy Order date dialog is expected, a target-package window that looks
     * like a calendar is preferred: such dialogs are frequently separate windows and are
     * not always the "active" root.</p>
     */
    private AccessibilityNodeInfo resolveRoot() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
        } catch (Exception e) {
            Log.w(TAG, "getRootInActiveWindow failed", e);
        }
        boolean expectCalendar = copyPhase == CopyPhase.AWAIT_CALENDAR
                || copyPhase == CopyPhase.PICK_DATE
                || copyPhase == CopyPhase.CONFIRM_DATE;
        if (expectCalendar) {
            AccessibilityNodeInfo calendarRoot = findCalendarWindowRoot();
            if (calendarRoot != null) return calendarRoot;
        }
        if (isTargetRoot(root)) return root;
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) continue;
                    AccessibilityNodeInfo candidate = window.getRoot();
                    if (isTargetRoot(candidate)) return candidate;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Window enumeration failed", e);
        }
        return root;
    }

    /** Root of a target-package window that is showing a calendar, or null. */
    private AccessibilityNodeInfo findCalendarWindowRoot() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null) return null;
            for (AccessibilityWindowInfo window : windows) {
                if (window == null) continue;
                AccessibilityNodeInfo candidate = window.getRoot();
                if (!isTargetRoot(candidate)) continue;
                if (CalendarDateSelector.isCalendar(candidate)) return candidate;
            }
        } catch (Exception e) {
            Log.w(TAG, "Calendar window lookup failed", e);
        }
        return null;
    }

    private boolean isTargetRoot(AccessibilityNodeInfo root) {
        if (root == null) return false;
        CharSequence packageName = root.getPackageName();
        return packageName != null && targetPackage.contentEquals(packageName);
    }

    private void inspect(AccessibilityNodeInfo root) {
        if (!isTargetRoot(root)) {
            idle("Waiting for the Sudha app window");
            return;
        }

        // Aakriti reads the same text: she keeps count of the retailers the app lists and
        // puts a label she has learned a new name for back to the name looked for below.
        // With nothing learned the text comes back exactly as it went in.
        String text = aakritiSees(AccessibilityTree.allText(root).toLowerCase(Locale.ROOT));

        // ---- 1. Walking back to the list after a success or a skip. Checked first so a
        //         success screen that ignores "Done" cannot bounce between handlers.
        if (returningToRetailerList) {
            // ---- 1. A dialog eats Back. Never spend a Back press on one, or the walk back
            //         silently does nothing while the counter climbs (this is exactly how
            //         the run died with "Could not get back after 6 Back presses" while the
            //         error dialog was still on screen). Dismiss it first.
            if (popupBlocksCopy(root, text)) {
                popupDismissTicks++;
                if (popupDismissTicks > MAX_POPUP_DISMISS_TICKS) {
                    // Cannot dismiss it; carry on to Back anyway rather than deadlock.
                    Log.w(TAG, "Dialog would not close while returning to the list");
                } else {
                    workflow.moveTo(BotState.RETURNING_TO_LIST,
                            "Closing the dialog before returning to the list");
                    refreshPanel();
                    tapDialogOk(root);
                    next(ACTION_DELAY_MS);
                    return;
                }
            }
            popupDismissTicks = 0;

            // ---- 2. Arrived. Either screen that can lead onward counts: the customer list,
            //         or the Daily Orders home page, from which Customers is re-opened.
            boolean onCustomerList = looksLikeRetailerListText(text)
                    || (!looksLikeDetails(text) && !looksLikeHistory(text)
                        && !looksLikeOrderForm(text)
                        && AccessibilityTree.visibleRetailerCount(root) >= 1);
            if (onCustomerList || looksLikeHome(text)) {
                returningToRetailerList = false;
                backSteps = 0;
                relaunches = 0;
                busyTicks = 0;
                busyBackSent = false;
                if (onCustomerList) {
                    onRetailerList(root);
                } else {
                    onHome(root);
                }
                return;
            }

            // ---- 3. Still inside the app: keep walking back.
            if (!isTargetRoot(root)) {
                // Somewhere outside the Sudha app. Re-open it rather than pressing Back
                // blindly, which from a launcher view can go anywhere.
                workflow.moveTo(BotState.RETURNING_TO_LIST,
                        "Re-opening Sudha Daily Orders to continue with the next retailer");
                refreshPanel();
                launchTargetApp();
                next(ACTION_DELAY_MS);
                return;
            }
            // The app is showing its own "Loading" box (on the device the customer page
            // takes about fifteen seconds to load after leaving an order form). Back was
            // tried once; while the box stays up, wait for it rather than use up the
            // remaining Back presses on a screen that may not be listening.
            if (looksBusy(text)) {
                if (busyBackSent && busyTicks++ < MAX_BUSY_TICKS) {
                    workflow.moveTo(BotState.RETURNING_TO_LIST,
                            "Waiting for the app to finish loading, then going back to the list");
                    refreshPanel();
                    next(IDLE_DELAY_MS);
                    return;
                }
                busyBackSent = true;
            } else {
                busyTicks = 0;
                busyBackSent = false;
            }
            if (backSteps++ < MAX_BACK_STEPS) {
                workflow.moveTo(BotState.RETURNING_TO_LIST,
                        "Returning to the retailer list (" + backSteps + "/" + MAX_BACK_STEPS + ")");
                refreshPanel();
                performGlobalAction(GLOBAL_ACTION_BACK);
                next(ACTION_DELAY_MS);
                return;
            }
            // Still stuck after bounded Backs: dig out instead of killing the whole run,
            // otherwise one stubborn screen abandons the remaining retailers.
            String stuckOn = workflow.retailerId();
            if (RetailerPolicy.isRetailerId(stuckOn) && !ledger.isProcessed(stuckOn)) {
                skipRetailer(stuckOn, "Could not walk back to the retailer list");
                launchTargetApp();
                backSteps = 0;
                return;
            }
            // The retailer's outcome is already on record (ordered, already ordered,
            // skipped), so there is nothing to mark: just get back to the app's first page.
            if (++relaunches > MAX_RELAUNCHES) {
                fail("Could not get back to the customer list after re-opening the app "
                        + MAX_RELAUNCHES + " times");
                return;
            }
            workflow.moveTo(BotState.RETURNING_TO_LIST,
                    "Back did not reach the list; re-opening Sudha Daily Orders ("
                            + relaunches + "/" + MAX_RELAUNCHES + ")");
            refreshPanel();
            launchTargetApp();
            backSteps = 0;
            next(ACTION_DELAY_MS);
            return;
        }

        // ---- 1b. "Today Order already created", when the app shows it inside a window
        //          (a dialog or a bar) instead of as a toast. Same meaning, same answer.
        if (text.contains("order already created")
                && (RetailerPolicy.isRetailerId(workflow.retailerId()) || ledger.hasPending())) {
            onAlreadyOrdered("Today Order already created");
            if (returningToRetailerList) return;
        }

        // ---- 1c. "Orders list is empty. Retry?" on the retailer's order page, or the white
        //          page that message leaves behind. Nothing to copy: do not wait, do not
        //          press YES; go back to the list and on to the next SMR.
        if (leavesEmptyOrderList(text)) return;

        // ---- 2. Success screen wins over everything else.
        if (looksLikeSuccess(text)) {
            onSuccess(root);
            return;
        }

        // ---- 3. Date dialog (only meaningful during a copy flow).
        if (copyPhase != CopyPhase.NONE && CalendarDateSelector.isCalendar(root)) {
            onCalendar(root);
            return;
        }

        // ---- 3b. A date dialog that is open although no copy is in progress: the run was
        //          paused (or restarted) with the calendar up. The bot does not know which
        //          retailer or which date this dialog belongs to, so it may not choose
        //          anything in it. Close it with CANCEL - never OK, which would commit the
        //          pre-selected present day - and let the normal flow reopen it.
        //          Before this branch existed the bot sat on "Waiting for a recognised
        //          Sudha screen" until it paused itself.
        if (copyPhase == CopyPhase.NONE && strayCalendarTicks < MAX_STRAY_CALENDAR_TICKS) {
            AccessibilityNodeInfo stray = CalendarDateSelector.isCalendar(root)
                    ? root : findCalendarWindowRoot();
            if (stray != null && CalendarDateSelector.hasConfirmButton(stray)) {
                strayCalendarTicks++;
                if (CalendarDateSelector.cancel(stray)) {
                    workflow.moveTo(workflow.state(),
                            "Closing a date dialog left open from before (CANCEL, no date chosen)");
                    refreshPanel();
                    next(ACTION_DELAY_MS);
                    return;
                }
            }
        } else if (copyPhase != CopyPhase.NONE) {
            strayCalendarTicks = 0;
        }

        // ---- 4. "No data" style popups must be handled before anything is tapped.
        if (copyPhase != CopyPhase.NONE && looksLikeCopyFailure(text)) {
            onCopyFailure(root);
            return;
        }

        // ---- 5. In-flight order: only safe navigations are allowed.
        if (pendingRecovery || ledger.hasPending()) {
            onPendingScreen(root, text);
            return;
        }

        // ---- 6. Home screen.
        if (looksLikeHome(text) && !text.contains("show retailers")) {
            onHome(root);
            return;
        }

        // ---- 7. Retailer list.
        //         Once the bot has scrolled the list, the header may be off screen and the
        //         last screenful may hold fewer than three retailers; it is still the list.
        int retailersOnScreen = AccessibilityTree.visibleRetailerCount(root);
        if (looksLikeRetailerListText(text) || retailersOnScreen >= 3
                || (listScroller.scrolledDown() && retailersOnScreen >= 1)) {
            if (!looksLikeDetails(text) && !looksLikeOrderForm(text) && !looksLikeHistory(text)) {
                onRetailerList(root);
                return;
            }
        }

        // ---- 8. Customer details.
        if (looksLikeDetails(text)) {
            onDetails(root);
            return;
        }

        // ---- 9. Order history BEFORE the order form.
        //         Both screens are titled "New Order Milk" in this app, so history must
        //         be recognised first. Getting this order wrong meant the bot never ran
        //         the "+" ladder at all and treated the history screen as the form.
        if (looksLikeHistory(text)) {
            onHistory(root, text);
            return;
        }

        // ---- 10. Order form ("New Order Milk" entry screen).
        if (looksLikeOrderForm(text)) {
            onOrderForm(root, text);
            return;
        }

        // ---- 11. Nothing recognised.
        if (++unknownTicks > MAX_UNKNOWN_TICKS) {
            fail("Unrecognised screen in " + targetPackage + " — paused instead of guessing");
            return;
        }
        idle("Waiting for a recognised Sudha screen");
    }

    private void idle(String message) {
        if (!running) return;
        workflow.moveTo(workflow.state(), message);
        next(IDLE_DELAY_MS);
    }

    // ------------------------------------------------------------ screen: home

    private void onHome(AccessibilityNodeInfo root) {
        boolean manualHome = prefs == null || prefs.getBoolean(MainActivity.KEY_MANUAL_HOME_ENTRY, true);
        if (manualHome) {
            workflow.moveTo(BotState.HOME, "Manual Home Entry ON — tap Customers manually to begin");
            refreshPanel();
            next(IDLE_DELAY_MS);
            return;
        }
        workflow.moveTo(BotState.HOME, "Home screen: opening Customers");
        // Some builds label the tile "Daily Orders" first; tap it if Customers is hidden.
        if (tapLabel(root, "Customers", "Customer", "Retailers", "Customer List")) {
            homeTicks = 0;
            workflow.moveTo(BotState.RETAILER_LIST, "Opened the customer list");
            refreshPanel();
            next(ACTION_DELAY_MS);
            return;
        }
        if (homeTicks < 4 && tapLabel(root, "Daily Orders", "Daily Order")) {
            homeTicks++;
            next(ACTION_DELAY_MS);
            return;
        }
        if (++homeTicks > MAX_HOME_TICKS) {
            fail("Could not find \"Customers\" on the Sudha home screen");
            return;
        }
        workflow.moveTo(BotState.HOME, "Looking for the Customers tile");
        next(IDLE_DELAY_MS);
    }

    // --------------------------------------------------- screen: retailer list

    private void onRetailerList(AccessibilityNodeInfo root) {
        // A swipe on the list is still under way: the finger is down. Pressing a row now
        // would land in the middle of the drag, so wait for it to finish.
        long swipeLeft = listBusyUntil - System.currentTimeMillis();
        if (swipeLeft > 0) {
            next(swipeLeft + 80);
            return;
        }
        if (listExpansionCooldownTicks > 0) {
            listExpansionCooldownTicks--;
            next(ACTION_DELAY_MS);
            return;
        }

        List<AccessibilityTree.RetailerRow> rows = AccessibilityTree.retailerRows(root);
        int visibleRetailers = rows.size();

        // Leaving the details/history flow: the run is back on the list.
        if (copyPhase != CopyPhase.NONE) resetCopyFlow();
        detailsTicks = 0;
        historyTicks = 0;
        backSteps = 0;

        // Section 5 Rule 2: Verify filter dropdown is "All Distributors" before touching Show Retailers.
        if (!listScroller.scrolledDown() && filterCheckDoneVisit != listScroller.visit()) {
            filterCheckDoneVisit = listScroller.visit();
            Object[] dd = DistributorBlueprint.findFilterDropdown(root);
            if (dd != null) {
                String ddLabel = (String) dd[1];
                if (!ddLabel.isEmpty() && !"All Distributors".equalsIgnoreCase(ddLabel)) {
                    String warn = "Filter dropdown was '" + ddLabel + "' instead of 'All Distributors' — possible app update or filter change";
                    Log.w(TAG, warn);
                    skipLog("FILTER", "WARN", warn);
                    AccessibilityTree.click((AccessibilityNodeInfo) dd[0], gestures);
                    workflow.moveTo(BotState.RETAILER_LIST, "Resetting filter to All Distributors...");
                    refreshPanel();
                    next(ACTION_DELAY_MS);
                    return;
                }
            }
        }

        // Bug 1 Fix: Treat list as EXPANDED immediately if Total Customers jumped > preShowRetailersTotal,
        // OR Show Retailers checkbox isChecked(), OR any Delivery Point (Retailer) card is visible.
        int currentTotalCustomers = AccessibilityTree.customerTotalFromScreen(root);
        boolean totalJumped = preShowRetailersTotal > 0 && currentTotalCustomers > preShowRetailersTotal;
        List<DistributorBlueprint.CardInfo> structuralCards = DistributorBlueprint.parseVisibleCards(root);
        boolean anyRetailerCard = false;
        for (DistributorBlueprint.CardInfo ci : structuralCards) {
            if (ci.isRetailer) { anyRetailerCard = true; break; }
        }
        AccessibilityNodeInfo srNode = AccessibilityTree.findContaining(root, "show retailers");
        boolean srChecked = srNode != null && (srNode.isChecked()
                || (srNode.getParent() != null && srNode.getParent().isChecked()));
        boolean alreadyExpanded = totalJumped || srChecked || anyRetailerCard || visibleRetailers >= 3;

        // Tier 1 Permanent Distributor Blueprint learning & boycott
        if (alreadyExpanded && !structuralCards.isEmpty()) {
            DistributorBlueprint.Classification cls = DistributorBlueprint.classifyOrderedCards(structuralCards);
            if (!DistributorBlueprint.isLearned(this) && !listScroller.scrolledDown()) {
                DistributorBlueprint.saveLearnedBlueprint(this, cls, 0);
                Log.i(TAG, "Tier 1 Blueprint learned: " + DistributorBlueprint.statusSummary(this));
            }
            // Filter out any permanently blacklisted ID (including Distributor-Owned Retailer IDs)
            java.util.Set<String> blacklist = DistributorBlueprint.permanentBlacklist(this);
            if (!blacklist.isEmpty() || !cls.allBlacklistedIds.isEmpty()) {
                java.util.List<AccessibilityTree.RetailerRow> filtered = new java.util.ArrayList<>(rows.size());
                for (AccessibilityTree.RetailerRow r : rows) {
                    String up = r.id.trim().toUpperCase(Locale.ROOT);
                    if (!blacklist.contains(up) && !cls.allBlacklistedIds.contains(up)) {
                        filtered.add(r);
                    }
                }
                rows = filtered;
                visibleRetailers = rows.size();
            }
        }

        boolean collapsed = !alreadyExpanded && !listScroller.scrolledDown();
        if (collapsed) {
            if (preShowRetailersTotal <= 0 && currentTotalCustomers > 0) {
                preShowRetailersTotal = currentTotalCustomers;
            }
            // ---- Step 2: the checkbox never exposes isChecked(), so infer it.
            if (showRetailersClicks >= MAX_SHOW_RETAILERS_CLICKS) {
                // The list itself will not expand: nothing can be processed, so this is a
                // genuine stop rather than a per-retailer skip.
                fail("Show Retailers was tapped " + showRetailersClicks
                        + " times but the list is still collapsed");
                return;
            }
            if (toggleShowRetailers(root)) {
                showRetailersClicks++;
                listExpansionCooldownTicks = 3;
                listScroller.leftList();
                sequence.newVisit();
                workflow.moveTo(BotState.RETAILER_LIST,
                        "Tapped Show Retailers (" + showRetailersClicks + "/"
                                + MAX_SHOW_RETAILERS_CLICKS + "); waiting for ~"
                                + expectedCount + " rows");
                refreshPanel();
                next(DEBOUNCE_MS + 400);
                return;
            }
            if (++unknownTicks > MAX_UNKNOWN_TICKS) {
                fail("Could not find the \"Show Retailers\" control on the list");
                return;
            }
            workflow.moveTo(BotState.RETAILER_LIST, "Looking for the Show Retailers control");
            refreshPanel();
            next(IDLE_DELAY_MS);
            return;
        }

        // List is expanded: the toggle is on, so never tap it again until it collapses.
        showRetailersClicks = 0;

        // Let the scroller see the rows first, so a scroll that reveals a new retailer is
        // recorded as having worked even though the bot now leaves the list.
        listScroller.observe(rows);

        // A new set of ids on screen means the scroll actually moved the list.
        String fingerprint = fingerprint(rows);
        if (!fingerprint.equals(lastListFingerprint)) {
            lastListFingerprint = fingerprint;
            scrollsWithoutProgress = 0;
            idleListPolls = 0;
            unknownTicks = 0;
        }

        // ---- Which retailer is next. Always the one AFTER the retailer handled last, in
        //      the order the list shows them; never an earlier row. The app reopens this
        //      list at the top after every retailer, so "first unhandled row on screen"
        //      meant going back to the top (SMR2255 after SMR2250 instead of SMR2249).
        List<String> idsOnScreen = new java.util.ArrayList<>(rows.size());
        for (AccessibilityTree.RetailerRow row : rows) idsOnScreen.add(row.id);
        // At the top of a freshly opened list nothing can have been passed yet.
        if (!listScroller.scrolledDown()) sequence.newVisit();
        startPointSees(idsOnScreen);
        // Scroll memory: straight down to where the retailer handled last was, then look.
        if (scrollMemorySteps(rows, idsOnScreen)) return;
        RetailerSequence.Decision decision = null;
        String last = null;
        for (int guard = 0; guard <= rows.size(); guard++) {
            last = ledger.lastHandledId();
            decision = sequence.decide(idsOnScreen, last, ledger::isProcessed);
            if (decision.action != RetailerSequence.Action.OPEN) break;
            AccessibilityTree.RetailerRow row = rows.get(decision.index);

            // Guard against a row that accepts the tap but never navigates.
            if (row.id.equals(lastClickedRetailerId)) {
                if (++sameRetailerClickCount > MAX_SAME_ROW_CLICKS) {
                    ledger.record(row.id, LedgerStatus.ERROR,
                            "Row did not open after " + MAX_SAME_ROW_CLICKS + " taps");
                    lastClickedRetailerId = null;
                    sameRetailerClickCount = 0;
                    continue;
                }
            } else {
                lastClickedRetailerId = row.id;
                sameRetailerClickCount = 1;
            }

            copyCycles = 0;
            workflow.beginRetailer(row.id);
            if (!AccessibilityTree.click(row.node, gestures)) {
                workflow.moveTo(BotState.RETAILER_LIST, "Row " + row.id + " did not respond; retrying");
                refreshPanel();
                next(ACTION_DELAY_MS);
                return;
            }
            lastActionAt = System.currentTimeMillis();
            resetCopyFlow();
            detailsTicks = 0;
            scrollsWithoutProgress = 0;
            idleListPolls = 0;
            scrollMemoryNote(row.id);
            listScroller.leftList();
            sequence.newVisit();
            workflow.moveTo(BotState.DETAILS, last == null
                    ? "Opened " + row.id + "; verifying details header"
                    : "Opened " + row.id + " (next after " + last + "); verifying details header");
            refreshPanel();
            next(ACTION_DELAY_MS);
            return;
        }
        boolean seekingLast = decision != null
                && decision.action == RetailerSequence.Action.SCROLL_TO_LAST;

        // Every visible retailer is handled. Only finish when the caller's own expectation
        // is met; otherwise the list simply cannot scroll yet (it may still be rendering)
        // so keep polling instead of declaring the batch complete.
        boolean expectationMet = ledger.processedCount() >= expectedCount && visibleRetailers <= 3;
        if (expectationMet) {
            ledger.clearLastHandled();
            complete("All " + ledger.processedCount() + " retailers handled");
            return;
        }
        // Nothing left to do on this screenful: bring up the next one. The scroller checks
        // on the following pass that the rows really moved, changes method when they did
        // not, and reports the end of the list when swipes no longer move it.
        int screenWidth = screenWidthPx();
        int screenHeight = screenHeightPx();
        if (screenWidth <= 0 || screenHeight <= 0) {
            Rect window = new Rect();
            root.getBoundsInScreen(window);
            screenWidth = window.right;
            screenHeight = window.bottom;
        }
        Rect panelAt = panel == null ? null : panel.boundsOnScreen();
        String progress = ledger.processedCount() + "/" + expectedCount;
        ListScroller.Step scroll =
                listScroller.advance(rows, screenWidth, screenHeight, panelAt, gestures);
        Log.i(TAG, "List scroll: " + scroll + " (" + listScroller.note() + "), rows "
                + fingerprint);
        switch (scroll) {
            case END_OF_LIST:
                if (seekingLast) {
                    // The bottom of the list was reached and the retailer handled last
                    // never came into view (it is no longer listed, or a swipe jumped over
                    // it). The place in the list is lost, so forget it and come down the
                    // list once more from the top. Retailers already handled are skipped
                    // by the ledger, so nothing is ordered twice.
                    Log.w(TAG, "Last handled retailer " + last + " not found; restarting the pass");
                    restartFromTop(last
                            + " (handled last) was not found in the list; starting again from the top");
                    return;
                }
                // The bottom of the list. If fewer retailers were handled than the list is
                // expected to hold, some were passed over - the run was started part-way
                // down, or a retailer was opened by hand in the middle. Go through the
                // list ONCE more from the top for those; only then is the run finished.
                if (!sweptFromTop && ledger.processedCount() < expectedCount) {
                    sweptFromTop = true;
                    restartFromTop("Reached the end of the list; going through it once more from "
                            + "the top for any retailer passed over (" + progress + ")");
                    return;
                }
                // The pass is over. Forget the place in the list so that the next START
                // begins at the top.
                ledger.clearLastHandled();
                if (listScroller.everMoved()) {
                    complete("Reached the end of the retailer list (" + ledger.processedCount()
                            + " of " + expectedCount + " handled)");
                } else {
                    // Never once seen to move. Either every retailer fits on one screen, or
                    // the list cannot be scrolled by the bot; say which facts are known.
                    complete("The list did not move when swiped (" + listScroller.note()
                            + "); no retailers beyond this screen could be reached ("
                            + ledger.processedCount() + " of " + expectedCount + " handled)");
                }
                return;
            case SCROLLED_BY_ACTION:
                scrollsWithoutProgress = 0;
                idleListPolls = 0;
                lastActionAt = System.currentTimeMillis();
                workflow.moveTo(BotState.RETAILER_LIST, seekingLast
                        ? "Scrolling down to " + last + " (done last); the next SMR is after it ("
                                + progress + ")"
                        : "All visible retailers handled; scrolling down (" + progress + ")");
                refreshPanel();
                next(FAST_DELAY_MS);
                return;
            case SCROLLED_BY_DRAG:
                scrollsWithoutProgress = 0;
                idleListPolls = 0;
                lastActionAt = System.currentTimeMillis();
                workflow.moveTo(BotState.RETAILER_LIST, seekingLast
                        ? "Swiping up to reach " + last + " (done last); the next SMR is after it ("
                                + progress + ")"
                        : "All visible retailers handled; swiping up for more (" + progress + ")");
                refreshPanel();
                next(lastDragMs + 300);
                return;
            case SETTLING:
                workflow.moveTo(BotState.RETAILER_LIST,
                        "Checking that the list moved (" + progress + ")");
                refreshPanel();
                next(FAST_DELAY_MS);
                return;
            case BLOCKED:
            default:
                // Nothing was sent. This is not the end of the list, so do not finish:
                // say what is in the way and keep trying.
                idleListPolls++;
                workflow.moveTo(BotState.RETAILER_LIST,
                        "Cannot scroll the list: " + listScroller.note()
                                + " - drag the bot panel off the list (" + progress + ")");
                refreshPanel();
                next(IDLE_DELAY_MS);
        }
    }

    /**
     * Forgets the place in the list and brings the list back to its start: Back leads from
     * the customer list to the first page, and the ordinary flow then opens Customers and
     * ticks Show Retailers again, which always shows the list from the top.
     */
    private void restartFromTop(String message) {
        ledger.clearLastHandled();
        sequence.newVisit();
        listScroller.leftList();
        showRetailersClicks = 0;
        workflow.clearRetailer();
        workflow.moveTo(BotState.RETAILER_LIST, message);
        refreshPanel();
        performGlobalAction(GLOBAL_ACTION_BACK);
        lastActionAt = System.currentTimeMillis();
        next(ACTION_DELAY_MS);
    }

    private String fingerprint(List<AccessibilityTree.RetailerRow> rows) {
        StringBuilder builder = new StringBuilder();
        for (AccessibilityTree.RetailerRow row : rows) {
            builder.append(row.id).append(',');
        }
        return builder.toString();
    }

    /**
     * Step 2 implementation. The checkbox's {@code isChecked()} is not exposed, so the
     * text node is used as an anchor: try its own click action, then a checkable
     * ancestor/sibling, then a raw tap on the label bounds.
     */
    private boolean toggleShowRetailers(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo label = AccessibilityTree.findContaining(root, "show retailers");
        if (label == null) label = AccessibilityTree.findContaining(root, "show retailer");
        if (label == null) return false;

        // 1. Direct click on the label node.
        if (label.isClickable() && label.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            lastActionAt = System.currentTimeMillis();
            return true;
        }

        // 2. Climb to a clickable or checkable row container.
        AccessibilityNodeInfo cursor = label;
        for (int hop = 0; cursor != null && hop <= 5; hop++) {
            if (cursor.isCheckable()) {
                if (cursor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    lastActionAt = System.currentTimeMillis();
                    return true;
                }
            }
            if (cursor.isClickable() && cursor != label
                    && cursor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                lastActionAt = System.currentTimeMillis();
                return true;
            }
            // Look for a checkbox sibling inside the same row.
            AccessibilityNodeInfo checkbox = findCheckable(cursor, 0);
            if (checkbox != null && checkbox.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                lastActionAt = System.currentTimeMillis();
                return true;
            }
            cursor = cursor.getParent();
        }

        // 3. Last resort: tap the label's bounds with a gesture.
        lastActionAt = System.currentTimeMillis();
        return AccessibilityTree.tapBounds(label, gestures);
    }

    private AccessibilityNodeInfo findCheckable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 6) return null;
        if (node.isCheckable() && node.isVisibleToUser()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findCheckable(node.getChild(i), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    // --------------------------------------------------------- screen: details

    private void onDetails(AccessibilityNodeInfo root) {
        String expected = workflow.retailerId();
        if (!RetailerPolicy.isRetailerId(expected)) {
            // The list handler normally sets this; recover defensively.
            List<String> visible = AccessibilityTree.visibleRetailerIds(root);
            for (String id : visible) {
                if (!ledger.isProcessed(id)) {
                    expected = id;
                    workflow.beginRetailer(id);
                    break;
                }
            }
            if (!RetailerPolicy.isRetailerId(expected)) {
                fail("Details screen is open but no retailer id is attached");
                return;
            }
        }

        lastClickedRetailerId = null;
        sameRetailerClickCount = 0;

        List<String> idsOnScreen = AccessibilityTree.visibleRetailerIds(root);
        if (idsOnScreen.isEmpty() && detailsTicks < 4) {
            // Header may still be loading.
            detailsTicks++;
            workflow.moveTo(BotState.DETAILS, "Waiting for " + expected + " in the details header");
            refreshPanel();
            next(ACTION_DELAY_MS);
            return;
        }
        if (!idsOnScreen.isEmpty() && !idsOnScreen.contains(expected)) {
            // A different retailer is on screen: never operate on the wrong customer.
            if (ledger.record(expected, LedgerStatus.SKIPPED_ID_MISMATCH,
                    "Details header showed " + idsOnScreen + " instead of " + expected)) {
                workflow.moveTo(BotState.RETURNING_TO_LIST,
                        "Header mismatch for " + expected + "; backing out");
            } else {
                fail("Could not record the header mismatch");
                return;
            }
            refreshPanel();
            goBackTo(1);
            return;
        }

        if (detailsTicks++ > MAX_DETAILS_TICKS) {
            fail("Could not open the Milk module for " + expected);
            return;
        }

        // The tabs are labelled simply "Milk" and "PROD" under "View Orders".
        if (tapLabel(root, "Milk", "Milk Order", "Milk Orders")) {
            historyTicks = 0;
            workflow.moveTo(BotState.HISTORY, "Opened Milk history for " + expected);
            refreshPanel();
            next(ACTION_DELAY_MS);
            return;
        }
        workflow.moveTo(BotState.DETAILS, "Verifying " + expected + "; looking for Milk");
        refreshPanel();
        next(IDLE_DELAY_MS);
    }

    // --------------------------------------------------------- screen: history

    /**
     * Order-history screen: get into "New Order Milk".
     *
     * <p>The floating "+" button in this app is a custom view whose click handler does not
     * run for {@code performAction(ACTION_CLICK)} and which may not report itself as
     * clickable at all. So the tap escalates over successive ticks instead of retrying the
     * same call:</p>
     *
     * <ol start="0">
     *   <li>tick 0-1: the best node from {@link AccessibilityTree#findAddButton}, tapped
     *       physically at its bounds centre with a gesture;</li>
     *   <li>tick 2-3: any other FAB-shaped candidate, clicked natively as a second opinion;</li>
     *   <li>tick 4+: geometry — a FAB-sized square at 88% / 88% of the screen;</li>
     *   <li>tick 8+: a small ladder of offsets around that corner.</li>
     * </ol>
     *
     * <p>If the form still never appears the retailer is recorded as {@code ERROR} and the
     * run moves on to a different SMR id. It never walks back to "Milk" for the same
     * retailer, which is what caused the earlier 4-retailer loop.</p>
     */
    private void onHistory(AccessibilityNodeInfo root, String text) {
        String expected = workflow.retailerId();
        if (historyTicks == 0) fabViaScreenshot = false;
        if (!RetailerPolicy.isRetailerId(expected)) {
            fail("Order history is open without a verified retailer");
            return;
        }

        // The form may already be open (a previous tap landed late, or the screen was
        // restored). Hand off instead of tapping + again.
        if (looksLikeOrderForm(text)) {
            historyTicks = 0;
            historyAwaitingResult = false;
            onOrderForm(root, text);
            return;
        }

        if (isEmptyHistory(text)) {
            if (ledger.record(expected, LedgerStatus.SKIPPED_NO_HISTORY,
                    "Order history was empty; nothing to copy")) {
                workflow.moveTo(BotState.RETURNING_TO_LIST,
                        expected + " has no history; next retailer");
                refreshPanel();
            } else {
                fail("Could not record the empty history for " + expected);
                return;
            }
            goBackTo(1);
            return;
        }

        // Just tapped: give the screen a full beat to react before deciding anything.
        if (historyTicks > 0 && !historyAwaitingResult) {
            historyAwaitingResult = true;
            workflow.moveTo(BotState.HISTORY, "Tapped +; waiting for New Order Milk");
            refreshPanel();
            next(ACTION_DELAY_MS);
            return;
        }
        historyAwaitingResult = false;

        int tickBudget = MAX_HISTORY_TICKS + (fabViaScreenshot ? FAB_SCREENSHOT_GRACE : 0);
        if (historyTicks >= tickBudget) {
            abandonRetailer(expected, "The + button never opened New Order Milk ("
                    + tickBudget + " tap attempts, including a screenshot-located aim)");
            return;
        }

        historyTicks++;
        workflow.moveTo(BotState.HISTORY, "Tapping + (attempt " + historyTicks + "/"
                + tickBudget + ") for " + expected);
        refreshPanel();

        if (tapAddButton(root)) {
            copyFormTicks = 0;
            returningToRetailerList = false;
            next(ACTION_DELAY_MS);
            return;
        }
        // No tap could be dispatched at all; the ladder will try harder next tick.
        next(FAST_DELAY_MS);
    }

    private boolean isEmptyHistory(String text) {
        return text.contains("orders list is empty") || text.contains("no orders found")
                || text.contains("no order history") || text.contains("no orders")
                || text.contains("no record found");
    }

    /**
     * One escalation step of the "+" tap ladder.
     *
     * @return true when a tap was actually dispatched (the next tick then checks whether
     *         the form appeared)
     */
    /**
     * Points the next tap at the button's real pixels instead of a guessed coordinate.
     *
     * <p>Uses {@link #takeScreenshot} (API 30+). If it is unavailable the caller simply
     * falls through to the coordinate ladder, so this is a pure improvement and never a
     * new failure mode.</p>
     *
     * <p>This is the fix for "reaches Order History but never taps +": the button exposes
     * no usable node and every coordinate guess depends on hand-measured fractions that
     * shift with screen size, aspect ratio, insets and DPI. Finding the orange disc in the
     * actual screenshot removes that dependency.</p>
     *
     * @return true when a screenshot request was started, meaning the caller should wait
     *         rather than tap something now.
     */
    private boolean tryTapAddButtonViaScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        if (fabScanInFlight) return false;

        int width = screenWidthPx();
        int height = screenHeightPx();
        if (width <= 0 || height <= 0) return false;

        fabScanInFlight = true;
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY,
                    getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult result) {
                            fabScanInFlight = false;
                            Rect hit = locateAddButtonInScreenshot(result);
                            if (hit == null) {
                                // Nothing button-shaped: most likely the screen has not
                                // finished drawing yet. Let the next tick retry.
                                Log.i(TAG, "Screenshot found no + button; retrying next tick");
                                next(FAST_DELAY_MS);
                                return;
                            }
                            fabViaScreenshot = true;
                            lastActionAt = System.currentTimeMillis();
                            workflow.moveTo(BotState.HISTORY,
                                    "Tapping + at " + hit.exactCenterX() + ","
                                            + hit.exactCenterY() + " (found by screenshot)");
                            refreshPanel();
                            Log.i(TAG, "Tapping FAB from screenshot at "
                                    + hit.exactCenterX() + "," + hit.exactCenterY());
                            if (AccessibilityTree.tapRect(gestures, hit)) {
                                // Give the form time to open before judging anything.
                                next(ACTION_DELAY_MS);
                            } else {
                                next(FAST_DELAY_MS);
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            fabScanInFlight = false;
                            // The caller already returns true, so make sure the loop keeps
                            // moving instead of stalling here.
                            Log.w(TAG, "takeScreenshot failed: " + errorCode);
                            next(FAST_DELAY_MS);
                        }
                    });
            return true;
        } catch (Exception e) {
            fabScanInFlight = false;
            Log.w(TAG, "takeScreenshot threw", e);
            return false;
        }
    }

    /**
     * Converts a screenshot into a tap target.
     *
     * <p>Rows are copied one at a time on purpose: {@code getPixels} on the whole bitmap
     * needs a contiguous width*height buffer and throws if the stride is padded, while a
     * row-sized buffer always fits.</p>
     */
    private Rect locateAddButtonInScreenshot(ScreenshotResult result) {
        Bitmap bitmap = null;
        android.hardware.HardwareBuffer buffer = result.getHardwareBuffer();
        try {
            bitmap = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
            if (bitmap == null) return null;

            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            if (width <= 0 || height <= 0) return null;

            int[] argb = new int[width * height];
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1);
                System.arraycopy(row, 0, argb, y * width, width);
            }

            FabFinder.Hit hit = FabFinder.find(argb, width, height);
            if (hit == null) return null;
            Log.i(TAG, "Screenshot FAB hit: " + hit);

            // The accessibility window can differ from the raw display (status bar,
            // navigation bar, display scaling). Rescale only when they clearly differ, so a
            // normal full-screen app uses the screenshot coordinates untouched.
            int screenWidth = screenWidthPx();
            int screenHeight = screenHeightPx();
            Rect bounds = new Rect(hit.bounds);
            if (screenWidth > 0 && screenHeight > 0
                    && (Math.abs(width - screenWidth) > screenWidth * 0.08f
                        || Math.abs(height - screenHeight) > screenHeight * 0.08f)) {
                float scaleX = screenWidth / (float) width;
                float scaleY = screenHeight / (float) height;
                bounds.set(Math.round(bounds.left * scaleX), Math.round(bounds.top * scaleY),
                        Math.round(bounds.right * scaleX), Math.round(bounds.bottom * scaleY));
                Log.i(TAG, "Rescaled FAB " + width + "x" + height + " -> "
                        + screenWidth + "x" + screenHeight);
            }
            return bounds;
        } catch (Exception e) {
            Log.w(TAG, "Could not read the screenshot", e);
            return null;
        } finally {
            if (bitmap != null) bitmap.recycle();
            // A HardwareBuffer holds native memory; one leaked per tap would add up fast.
            try {
                buffer.close();
            } catch (Exception ignored) {
                // Already closed, or closed by the platform.
            }
        }
    }

    private boolean tapAddButton(AccessibilityNodeInfo root) {
        AccessibilityTree.AddButtonTarget target =
                AccessibilityTree.findAddButton(root, screenWidthPx(), screenHeightPx());

        // ---- Stage 0: ask the screen itself where the button is.
        //      The button has no usable node and coordinate guesses have been wrong twice,
        //      so a screenshot is the only reliable source of its position. Tried once.
        if (historyTicks <= 1 && !fabViaScreenshot && tryTapAddButtonViaScreenshot()) {
            return true;
        }

        // ---- Stage 1 (ticks 1-2): a node with real evidence, tapped physically at the
        //      centre of its bounds. This is what makes the custom FAB work: the gesture
        //      does not depend on the view's own click handler firing.
        if (target != null && !target.weak && historyTicks <= 2) {
            Log.i(TAG, "FAB candidate: " + target);
            if (AccessibilityTree.tapRect(gestures, target.bounds)) {
                lastActionAt = System.currentTimeMillis();
                Log.i(TAG, "Tapped FAB at its bounds centre");
                return true;
            }
        }

        // ---- Stage 2 (tick 3): native ACTION_CLICK on the clickable ancestor, as a
        //      second opinion for views that only respond to accessibility clicks.
        if (target != null && !target.weak && historyTicks <= 3) {
            AccessibilityNodeInfo clickable = AccessibilityTree.clickableAncestor(target.node, 4);
            if (clickable != null && AccessibilityTree.click(clickable, gestures)) {
                lastActionAt = System.currentTimeMillis();
                Log.i(TAG, "Tapped FAB via native ACTION_CLICK");
                return true;
            }
            if (AccessibilityTree.tapRect(gestures, target.bounds)) {
                lastActionAt = System.currentTimeMillis();
                return true;
            }
        }

        // ---- Stage 3 (ticks 4-5): a shape/position-only node. Less certain than stage 1
        //      but still far more precise than a fixed coordinate.
        if (target != null && target.weak && historyTicks <= 5) {
            Log.i(TAG, "FAB (shape+corner only): " + target);
            if (AccessibilityTree.tapRect(gestures, target.bounds)) {
                lastActionAt = System.currentTimeMillis();
                return true;
            }
        }

        // ---- Stage 4 (ticks 6+): fixed coordinates at the bottom-right corner, then a
        //      small ladder of neighbouring positions.
        Rect guess = historyTicks >= 8
                ? fabFallbackBounds(historyTicks - 8)
                : AccessibilityTree.assumedFabBounds(
                        screenWidthPx(), screenHeightPx(), densityDpi());
        Log.w(TAG, "No usable FAB node; tapping assumed corner "
                + guess.exactCenterX() + "," + guess.exactCenterY());
        if (AccessibilityTree.tapRect(gestures, guess)) {
            lastActionAt = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    /**
     * Fixed ladder of alternative corner positions, in case the FAB is not exactly at
     * 88% / 88% (different screen ratio, navigation bar, inset padding).
     */
    private Rect fabFallbackBounds(int step) {
        int width = screenWidthPx();
        int height = screenHeightPx();
        // Centred on the measured FAB position (0.867 / 0.902), then small steps in
        // each direction to absorb navigation-bar and inset differences.
        float[][] offsets = {
                {0.867f, 0.902f}, {0.90f, 0.92f}, {0.83f, 0.88f},
                {0.90f, 0.86f}, {0.82f, 0.93f}, {0.78f, 0.88f}
        };
        float[] point = offsets[Math.max(0, Math.min(step, offsets.length - 1))];
        Rect base = AccessibilityTree.assumedFabBounds(width, height, densityDpi());
        int radius = Math.max(1, base.width() / 2);
        int centerX = Math.round(width * point[0]);
        int centerY = Math.round(height * point[1]);
        return new Rect(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
    }

    /**
     * Gives up on one retailer without touching any other: records {@code ERROR}, resets
     * the per-screen budgets and walks back to a clean list position. Crucially it clears
     * the active retailer, so the next pass must pick a different SMR id.
     */
    private void abandonRetailer(String retailerId, String reason) {
        if (!ledger.record(retailerId, LedgerStatus.ERROR, reason)) {
            fail("Could not record ERROR for " + retailerId + "; stopping to avoid a repeat");
            return;
        }
        Log.w(TAG, "Retailer " + retailerId + " abandoned: " + reason);
        aakritiAfterRetailerError(reason);
        resetCopyFlow();
        historyTicks = 0;
        detailsTicks = 0;
        copyFormTicks = 0;
        unknownTicks = 0;
        workflow.clearRetailer();
        // The row that failed must not be retried immediately, even if it is still the
        // first unprocessed-looking row on the list.
        lastClickedRetailerId = null;
        sameRetailerClickCount = 0;
        returningToRetailerList = true;
        backSteps = 0;
        workflow.moveTo(BotState.RETURNING_TO_LIST, retailerId + " skipped: " + reason);
        refreshPanel();
        performGlobalAction(GLOBAL_ACTION_BACK);
        next(ACTION_DELAY_MS);
    }

    // ---------------------------------------------------------- screen metrics

    private int screenWidthPx() {
        if (cachedWidth > 0) return cachedWidth;
        measureScreen();
        return cachedWidth;
    }

    private int screenHeightPx() {
        if (cachedHeight > 0) return cachedHeight;
        measureScreen();
        return cachedHeight;
    }

    private int densityDpi() {
        if (cachedDensity <= 0) measureScreen();
        return cachedDensity;
    }

    /** Real display metrics, including the screen the app's window may not fill. */
    private void measureScreen() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.WindowManager windowManager =
                        (android.view.WindowManager) getSystemService(Context.WINDOW_SERVICE);
                if (windowManager != null) {
                    android.graphics.Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
                    cachedWidth = bounds.width();
                    cachedHeight = bounds.height();
                }
            } else {
                android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                android.view.WindowManager windowManager =
                        (android.view.WindowManager) getSystemService(Context.WINDOW_SERVICE);
                if (windowManager != null) {
                    //noinspection deprecation
                    windowManager.getDefaultDisplay().getRealMetrics(metrics);
                    cachedWidth = metrics.widthPixels;
                    cachedHeight = metrics.heightPixels;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Screen measurement failed", e);
        }
        cachedDensity = getResources().getDisplayMetrics().densityDpi;
        if (cachedWidth <= 0) cachedWidth = 1080;
        if (cachedHeight <= 0) cachedHeight = 2340;
    }

    // ------------------------------------------------------- screen: order form

    /**
     * Drives the "New Order Milk" form.
     *
     * <p>The operator's rule, and the reason this is simple: after "+" the form is
     * <b>always</b> empty, so the bot must not inspect it at all — it taps Copy Order
     * straight away, picks a date, and then lets the app say whether that date had data.
     * Whether the form "looks ready" is irrelevant and is never consulted before copying.</p>
     *
     * <p>Quantity parsing used to run here and caused the failure that stopped the bot:
     * the item name "FCM 1000 ML" was read as a positive total, so an empty form was
     * declared ready, Copy Order was skipped, and the bot sat waiting for a payment screen
     * that could never appear.</p>
     */
    private void onOrderForm(AccessibilityNodeInfo root, String text) {
        String expected = workflow.retailerId();
        if (!RetailerPolicy.isRetailerId(expected)) {
            fail("Order form is open without a verified retailer");
            return;
        }

        switch (copyPhase) {
            case NONE:
            case OPEN_COPY: {
                // A dialog from the previous date must be gone before Copy Order can be
                // reached. Clearing the flag here is what makes the retry loop terminate:
                // once the dialogue closes, the next pass taps Copy Order for the new date.
                if (awaitingRetryAfterPopup) {
                    if (!CalendarDateSelector.isCalendar(root) && popupBlocksCopy(root, text)) {
                        popupDismissTicks++;
                        if (popupDismissTicks > MAX_POPUP_DISMISS_TICKS) {
                            abandonOrder(expected, "The app's error dialog would not close");
                            return;
                        }
                        workflow.moveTo(BotState.COPY_ORDER,
                                "Dismissing the error dialog before retrying");
                        refreshPanel();
                        tapDialogOk(root);
                        next(ACTION_DELAY_MS);
                        return;
                    }
                    awaitingRetryAfterPopup = false;
                    popupDismissTicks = 0;
                }

                // The form is always empty at this point, so nothing is inspected: copy now.
                if (tapLabel(root, "Copy Order", "Copy order", "COPY ORDER")) {
                    copyOutcomeBefore(text);
                    awaitingRetryAfterPopup = false;
                    popupDismissTicks = 0;
                    copyPhase = CopyPhase.AWAIT_CALENDAR;
                    calendarWaitTicks = 0;
                    workflow.moveTo(BotState.COPY_ORDER, "Copy Order tapped for "
                            + DateRollbackPolicy.label(dateAttempt) + " (" + expected + ")");
                    refreshPanel();
                    next(ACTION_DELAY_MS);
                    return;
                }
                if (copyFormTicks++ > MAX_COPY_FORM_TICKS) {
                    abandonOrder(expected, "Copy Order control never appeared");
                    return;
                }
                next(IDLE_DELAY_MS);
                return;
            }
            case AWAIT_CALENDAR:
            case PICK_DATE:
            case CONFIRM_DATE:
                // Checked FIRST and unconditionally. This calendar's confirm button
                // *commits* whatever day is selected, and it opens with the PRESENT day
                // selected. So if the calendar is ever misrouted into the dialog branch,
                // the bot presses OK and silently orders for today - the exact reported
                // bug. Nothing may tap OK while this dialog is on screen.
                // The calendar is drawn inside this same form window on some builds.
                if (CalendarDateSelector.isCalendar(root)) {
                    onCalendar(root);
                    return;
                }
                // An error dialog can appear without a calendar ever being drawn (some builds
                // copy the pre-selected date straight away), so check it before waiting.
                if (popupBlocksCopy(root, text)) {
                    onCopyFailure(root);
                    return;
                }
                if (calendarWaitTicks++ > MAX_CALENDAR_WAIT_TICKS) {
                    if (copyPhase == CopyPhase.AWAIT_CALENDAR) {
                        abandonOrder(expected, "The date dialog never opened");
                    } else {
                        onCopyFailure(root);
                    }
                    return;
                }
                next(FAST_DELAY_MS);
                return;
            case AWAIT_RESULT:
                onAwaitCopyResult(root, text, expected);
                return;
            default:
                next(IDLE_DELAY_MS);
        }
    }

    /**
     * The only success test in the copy flow: <b>no dialog appeared</b>.
     *
     * <p>Nothing about the items, quantities or totals is read. The app signals a failed
     * copy with an error dialog ("No order to copy from selected date"), and signals a
     * successful one by simply returning to a form that now has rows in it. So after a
     * short settle period this submits; it never inspects the table.</p>
     *
     * <p>Past behaviour worth remembering: an earlier version parsed quantities and read
     * the "1000" in the item name "FCM 1000 ML" as a real quantity, which made an empty
     * form look ready. The form is now never parsed, so that class of bug cannot recur.</p>
     */
    private void onAwaitCopyResult(AccessibilityNodeInfo root, String text, String expected) {
        // 1. A dialog means this date has no data. Dismiss it and step back a day.
        if (popupBlocksCopy(root, text)) {
            copyOutcomeRefused();
            onCopyFailure(root);
            return;
        }

        // 2. No dialog yet. Look every 150 ms: the moment the copied rows are in the form
        //    the wait is over; without that proof, the settle period is waited out as before.
        if (resultTicks < COPY_SETTLE_TICKS) {
            if (resultTicks == 0) {
                resultTicks = 1;
                workflow.moveTo(BotState.COPY_ORDER, "Checking " + DateRollbackPolicy.label(dateAttempt)
                        + ": order or error dialog");
                refreshPanel();
            }
            if (copyOutcomeWaits(root, text)) return;
            resultTicks = COPY_SETTLE_TICKS;
        }

        // 3. No dialog after the settle period: the copy worked. Submit without inspecting.
        if (!hasPlaceOrder(root)) {
            orderReadyTicks++;
            if (orderReadyTicks > MAX_ORDER_READY_TICKS) {
                abandonOrder(expected, "No dialog, but PLACE ORDER never became available");
                return;
            }
            workflow.moveTo(BotState.COPY_ORDER, "Order loaded; waiting for PLACE ORDER");
            refreshPanel();
            next(ACTION_DELAY_MS);
            return;
        }
        orderReadyTicks = 0;
        workflow.moveTo(BotState.PLACE_ORDER,
                "No error dialog after " + DateRollbackPolicy.label(dateAttempt)
                        + "; placing the order for " + expected);
        refreshPanel();
        onReadyToPlace(root);
    }

    /**
     * Gives up on one retailer without placing an order.
     *
     * <p>Records {@link LedgerStatus#SKIPPED_NO_DATA} so the retailer is never revisited
     * today, then walks back to the customer list for the next SMR id.</p>
     */
    private void abandonOrder(String retailerId, String reason) {
        if (ledger.record(retailerId, LedgerStatus.SKIPPED_NO_DATA, reason)) {
            skipLog(retailerId, SkipLine.COPY_PROBLEM, reason);
            workflow.moveTo(BotState.RETURNING_TO_LIST, retailerId + " skipped: " + reason);
            refreshPanel();
        } else {
            fail("Could not record SKIPPED_NO_DATA for " + retailerId);
            return;
        }
        resetCopyFlow();
        goBackTo(2);
    }

    private void onCalendar(AccessibilityNodeInfo root) {
        String expected = workflow.retailerId();
        if (!RetailerPolicy.isRetailerId(expected)) {
            fail("Date dialog is open without a verified retailer");
            return;
        }
        // T is whatever date the dialog itself has highlighted when it first opens for this
        // retailer - the orange disc. Nothing is chosen until it has been read, and the
        // phone's clock is not asked: the dates tried are T-1 ... T-5 counted from it.
        if (calendarAnchor == null && !readCalendarAnchor(root, expected)) return;
        if (candidates.length != DateRollbackPolicy.MAX_LOOK_BACK_DAYS
                || !calendarAnchor.equals(candidates[0].plusDays(1))) {
            candidates = DateRollbackPolicy.candidateArray(calendarAnchor);
        }
        if (dateAttempt >= candidates.length) {
            onCopyFailure(root);
            return;
        }
        LocalDate target = candidates[dateAttempt];

        if (copyPhase == CopyPhase.AWAIT_CALENDAR || copyPhase == CopyPhase.OPEN_COPY) {
            copyPhase = CopyPhase.PICK_DATE;
            datePickTicks = 0;
            calendarVerifyTicks = 0;
            calendarMonthSteps = 0;
            calendarScreenNote = "";
        }
        // T, fixed for this retailer's whole ladder: the date the dialog opened on. It and
        // anything later may never be chosen.
        LocalDate today = calendarAnchor;

        switch (copyPhase) {
            case PICK_DATE: {
                if (datePickTicks++ > MAX_DATE_PICK_TICKS) {
                    // Spending too long here is the bot's trouble, not the app saying "no
                    // data", so it must not consume a date.
                    calendarMiss(root, target, expected, "ran out of time choosing the day");
                    return;
                }
                CalendarDateSelector.Outcome outcome =
                        CalendarDateSelector.selectDate(root, target, gestures, today, false);
                switch (outcome) {
                    case TAPPED:
                    case ALREADY_SELECTED:
                        copyPhase = CopyPhase.CONFIRM_DATE;
                        workflow.moveTo(BotState.COPY_ORDER,
                                "Selected " + target + " (" + DateRollbackPolicy.label(dateAttempt) + ")");
                        refreshPanel();
                        next(FAST_DELAY_MS);
                        return;
                    case STEPPED_BACK_MONTH:
                        workflow.moveTo(BotState.COPY_ORDER,
                                "Moved the calendar one month back for " + target);
                        refreshPanel();
                        next(ACTION_DELAY_MS);
                        return;
                    case DAY_NOT_FOUND:
                    case MONTH_NOT_AVAILABLE:
                    default:
                        // The accessibility tree offers no cell for this day. That says
                        // nothing about the screen: the day is drawn there all the same.
                        // Go on to the look-and-press step, which works from the pixels.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            copyPhase = CopyPhase.CONFIRM_DATE;
                            workflow.moveTo(BotState.COPY_ORDER,
                                    "Looking for " + target.getDayOfMonth()
                                            + " on the calendar as drawn ("
                                            + DateRollbackPolicy.label(dateAttempt) + ")");
                            refreshPanel();
                            next(FAST_DELAY_MS);
                            return;
                        }
                        calendarMiss(root, target, expected, "no cell for the day in the dialog");
                        return;
                }
            }
            case CONFIRM_DATE: {
                // OK is pressed only once the highlight is SEEN on the requested day. The
                // dialog opens with the present day highlighted, so pressing OK on the
                // strength of "the tap was sent" is how today's date gets confirmed.
                // The look is a screenshot, so the rest happens in onCalendarLook().
                if (startCalendarLook(target, today, expected)) return;
                // No screenshot on this Android version: the tree is all there is.
                confirmFromTree(root, target, today, expected, "screenshots are not available");
                return;
            }
            case AWAIT_RESULT: {
                if (CalendarDateSelector.isCalendar(root)) {
                    if (resultTicks++ > MAX_COPY_RESULT_TICKS) {
                        onCopyFailure(root);
                        return;
                    }
                    next(ACTION_DELAY_MS);
                    return;
                }
                next(FAST_DELAY_MS);
                return;
            }
            default:
                next(FAST_DELAY_MS);
        }
    }

    /**
     * Reads T - the date the open calendar has highlighted - into {@link #calendarAnchor}.
     *
     * <p>First from the dialog's own description of itself (the selected day, or the
     * headline, which always shows the selected date). If the dialog describes neither,
     * from the screen: the orange disc is located in a screenshot. If T still cannot be
     * read the dialog is closed with CANCEL and reopened, and after
     * {@link #MAX_CALENDAR_MISSES} rounds the retailer is skipped. The phone's date is
     * never used as a substitute.</p>
     *
     * @return true when T is known and the caller may go on this tick
     */
    private boolean readCalendarAnchor(AccessibilityNodeInfo root, String expected) {
        LocalDate shown = CalendarDateSelector.highlightedDate(root);
        if (shown != null) {
            setCalendarAnchor(shown, "the dialog reports it selected");
            return true;
        }
        anchorTicks++;
        if (anchorTicks > MAX_ANCHOR_TICKS) {
            calendarMiss(root, null, expected,
                    "could not read which date the calendar has highlighted");
            return false;
        }
        // Give the dialog one tick to finish opening, then look at the screen itself.
        if (anchorTicks >= 2 && startAnchorLook(root)) return false;
        workflow.moveTo(BotState.COPY_ORDER, "Reading the highlighted date in the calendar ("
                + anchorTicks + "/" + MAX_ANCHOR_TICKS + ")");
        refreshPanel();
        next(FAST_DELAY_MS);
        return false;
    }

    private void setCalendarAnchor(LocalDate shown, String proof) {
        calendarAnchor = shown;
        anchorTicks = 0;
        candidates = DateRollbackPolicy.candidateArray(shown);
        Log.i(TAG, "Calendar opened on " + shown + " (" + proof + "); dates to try: "
                + java.util.Arrays.toString(candidates));
        workflow.moveTo(BotState.COPY_ORDER, "Calendar is highlighted on " + shown
                + "; choosing " + candidates[Math.min(dateAttempt, candidates.length - 1)]
                + " (" + DateRollbackPolicy.label(dateAttempt) + ")");
        refreshPanel();
    }

    /**
     * Finds the orange disc in a screenshot and takes its date as T. Needs the month the
     * dialog is showing, which is read from the dialog; without it the picture of a grid
     * cannot be turned into dates.
     *
     * @return true when a look was started (or is under way); the caller must wait
     */
    private boolean startAnchorLook(AccessibilityNodeInfo root) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        final YearMonth month = CalendarDateSelector.visibleMonth(root);
        if (month == null) return false;
        if (calendarScanInFlight) return true;
        calendarScanInFlight = true;
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY,
                    getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult result) {
                            calendarScanInFlight = false;
                            int[] size = new int[2];
                            int[] argb = pixelsOf(result, size);
                            if (!running) return;
                            try {
                                if (calendarAnchor == null && argb != null
                                        && copyPhase != CopyPhase.NONE) {
                                    CalendarGridFinder.Reading reading = CalendarGridFinder.read(
                                            argb, size[0], size[1], month, deviceWeekStart());
                                    calendarScreenNote = reading.toString();
                                    Log.i(TAG, "Calendar look for the highlighted date: "
                                            + calendarScreenNote);
                                    if (reading.ok() && reading.selectedDay >= 1
                                            && reading.selectedDay <= month.lengthOfMonth()) {
                                        setCalendarAnchor(month.atDay(reading.selectedDay),
                                                "orange highlight seen on " + reading.selectedDay);
                                    }
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Calendar look failed", e);
                            }
                            next(FAST_DELAY_MS);
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            calendarScanInFlight = false;
                            Log.w(TAG, "takeScreenshot (highlighted date) failed: " + errorCode);
                            if (running) next(FAST_DELAY_MS);
                        }
                    });
            return true;
        } catch (Exception e) {
            calendarScanInFlight = false;
            Log.w(TAG, "takeScreenshot (highlighted date) threw", e);
            return false;
        }
    }

    /**
     * The bot could not choose {@code target} in the calendar. This is NOT the app saying
     * "no data", so the date ladder must not move: advancing here is what silently consumed
     * a date and then abandoned the retailer. Close the dialog with CANCEL (never OK, which
     * would commit whichever day is highlighted) and try the SAME date again; after
     * {@link #MAX_CALENDAR_MISSES} the retailer is skipped with the reason on record.
     */
    private void calendarMiss(AccessibilityNodeInfo root, LocalDate target, String expected,
                              String why) {
        // target is null when the miss happened before any date could be worked out: the
        // highlighted date itself could not be read.
        String what = target == null ? "the highlighted date"
                : target + " (" + DateRollbackPolicy.label(dateAttempt) + ")";
        String detail = why;
        if (!calendarScreenNote.isEmpty()) detail += "; screen: " + calendarScreenNote;
        String tree = target == null ? "" : CalendarDateSelector.diagnose(root, target);
        if (tree != null && !tree.isEmpty()) detail += "; tree: " + tree;
        Log.w(TAG, "Calendar miss for " + what + ": " + detail);

        if (calendarMisses >= MAX_CALENDAR_MISSES) {
            // Leave nothing half-chosen behind for the next retailer.
            CalendarDateSelector.cancel(root);
            skipRetailer(expected, target == null
                    ? "Could not read the highlighted date in the calendar: " + detail
                    : "Could not select " + what + " in the calendar: " + detail);
            return;
        }
        calendarMisses++;
        anchorTicks = 0;
        workflow.moveTo(BotState.COPY_ORDER,
                "Closing the calendar without choosing a date, then retrying "
                        + (target == null ? what : target + " (" + DateRollbackPolicy.label(dateAttempt))
                        + (target == null ? " (" : ", ")
                        + calendarMisses + "/" + MAX_CALENDAR_MISSES + "): " + why);
        refreshPanel();
        CalendarDateSelector.cancel(root);
        // Reset the per-attempt ticks but KEEP dateAttempt: calling resetCopyFlow() here
        // would zero it and silently restart the ladder at T-1 instead of retrying the
        // current date.
        copyPhase = CopyPhase.OPEN_COPY;
        copyFormTicks = 0;
        calendarWaitTicks = 0;
        datePickTicks = 0;
        calendarVerifyTicks = 0;
        calendarMonthSteps = 0;
        resultTicks = 0;
        orderReadyTicks = 0;
        awaitingRetryAfterPopup = false;
        popupDismissTicks = 0;
        next(ACTION_DELAY_MS);
    }

    /** Presses the calendar's OK. Only called once the requested day is known to be chosen. */
    private void confirmCalendar(AccessibilityNodeInfo root, LocalDate target, String proof) {
        Log.i(TAG, "Confirming " + target + ": " + proof);
        if (CalendarDateSelector.confirm(root)) {
            copyPhase = CopyPhase.AWAIT_RESULT;
            resultTicks = 0;
            copyOutcomeBegins();
            workflow.moveTo(BotState.COPY_ORDER,
                    "Confirmed " + target + " (" + proof + "); loading the copied order");
            refreshPanel();
            quick(CopyOutcome.FIRST_LOOK_MS);
            return;
        }
        // No OK button: the day tap itself submits the dialog.
        copyPhase = CopyPhase.AWAIT_RESULT;
        resultTicks = 0;
        copyOutcomeBegins();
        quick(CopyOutcome.FIRST_LOOK_MS);
    }

    /**
     * Decides from the accessibility tree alone. Used when the screen could not be read.
     * The rule is the same: OK only when the dialog reports the requested day as selected.
     */
    private void confirmFromTree(AccessibilityNodeInfo root, LocalDate target, LocalDate today,
                                 String expected, String whyNoPixels) {
        if (root == null || !CalendarDateSelector.isCalendar(root)) {
            // The dialog went away by itself; let the main loop see what is there now.
            next(FAST_DELAY_MS);
            return;
        }
        LocalDate shown = CalendarDateSelector.selectedDate(root);
        if (target.equals(shown)) {
            confirmCalendar(root, target, "the dialog reports it selected");
            return;
        }
        if (calendarVerifyTicks++ >= MAX_CALENDAR_VERIFY_TICKS) {
            calendarMiss(root, target, expected,
                    shown == null
                            ? "could not confirm which day is selected (" + whyNoPixels + ")"
                            : "the dialog still reports " + shown + " selected after "
                                    + MAX_CALENDAR_VERIFY_TICKS + " presses");
            return;
        }
        // Press again, this time with a real touch on the cell.
        CalendarDateSelector.Outcome outcome =
                CalendarDateSelector.selectDate(root, target, gestures, today, true);
        workflow.moveTo(BotState.COPY_ORDER,
                (shown == null ? "Cannot see which day is selected" : "Calendar still shows " + shown)
                        + "; pressing " + target + " again (" + outcome + ", "
                        + calendarVerifyTicks + "/" + MAX_CALENDAR_VERIFY_TICKS + ")");
        refreshPanel();
        next(outcome == CalendarDateSelector.Outcome.STEPPED_BACK_MONTH
                ? ACTION_DELAY_MS : FAST_DELAY_MS);
    }

    /**
     * Takes a screenshot of the calendar and continues in {@link #onCalendarLook}.
     *
     * @return true when the look was started (or one is already under way), meaning the
     *         caller must not do anything else this tick
     */
    private boolean startCalendarLook(final LocalDate target, final LocalDate today,
                                      final String expected) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        if (calendarScanInFlight) return true;
        calendarScanInFlight = true;
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY,
                    getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult result) {
                            calendarScanInFlight = false;
                            int[] size = new int[2];
                            int[] argb = pixelsOf(result, size);
                            if (!running) return;
                            try {
                                onCalendarLook(argb, size[0], size[1], target, today, expected);
                            } catch (Exception e) {
                                Log.e(TAG, "Calendar look failed", e);
                                next(ACTION_DELAY_MS);
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            calendarScanInFlight = false;
                            Log.w(TAG, "takeScreenshot (calendar) failed: " + errorCode);
                            if (!running) return;
                            try {
                                onCalendarLook(null, 0, 0, target, today, expected);
                            } catch (Exception e) {
                                Log.e(TAG, "Calendar look failed", e);
                                next(ACTION_DELAY_MS);
                            }
                        }
                    });
            return true;
        } catch (Exception e) {
            calendarScanInFlight = false;
            Log.w(TAG, "takeScreenshot (calendar) threw", e);
            return false;
        }
    }

    /**
     * Copies a screenshot into ARGB pixels; {@code size} receives width and height. Null on
     * any failure. Rows are copied one at a time for the reason given in
     * {@link #locateAddButtonInScreenshot}.
     */
    private int[] pixelsOf(ScreenshotResult result, int[] size) {
        Bitmap bitmap = null;
        android.hardware.HardwareBuffer buffer = result.getHardwareBuffer();
        try {
            bitmap = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
            if (bitmap == null) return null;
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            if (width <= 0 || height <= 0) return null;
            int[] argb = new int[width * height];
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1);
                System.arraycopy(row, 0, argb, y * width, width);
            }
            size[0] = width;
            size[1] = height;
            return argb;
        } catch (Exception e) {
            Log.w(TAG, "Could not read the screenshot", e);
            return null;
        } finally {
            if (bitmap != null) bitmap.recycle();
            try {
                buffer.close();
            } catch (Exception ignored) {
                // Already closed, or closed by the platform.
            }
        }
    }

    /** Screenshot coordinates to touch coordinates; identical unless the display is scaled. */
    private Rect toTouch(Rect inShot, int shotWidth, int shotHeight) {
        if (inShot == null) return null;
        int screenWidth = screenWidthPx();
        int screenHeight = screenHeightPx();
        Rect out = new Rect(inShot);
        if (screenWidth > 0 && screenHeight > 0
                && (Math.abs(shotWidth - screenWidth) > screenWidth * 0.08f
                    || Math.abs(shotHeight - screenHeight) > screenHeight * 0.08f)) {
            float scaleX = screenWidth / (float) shotWidth;
            float scaleY = screenHeight / (float) shotHeight;
            out.set(Math.round(out.left * scaleX), Math.round(out.top * scaleY),
                    Math.round(out.right * scaleX), Math.round(out.bottom * scaleY));
        }
        return out;
    }

    /** True when the bot's own panel covers the centre of {@code spot} and would take the touch. */
    private boolean panelCovers(Rect spot) {
        if (panel == null || spot == null) return false;
        Rect at = panel.boundsOnScreen();
        return at != null && at.contains(spot.centerX(), spot.centerY());
    }

    /** First column of the week on this device (Sunday for India). */
    private static DayOfWeek deviceWeekStart() {
        try {
            int first = java.util.Calendar.getInstance().getFirstDayOfWeek();   // 1 = Sunday
            return DayOfWeek.SUNDAY.plus(first - 1);
        } catch (Exception e) {
            return DayOfWeek.SUNDAY;
        }
    }

    /**
     * The calendar has been looked at. Decide by what is on the screen:
     * <ul>
     *   <li>highlight on the requested day - press OK;</li>
     *   <li>highlight anywhere else, or nowhere - press the requested day where it is
     *       drawn, with a real touch, and look again next tick;</li>
     *   <li>a later month is showing - press the previous-month arrow;</li>
     *   <li>the screen cannot be read as a calendar - fall back on the tree.</li>
     * </ul>
     * OK is never pressed from here unless the highlight was seen on the requested day.
     */
    private void onCalendarLook(int[] argb, int width, int height, LocalDate target,
                                LocalDate today, String expected) {
        AccessibilityNodeInfo root = resolveRoot();
        if (copyPhase != CopyPhase.CONFIRM_DATE) {
            next(FAST_DELAY_MS);
            return;
        }
        if (root == null || !CalendarDateSelector.isCalendar(root)) {
            // The dialog is no longer there; let the main loop see what is.
            next(FAST_DELAY_MS);
            return;
        }
        if (dateAttempt >= candidates.length || !target.equals(candidates[dateAttempt])) {
            next(FAST_DELAY_MS);
            return;
        }

        DayOfWeek weekStart = deviceWeekStart();
        YearMonth month = YearMonth.from(target);
        CalendarGridFinder.Reading reading = argb == null ? null
                : CalendarGridFinder.read(argb, width, height, month, weekStart);
        calendarScreenNote = reading == null ? "no screenshot" : reading.toString();
        Log.i(TAG, "Calendar look for " + target + ": " + calendarScreenNote);

        if (reading != null && reading.ok()) {
            int day = target.getDayOfMonth();
            if (reading.selectedDay == day) {
                confirmCalendar(root, target, "highlight seen on " + day);
                return;
            }
            String seen = reading.selectedDay > 0
                    ? "highlight is on " + reading.selectedDay : "no day is highlighted";
            if (calendarVerifyTicks++ >= MAX_CALENDAR_VERIFY_TICKS) {
                calendarMiss(root, target, expected,
                        seen + " after " + MAX_CALENDAR_VERIFY_TICKS + " presses on " + day);
                return;
            }
            Rect cellInShot = reading.cellOf(day);
            if (!CalendarGridFinder.looksSelectable(argb, width, height, cellInShot)) {
                // Pale grey: the dialog does not allow this day. Nothing to press.
                calendarMiss(root, target, expected,
                        day + " is greyed out in the calendar and cannot be chosen");
                return;
            }
            Rect cell = toTouch(cellInShot, width, height);
            if (panelCovers(cell)) {
                workflow.moveTo(BotState.COPY_ORDER,
                        "The bot panel is covering " + day + " on the calendar - drag the panel away");
                refreshPanel();
                next(IDLE_DELAY_MS);
                return;
            }
            boolean sent = AccessibilityTree.tapRect(gestures, cell);
            lastActionAt = System.currentTimeMillis();
            workflow.moveTo(BotState.COPY_ORDER,
                    "Pressing " + day + " at " + cell.centerX() + "," + cell.centerY()
                            + " (" + seen + "; press " + calendarVerifyTicks + "/"
                            + MAX_CALENDAR_VERIFY_TICKS + (sent ? ")" : ", touch refused)"));
            refreshPanel();
            Log.i(TAG, "Pressed day " + day + " at " + cell.centerX() + "," + cell.centerY()
                    + " sent=" + sent + " (" + seen + ")");
            next(ACTION_DELAY_MS);
            return;
        }

        // Not the target's month. If the month after it is showing (the first days of a
        // month, when T-1..T-5 reach back into the previous one), step back once.
        if (argb != null && calendarMonthSteps < 2) {
            CalendarGridFinder.Reading later =
                    CalendarGridFinder.read(argb, width, height, month.plusMonths(1), weekStart);
            if (later.ok() && later.previousMonthArrow != null) {
                Rect arrow = toTouch(later.previousMonthArrow, width, height);
                if (!panelCovers(arrow) && AccessibilityTree.tapRect(gestures, arrow)) {
                    calendarMonthSteps++;
                    lastActionAt = System.currentTimeMillis();
                    workflow.moveTo(BotState.COPY_ORDER,
                            "Calendar shows " + month.plusMonths(1) + "; going back one month for "
                                    + target);
                    refreshPanel();
                    next(ACTION_DELAY_MS);
                    return;
                }
            }
        }

        // The screen could not be read as this month's calendar (mid-animation, the panel
        // lying across the grid, an unexpected look). Fall back on the tree.
        confirmFromTree(root, target, today, expected, calendarScreenNote);
    }

    /**
     * Brings the target app back to the foreground.
     *
     * <p>Used as the fallback way out of a stuck screen. Pressing Back repeatedly can end
     * up in the launcher or in another app entirely, and a global Back from there has no
     * predictable result — so the recovery is to re-open Sudha Daily Orders and start
     * again from its home page, which the ordinary flow already knows how to drive.</p>
     *
     * @return true when a launch intent was actually sent
     */
    private boolean launchTargetApp() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(targetPackage);
            if (intent == null) {
                Log.w(TAG, "No launch intent for " + targetPackage);
                return false;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(intent);
            lastActionAt = System.currentTimeMillis();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Could not launch " + targetPackage, e);
            return false;
        }
    }

    /**
     * The app refused the selected date: dismiss the popup and walk one day further back.
     *
     * <p>Called for any dialog that appears after the calendar is confirmed. It is the
     * app's own signal, so no quantity or item is ever inspected: the wording
     * ("No order to copy from selected date") is only one of several accepted hints, and
     * an unexpected dialogue still counts as a failure.</p>
     *
     * <p>Two details matter for correctness:</p>
     * <ul>
     *   <li><b>One popup advances one date only.</b> While the popup is still on screen this
     *       keeps pressing OK without spending another attempt, otherwise a single error
     *       would silently consume two or three of the five allowed dates.</li>
     *   <li><b>Giving up is bounded by the date count</b>, so the retailer is skipped
     *       exactly after T-5, having dismissed that final dialog and pressed Back.</li>
     * </ul>
     */
    private void onCopyFailure(AccessibilityNodeInfo root) {
        String expected = workflow.retailerId();
        if (!RetailerPolicy.isRetailerId(expected)) {
            fail("Copy failed without a verified retailer");
            return;
        }

        // Still the popup already counted: keep dismissing it, spend no new attempt.
        if (awaitingRetryAfterPopup) {
            popupDismissTicks++;
            if (popupDismissTicks > MAX_POPUP_DISMISS_TICKS) {
                abandonOrder(expected, "The app's popup would not close after "
                        + DateRollbackPolicy.label(Math.max(0, dateAttempt - 1)));
                return;
            }
            workflow.moveTo(BotState.COPY_ORDER, "Closing the popup for "
                    + DateRollbackPolicy.label(Math.max(0, dateAttempt - 1)));
            refreshPanel();
            dismissPopup(root);
            next(ACTION_DELAY_MS);
            return;
        }

        // A new popup: acknowledge it, then move one date further back.
        awaitingRetryAfterPopup = true;
        popupDismissTicks = 0;
        dismissPopup(root);

        dateAttempt++;

        // Step 4 of the rule: all five dates refused. The final dialog was already dismissed
        // above, so leave the order form with Back and move to the next retailer.
        if (dateAttempt >= MAX_DATE_ATTEMPTS) {
            workflow.moveTo(BotState.RETURNING_TO_LIST,
                    "No data for " + DateRollbackPolicy.label(0) + " to "
                            + DateRollbackPolicy.label(MAX_DATE_ATTEMPTS - 1)
                            + "; skipping " + expected);
            refreshPanel();
            resetCopyFlow();
            if (!ledger.record(expected, LedgerStatus.SKIPPED_NO_DATA,
                    "No order to copy for " + DateRollbackPolicy.label(0) + " to "
                            + DateRollbackPolicy.label(MAX_DATE_ATTEMPTS - 1))) {
                fail("Could not record SKIPPED_NO_DATA for " + expected);
                return;
            }
            skipLog(expected, SkipLine.NO_ORDER_5_DATES, "No order to copy for "
                    + DateRollbackPolicy.label(0) + " to " + DateRollbackPolicy.label(MAX_DATE_ATTEMPTS - 1));
            // Save the skip first, then leave the form: if the Back never registers, the
            // ledger already prevents this retailer from being retried.
            goBackTo(2);
            return;
        }

        // Re-open Copy Order and pick the next older date.
        calendarMisses = 0;
        copyPhase = CopyPhase.OPEN_COPY;
        copyFormTicks = 0;
        calendarWaitTicks = 0;
        datePickTicks = 0;
        resultTicks = 0;
        orderReadyTicks = 0;
        workflow.moveTo(BotState.COPY_ORDER,
                "No data for " + DateRollbackPolicy.label(dateAttempt - 1)
                        + "; trying " + DateRollbackPolicy.label(dateAttempt)
                        + " (" + expected + ")");
        refreshPanel();
        next(ACTION_DELAY_MS);
    }

    /**
     * Taps the error dialog's OK button, whichever window the dialog lives in.
     *
     * <p>This is the part that was failing. A dialog is often its own window, so it is not
     * always inside {@code getRootInActiveWindow()}, and a dialog's OK button frequently
     * reports no clickable ancestor at all. Both cases mean "OK" was never pressed.</p>
     *
     * <p>Strategy, in order:</p>
     * <ol>
     *   <li>the given root;</li>
     *   <li>every other target-package window — this is what catches a dialog drawn in a
     *       separate window while the form stays the "active" one;</li>
     *   <li>a physical tap on the OK node's bounds centre, for buttons that ignore
     *       ACTION_CLICK.</li>
     * </ol>
     *
     * @return true when an OK-style button was found and tapped
     */
    private boolean tapDialogOk(AccessibilityNodeInfo root) {
        // Never treat the calendar's OK as a dialog acknowledgement: it commits the date.
        if (root != null && CalendarDateSelector.isCalendar(root)) {
            Log.w(TAG, "Refusing to tap OK: this is the date dialog, not an error popup");
            return false;
        }
        String[] labels = {"OK", "Ok", "ok", "OKAY", "Okay", "DISMISS", "Dismiss",
                "CLOSE", "Close", "GOT IT", "Got it"};
        boolean tapped = tapOkIn(root, labels);
        if (tapped) return true;

        // A separate window for the dialog.
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) continue;
                    AccessibilityNodeInfo candidate = window.getRoot();
                    if (candidate == null || candidate == root) continue;
                    if (!isTargetRoot(candidate) && !hasDialogButton(candidate)) continue;
                    if (tapOkIn(candidate, labels)) return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Dialog window lookup failed", e);
        }
        return false;
    }

    /** Cheap check: does this window even contain one of the dialog's buttons? */
    private boolean hasDialogButton(AccessibilityNodeInfo windowRoot) {
        return AccessibilityTree.findClickableLabel(windowRoot, "OK") != null
                || AccessibilityTree.findClickableLabel(windowRoot, "Ok") != null
                || AccessibilityTree.findClickableLabel(windowRoot, "Dismiss") != null;
    }

    /** Exact-label OK search plus a physical fallback tap. */
    private boolean tapOkIn(AccessibilityNodeInfo root, String[] labels) {
        if (root == null) return false;
        for (String label : labels) {
            AccessibilityNodeInfo node = AccessibilityTree.findExact(root, label);
            if (node == null) continue;
            AccessibilityNodeInfo clickable = AccessibilityTree.clickableAncestor(node, 4);
            AccessibilityNodeInfo target = clickable != null ? clickable : node;
            if (AccessibilityTree.click(target, gestures)) {
                lastActionAt = System.currentTimeMillis();
                Log.i(TAG, "Dismissed the error dialog with \"" + label + "\"");
                return true;
            }
        }
        // Nothing clickable: tap the button's own bounds. A dialog button that reports no
        // clickable ancestor is common, and the gesture still lands on it.
        for (String label : labels) {
            AccessibilityNodeInfo node = AccessibilityTree.findExact(root, label);
            if (node == null) continue;
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.width() > 0 && bounds.height() > 0
                    && AccessibilityTree.tapRect(gestures, bounds)) {
                lastActionAt = System.currentTimeMillis();
                Log.i(TAG, "Dismissed the error dialog by tapping \"" + label + "\" bounds");
                return true;
            }
        }
        return false;
    }

    /**
     * Closes the app's "no data" popup.
     *
     * <p>Prefers the popup's own OK-style button over a system Back, because Back on some
     * builds also leaves the order form and would restart the whole retailer.</p>
     *
     * @return true when a button was tapped
     */
    private boolean dismissPopup(AccessibilityNodeInfo root) {
        return tapDialogOk(root);
    }

    // ------------------------------------------------------ order readiness

    /** True when PLACE ORDER is on screen as a tappable control. */
    private boolean hasPlaceOrder(AccessibilityNodeInfo root) {
        return AccessibilityTree.findClickableLabel(root, "PLACE ORDER") != null
                || AccessibilityTree.findClickableLabel(root, "Place Order") != null
                || AccessibilityTree.findContaining(root, "place order") != null;
    }

    /** Copies succeeded: verify, mark pending, tap PLACE ORDER, then drive payment. */
    private void onReadyToPlace(AccessibilityNodeInfo root) {
        String expected = workflow.retailerId();
        if (!RetailerPolicy.isRetailerId(expected)) {
            fail("Cannot place an order without a verified retailer");
            return;
        }
        if (ledger.hasPending()) {
            // Already submitted once; never place twice.
            onPendingScreen(root, AccessibilityTree.allText(root).toLowerCase(Locale.ROOT));
            return;
        }
        // Section 10: Apply Customer Demand overrides or Employee's Chance anti-bot scatter BEFORE PLACE ORDER
        int editedCells = QuantityModes.applyEditsToOrderForm(
                root, this, expected, ledger.processedCount() + 1, expectedCount, gestures);
        if (editedCells > 0) {
            workflow.moveTo(BotState.PLACE_ORDER, "Applied " + editedCells + " qty edit(s) for " + expected);
            refreshPanel();
            next(FAST_DELAY_MS);
            return;
        }
        workflow.moveTo(BotState.PLACE_ORDER, "Items copied; placing the order for " + expected);
        refreshPanel();

        // Flush the in-flight marker BEFORE tapping, so a crash cannot re-order.
        if (!ledger.markPending(expected, "PLACE_TAP_PENDING")) {
            fail("Could not persist the pre-submit marker; order was not placed");
            return;
        }
        AccessibilityNodeInfo placeButton = AccessibilityTree.findClickableLabel(root, "PLACE ORDER");
        if (placeButton == null) placeButton = AccessibilityTree.findClickableLabel(root, "Place Order");
        if (placeButton == null) {
            // The tap provably did not happen, so releasing the marker is safe.
            ledger.clearPending();
            fail("PLACE ORDER vanished before it could be tapped; order was not placed");
            return;
        }
        if (!AccessibilityTree.click(placeButton, gestures)) {
            ledger.clearPending();
            fail("PLACE ORDER could not be tapped; order was not placed");
            return;
        }
        lastActionAt = System.currentTimeMillis();
        placeTappedAt = lastActionAt;
        formAfterPlaceTicks = 0;
        // The waiting budget belongs to this order alone. It used to be cleared only on
        // START, so it added up across retailers and the run stopped with "never reached a
        // payment or success screen" a few retailers in, however well each one had gone.
        pendingTicks = 0;
        ledger.setPendingPhase(expected, "PLACE_TAPPED");
        resetCopyFlow();
        paymentActions = 0;
        workflow.moveTo(BotState.PAYMENT, "Order submitted; completing payment automatically");
        refreshPanel();
        next(ACTION_DELAY_MS);
    }

    // ------------------------------------------------ in-flight / payment chain

    /**
     * Handles every screen seen while an order is in flight: the payment chain, and
     * the failure mode where the app drops back to the list without confirming.
     */
    private void onPendingScreen(AccessibilityNodeInfo root, String textLower) {
        String pendingId = ledger.pendingId();
        if (!RetailerPolicy.isRetailerId(pendingId)) {
            fail("Ledger says an order is in flight but the retailer id is unreadable");
            return;
        }
        if (workflow.retailerId() == null) workflow.beginRetailer(pendingId);

        if (looksLikeSuccess(textLower)) {
            onSuccess(root);
            return;
        }

        if (paymentActions >= MAX_PAYMENT_ACTIONS) {
            fail("Payment chain exceeded " + MAX_PAYMENT_ACTIONS + " steps; reconcile "
                    + pendingId + " manually");
            return;
        }

        String phase = ledger.pendingPhase();

        // PLACE ORDER was pressed, Pay was not, and the order form is simply still there:
        // no payment screen, no dialog. That is what the app does when it answers with a
        // toast ("Today Order already created") - and a toast the service was not told
        // about looks exactly like this. Waiting longer cannot help, and stopping the whole
        // run for one retailer is what the operator asked not to happen. So after a fixed
        // wait the retailer is put on record as "not confirmed - check it" and the bot goes
        // on to the next SMR. PLACE ORDER is never pressed a second time.
        boolean formStillOpen = "PLACE_TAPPED".equals(phase)
                && !looksLikePaymentScreenText(textLower)
                && looksLikeOrderForm(textLower) && hasPlaceOrder(root)
                && !popupBlocksCopy(root, textLower);
        if (formStillOpen) {
            long now = System.currentTimeMillis();
            if (placeTappedAt <= 0) placeTappedAt = now;      // resumed after a restart
            formAfterPlaceTicks++;
            long waited = now - placeTappedAt;
            if (waited >= PLACE_NO_RESPONSE_MS && formAfterPlaceTicks >= MIN_FORM_AFTER_PLACE_TICKS) {
                onPlaceOrderUnanswered(pendingId, waited);
                return;
            }
        } else {
            formAfterPlaceTicks = 0;
        }

        // Time spent in front of the unchanged form is measured by the clock above, not by
        // this counter, so a burst of screen events cannot use the budget up early.
        if (!formStillOpen) pendingTicks++;
        if (pendingTicks > MAX_PENDING_TICKS) {
            fail("In-flight order " + pendingId + " never reached a payment or success screen "
                    + "(phase " + phase + "). Check the order in the app, then tap Reset.");
            return;
        }
        if ("PLACE_TAP_PENDING".equals(phase)
                && !looksLikePaymentScreenText(textLower) && !looksLikeSuccess(textLower)) {
            // The submit tap provably never fired, so nothing was ordered.
            if (pendingTicks > 4) {
                fail("Order " + pendingId + " was NOT submitted (the submit tap never fired). "
                        + "Check the app, then tap Reset to continue.");
                return;
            }
        }

        // Section 8 Step 7: Manual Payment Pause mode (if enabled in settings)
        boolean manualPaymentPause = prefs != null && prefs.getBoolean(MainActivity.KEY_MANUAL_PAYMENT_PAUSE, false);
        if (manualPaymentPause && looksLikePaymentScreenText(textLower)) {
            pause("Manual Payment: complete payment for " + pendingId + ", then tap START");
            return;
        }
        // Restarted mid-payment: navigation is safe, a second PAY is not.
        boolean maySubmitPayment = !pendingRecovery;
        if (looksLikePaymentScreenText(textLower)) {
            if (maySubmitPayment && tapLabel(root, "PAY", "Pay", "PAY NOW", "Pay Now")) {
                paymentActions++;
                ledger.setPendingPhase(pendingId, "PAY_TAPPED");
                workflow.moveTo(BotState.PAYMENT, "Pay tapped for " + pendingId);
                refreshPanel();
                next(ACTION_DELAY_MS);
                return;
            }
            if (tapLabel(root, "PROCEED", "Proceed", "CONTINUE", "Continue")) {
                paymentActions++;
                ledger.setPendingPhase(pendingId, "PROCEED_TAPPED");
                workflow.moveTo(BotState.PAYMENT, "Proceeded to payment for " + pendingId);
                refreshPanel();
                next(ACTION_DELAY_MS);
                return;
            }
            if (!maySubmitPayment) {
                fail("Restarted mid-payment for " + pendingId
                        + "; refusing a second PAY — reconcile it in the app");
                return;
            }
            workflow.moveTo(BotState.PAYMENT, "Waiting for a payment control");
            refreshPanel();
            next(IDLE_DELAY_MS);
            return;
        }

        if (tapLabel(root, "PROCEED", "Proceed", "CONFIRM", "Confirm", "CONTINUE", "Continue")) {
            paymentActions++;
            workflow.moveTo(BotState.PAYMENT, "Confirmed a step for " + pendingId);
            refreshPanel();
            next(ACTION_DELAY_MS);
            return;
        }

        // Generic acknowledgement dialogs, but only where a confirmation is expected.
        boolean confirmationScreen = textLower.contains("confirm")
                || textLower.contains("are you sure")
                || textLower.contains("submit")
                || textLower.contains("place order")
                || textLower.contains("payment")
                || textLower.contains("success")
                || textLower.contains("done");
        if (confirmationScreen && tapLabel(root, "OK", "Ok", "YES", "Yes", "DONE", "Done")) {
            paymentActions++;
            workflow.moveTo(BotState.PAYMENT, "Acknowledged a dialog for " + pendingId);
            refreshPanel();
            next(ACTION_DELAY_MS);
            return;
        }

        if (looksLikeRetailerListText(textLower)) {
            // Back on the list with an unresolved order: do not retry, do not re-place.
            if (ledger.record(pendingId, LedgerStatus.IN_FLIGHT_UNVERIFIED,
                    "Returned to the list with pending phase " + phase)) {
                pendingRecovery = false;
                workflow.clearRetailer();
                workflow.fail("Order for " + pendingId + " was not confirmed — check the app");
            } else {
                fail("Could not record the unverified order for " + pendingId);
            }
            refreshPanel();
            return;
        }

        if (formStillOpen) {
            long waited = System.currentTimeMillis() - placeTappedAt;
            workflow.moveTo(BotState.PAYMENT, "PLACE ORDER pressed for " + pendingId
                    + "; no payment screen yet (" + (waited / 1000) + "s of "
                    + (PLACE_NO_RESPONSE_MS / 1000) + "s)");
        } else {
            workflow.moveTo(BotState.PAYMENT, "Waiting for the next payment screen");
        }
        refreshPanel();
        next(IDLE_DELAY_MS);
    }


    /**
     * PLACE ORDER was pressed and nothing followed: the order form is still open and no
     * payment screen came. The retailer is recorded as unconfirmed (it shows under ERRORS
     * and is not touched again today) and the run continues with the next SMR.
     */
    private void onPlaceOrderUnanswered(String retailerId, long waitedMs) {
        String detail = "PLACE ORDER was pressed but no payment screen opened in "
                + (waitedMs / 1000) + "s and the order form stayed open. Most likely the app "
                + "answered \"Today Order already created\"; check this retailer in the app";
        if (!ledger.record(retailerId, LedgerStatus.IN_FLIGHT_UNVERIFIED, detail)) {
            fail("Could not record the unconfirmed order for " + retailerId);
            return;
        }
        Log.w(TAG, retailerId + ": " + detail);
        pendingRecovery = false;
        lastClickedRetailerId = null;
        sameRetailerClickCount = 0;
        resetCopyFlow();
        paymentActions = 0;
        pendingTicks = 0;
        formAfterPlaceTicks = 0;
        placeTappedAt = 0;
        unknownTicks = 0;
        workflow.clearRetailer();
        returningToRetailerList = true;
        backSteps = 1;
        relaunches = 0;
        workflow.moveTo(BotState.RETURNING_TO_LIST, retailerId
                + ": no payment screen after PLACE ORDER (probably already ordered today)"
                + " - noted under ERRORS, going to the next SMR");
        refreshPanel();
        performGlobalAction(GLOBAL_ACTION_BACK);
        lastActionAt = System.currentTimeMillis();
        next(ACTION_DELAY_MS);
    }

    // ------------------------------------------------------------ screen: success

    private void onSuccess(AccessibilityNodeInfo root) {
        String retailerId = workflow.retailerId();
        if (!RetailerPolicy.isRetailerId(retailerId)) {
            retailerId = ledger.pendingId();
        }
        if (!RetailerPolicy.isRetailerId(retailerId)) {
            // Success screen with no retailer context (e.g. after a restart): just leave
            // it, and fall into the bounded Back walk if the buttons do nothing.
            workThroughSuccessScreen(root);
            returningToRetailerList = true;
            backSteps = 0;
            next(ACTION_DELAY_MS);
            return;
        }

        if (!ledger.isProcessed(retailerId)) {
            if (!ledger.record(retailerId, LedgerStatus.SUCCESS, "Success screen acknowledged")) {
                fail("Could not persist SUCCESS for " + retailerId);
                return;
            }
        }
        pendingRecovery = false;
        pendingTicks = 0;
        placeTappedAt = 0;
        formAfterPlaceTicks = 0;
        workflow.moveTo(BotState.SUCCESS, retailerId + " ordered successfully");
        refreshPanel();

        workThroughSuccessScreen(root);
        workflow.clearRetailer();
        resetCopyFlow();
        // Reset the per-screen budgets but keep the run alive.
        unknownTicks = 0;
        detailsTicks = 0;
        historyTicks = 0;
        copyFormTicks = 0;
        returningToRetailerList = true;
        backSteps = 0;
        workflow.moveTo(BotState.RETURNING_TO_LIST, "Returning to the retailer list");
        refreshPanel();
        next(ACTION_DELAY_MS);
    }

    /** Taps Done/OK/Close on a success screen; no-op when none is present. */
    private void workThroughSuccessScreen(AccessibilityNodeInfo root) {
        String[] labels = {"Done", "DONE", "OK", "Close"};
        for (String label : labels) {
            AccessibilityNodeInfo node = AccessibilityTree.findClickableLabel(root, label);
            if (node != null && AccessibilityTree.click(node, gestures)) {
                lastActionAt = System.currentTimeMillis();
                return;
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Clicks the first matching label. Exact matches are preferred over substring
     * matches so "PAY" never hits "Payment History".
     */
    private boolean tapLabel(AccessibilityNodeInfo root, String... labels) {
        if (root == null || labels.length == 0) return false;
        if (System.currentTimeMillis() - lastActionAt < MIN_ACTION_GAP_MS) return false;
        for (String label : labels) {
            AccessibilityNodeInfo node = AccessibilityTree.findClickableLabel(root, label);
            if (node != null && AccessibilityTree.click(node, gestures)) {
                lastActionAt = System.currentTimeMillis();
                return true;
            }
        }
        for (String label : labels) {
            // Substring matching is only safe for real words; "OK"/"PAY"/"NO" would hit
            // unrelated text such as "Payment History".
            if (label.trim().length() < 4) continue;
            AccessibilityNodeInfo node = AccessibilityTree.findContaining(root, label);
            if (node != null) {
                AccessibilityNodeInfo clickable = AccessibilityTree.clickableAncestor(node, 4);
                AccessibilityNodeInfo target = clickable == null ? node : clickable;
                if (AccessibilityTree.click(target, gestures)) {
                    lastActionAt = System.currentTimeMillis();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Starts a bounded walk back to the retailer list. The remaining Back presses are
     * paced out by {@link #inspect} so a slow screen transition cannot eat them all.
     */
    private void goBackTo(int maxSteps) {
        returningToRetailerList = true;
        backSteps = 0;
        workflow.moveTo(BotState.RETURNING_TO_LIST, "Returning to the retailer list");
        refreshPanel();
        performGlobalAction(GLOBAL_ACTION_BACK);
        next(ACTION_DELAY_MS);
    }

    private void complete(String message) {
        running = false;
        workflow.moveTo(BotState.COMPLETE, message);
        refreshPanel();
        Log.i(TAG, "Complete: " + message + " after "
                + (System.currentTimeMillis() - runStartedAt) / 1000 + "s");
    }

    // -------------------------------------------------------- screen predicates

    /**
     * The app's own progress box and nothing else: "Loading", "In Progress",
     * "Getting placed orders...", "Making Payment...". Kept to short texts so that a full
     * screen which merely contains one of these words is not taken for it.
     */
    private boolean looksBusy(String textLower) {
        if (textLower == null) return false;
        String text = textLower.trim();
        if (text.isEmpty() || text.length() > 80) return false;
        return text.contains("loading") || text.contains("in progress")
                || text.contains("getting placed") || text.contains("making payment")
                || text.contains("please wait");
    }

    private boolean looksLikeHome(String textLower) {
        return textLower.contains("total customers")
                && (textLower.contains("customers") || textLower.contains("daily order"));
    }

    /**
     * Retailer list. Verified strings: "Total Customers: 125" header, the
     * "All Distributors" filter, and the "Show Retailers" checkbox.
     */
    private boolean looksLikeRetailerListText(String textLower) {
        return textLower.contains("total customers")
                || textLower.contains("show retailers")
                || textLower.contains("all distributors");
    }

    /**
     * Customer Details. Verified strings: title "Customer Details", the "Name" block
     * containing "(SMR2196)", "Distributor Name ( SM65 )", the "View Orders" section
     * with the Milk / PROD tabs and the Transactions / Pay bottom bar.
     */
    private boolean looksLikeDetails(String textLower) {
        if (textLower.contains("customer details")) return true;
        return textLower.contains("view orders") && textLower.contains("distributor name");
    }

    /**
     * Order history for one retailer.
     *
     * <p><b>Important:</b> this screen's own title is "New Order Milk", exactly the same
     * as the order form, so the title cannot be used to tell them apart. The reliable
     * markers are the previous-order cards: "Order created on ..", "Order Id", "Products",
     * "Order Qty", plus the "&lt; November,2026 &gt;" month switcher.</p>
     */
    private boolean looksLikeHistory(String textLower) {
        if (textLower.contains("order created on")) return true;
        if (textLower.contains("order qty")) return true;
        if (textLower.contains("order history") || textLower.contains("milk orders")) return true;
        return textLower.contains("order id") && textLower.contains("products");
    }

    /**
     * The "New Order Milk" order-entry form.
     *
     * <p>Verified markers: the header fields "Date / Item Group / Zone / Party", the item
     * table "Item Rate MQty EQty Total", the "Copy Order" link and the orange
     * "PLACE ORDER" button. Deliberately does <b>not</b> match on the title alone and does
     * not match "copy order" alone (a build could show it elsewhere).</p>
     */
    private boolean looksLikeOrderForm(String textLower) {
        if (textLower.contains("place order")) return true;
        if (textLower.contains("view order summary")) return true;
        if (textLower.contains("mqty") || textLower.contains("eqty")) return true;
        return textLower.contains("copy order")
                && (textLower.contains("item group") || textLower.contains("party"));
    }

    /**
     * The success dialog.
     *
     * <p>Verified string: <b>"Successfully Payment Created"</b> with a green "Done"
     * button.</p>
     *
     * <p><b>"Order placed" is deliberately NOT a success signal</b>: the order-history
     * cards use it as the status of *previous* orders, so treating it as success made the
     * bot mark a retailer SUCCESS the moment it reached the history screen.</p>
     */
    private boolean looksLikeSuccess(String textLower) {
        if (looksLikeHistory(textLower)) return false;
        if (textLower.contains("payment created")) return true;
        if (textLower.contains("successfully payment")) return true;
        if (textLower.contains("order placed successfully")) return true;
        if (textLower.contains("successfully placed")) return true;
        if (textLower.contains("thank you for your order")) return true;
        return textLower.contains("success")
                && (textLower.contains("done") || textLower.contains("created"));
    }

    /** Payment screen: "Paid Amount", "Payment Mode", "CASH", "Remarks", orange "Pay". */
    private boolean looksLikePaymentScreenText(String textLower) {
        if (textLower.contains("paid amount")) return true;
        if (textLower.contains("payment mode")) return true;
        if (textLower.contains("successfully payment")) return false;
        return textLower.contains("payment");
    }

    /**
     * True when a dialogue is on screen after a copy was confirmed.
     *
     * <p>The app's wording has never been read reliably, so this deliberately does not
     * depend on it. Any of these is enough:</p>
     * <ul>
     *   <li>a known "no data" style phrase, or</li>
     *   <li>an OK/Close-style button that was not there before the copy, or</li>
     *   <li>the app's own popup shape: a window that is not the order form.</li>
     * </ul>
     * A successful copy shows no dialogue, so this only fires on the error path.
     */
    private boolean popupBlocksCopy(AccessibilityNodeInfo root, String textLower) {
        // Safety first: the date dialog contains a CANCEL and an OK button, so without this
        // guard it looks exactly like a popup. Pressing OK there commits the present date.
        if (CalendarDateSelector.isCalendar(root)) return false;
        if (looksLikeCopyFailure(textLower)) return true;
        // The form's own buttons must not be mistaken for a popup.
        if (textLower.contains("place order") && textLower.contains("view order summary")
                && !textLower.contains("cancel")) {
            return false;
        }
        return AccessibilityTree.findClickableLabel(root, "OK") != null
                || AccessibilityTree.findClickableLabel(root, "Ok") != null
                || AccessibilityTree.findClickableLabel(root, "DISMISS") != null;
    }

    private boolean looksLikeCopyFailure(String textLower) {
        // "No order to copy from selected date" is the app's wording for this failure.
        return textLower.contains("no order to copy")
                || textLower.contains("nothing to copy")
                || textLower.contains("no data")
                || textLower.contains("no record")
                || textLower.contains("no item")
                || textLower.contains("nothing to copy")
                || textLower.contains("copy failed")
                || textLower.contains("unable to copy")
                || textLower.contains("not found for");
    }
}
