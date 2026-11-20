package com.vrksignatures.sudhaautobot;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Aakriti - the part of the app that watches the Sudha app change and keeps the bot working.
 *
 * <p>She does four things, and the service calls her at exactly four places:</p>
 * <ol>
 *   <li>{@link #see} - on every look, reads the same screen text the bot reads. Keeps the
 *       roster (how many retailers, which distributor) and puts any label she has learned a
 *       new name for back to the name the bot knows.</li>
 *   <li>{@link #expectedCount} - tells the bot how many retailers the app itself says there
 *       are, in place of a number fixed in the code.</li>
 *   <li>{@link #afterStop} - when the bot has stopped, studies the screen. A renamed label
 *       she can work out is written in her notebook and the run goes on; otherwise she
 *       takes a picture, writes down what she found and asks a person.</li>
 *   <li>{@link #afterRetailerError} - the same study when one retailer had to be given up,
 *       so that the next retailer does not fail the same way.</li>
 * </ol>
 *
 * <p>She never presses anything in the Sudha app. Every entry point swallows its own
 * failures: if Aakriti breaks, the bot carries on exactly as it did before she existed.</p>
 *
 * <p>What she knows lives in {@code files/aakriti/} and a preference file of her own. The
 * Reset button clears the day's ledger and nothing of hers.</p>
 */
final class Aakriti {

    private static final String TAG = "SudhaAutoBot";
    private static final String FOLDER = "aakriti";
    private static final String MEMORY = "aakriti_memory";
    /** The run is started again by itself at most this often in one day. */
    private static final int MAX_RESUMES_A_DAY = 3;
    private static final int KEEP_REPORTS = 6;
    private static final int ERRORS_IN_A_ROW_TO_REPORT = 3;

    private static volatile Aakriti instance;
    /** While set, the stop being shown is one Aakriti is already recovering from. */
    private static volatile long recoveringUntil;
    /**
     * Run just before a picture of the screen is taken. The floating panel sets it, to keep
     * its full-screen scene out of the picture; Aakriti herself knows nothing about scenes.
     */
    static volatile Runnable beforePicture;

    private final Context app;
    private final File learnedFile;
    private final File rosterFile;
    private final Roster roster;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private volatile String lastState = "IDLE";
    private String today = "";
    private long todayReadAt;
    private long rosterSavedAt;
    private String resumeDay = "";
    private int resumesToday;
    private int retailerErrorsInARow;

    static Aakriti with(Context context) {
        Aakriti current = instance;
        if (current != null) return current;
        synchronized (Aakriti.class) {
            if (instance == null) instance = new Aakriti(context.getApplicationContext());
            return instance;
        }
    }

    private Aakriti(Context context) {
        app = context;
        File folder = new File(context.getFilesDir(), FOLDER);
        learnedFile = new File(folder, "learned.txt");
        rosterFile = new File(folder, "roster.txt");
        LearnedUi.load(learnedFile);
        roster = Roster.decode(LearnedUi.read(rosterFile));
        noteSudhaVersion();
    }

    static File reportsFolder(Context context) {
        return new File(new File(context.getFilesDir(), FOLDER), "reports");
    }

    /** True for a few seconds after a stop that Aakriti has fixed and is restarting from. */
    static boolean recovering() {
        return SystemClock.uptimeMillis() < recoveringUntil;
    }

    // ================================================================ the bot

    /**
     * Called with the text of every screen the bot looks at.
     *
     * @param textLower every label on the screen, lower-cased
     * @param state     the bot's state as this look begins
     * @return the text the bot should judge: {@code textLower} itself, unless a learned name
     *         had to be put back
     */
    String see(String textLower, BotState state) {
        try {
            lastState = state == null ? "IDLE" : state.name();
            if (state == BotState.SUCCESS) retailerErrorsInARow = 0;
            String text = LearnedUi.forBot(textLower, lastState);
            roster.see(text, today());
            if (roster.takeDirty()) saveRosterSoon();
            return text;
        } catch (Throwable e) {
            Log.w(TAG, "Aakriti could not read the screen", e);
            return textLower;
        }
    }

    /**
     * How many retailers to expect: the app's own count when it has printed one, otherwise
     * {@code configured} (never fewer than have already been seen).
     */
    int expectedCount(int configured) {
        try {
            int value = roster.expected(configured);
            return value < 1 ? configured : value;
        } catch (Throwable e) {
            return configured;
        }
    }

    /**
     * The bot has stopped. Studies the screen it stopped on.
     *
     * @return true when a renamed label was learned that explains the stop and the run may
     *         be started again
     */
    boolean afterStop(AccessibilityService service, AccessibilityNodeInfo root, String reason) {
        String state = lastState;
        List<UiMolder.Label> labels = ScreenLabels.of(root);
        boolean lookup = UiMolder.isLookupStop(reason);
        // After "I do not recognise this screen" the state says little about which screen
        // it is; after any other stop, only the screen the bot was working on is studied.
        UiMolder.Finding finding = lookup ? UiMolder.studyAny(state, labels) : UiMolder.study(state, labels);
        List<String> learned = adopt(finding);

        memory().edit()
                .putString("stop_reason", reason == null ? "" : reason)
                .putString("stop_state", state)
                .putLong("stop_at", System.currentTimeMillis())
                .apply();

        if (lookup && !learned.isEmpty() && takeResume()) {
            recoveringUntil = SystemClock.uptimeMillis() + 6000;
            AakritiNotifier.learned(app, learned, true);
            Log.i(TAG, "Aakriti learned " + learned + "; starting the run again");
            return true;
        }
        if (!learned.isEmpty()) AakritiNotifier.learned(app, learned, false);
        report(service, state, reason, finding, labels,
                app.getString(lookup ? R.string.notify_stuck_changed : R.string.notify_stuck_stopped));
        return false;
    }

    /** One retailer was given up; the run goes on. Learns what can be learned for the next. */
    void afterRetailerError(AccessibilityService service, AccessibilityNodeInfo root, String reason) {
        String state = lastState;
        List<UiMolder.Label> labels = ScreenLabels.of(root);
        UiMolder.Finding finding = UiMolder.study(state, labels);
        List<String> learned = adopt(finding);
        if (!learned.isEmpty()) AakritiNotifier.learned(app, learned, false);
        if (++retailerErrorsInARow == ERRORS_IN_A_ROW_TO_REPORT) {
            // No picture here: the run is still going and the bot takes pictures of its
            // own (to find the "+" button); Android allows only so many in a row.
            report(null, state, ERRORS_IN_A_ROW_TO_REPORT
                            + " retailers in a row could not be handled. The last one: " + reason,
                    finding, labels, app.getString(R.string.notify_stuck_row));
        }
    }

    // ============================================================ the notebook

    /** Writes down what a study found. Returns a line for each name that is live from now. */
    private List<String> adopt(UiMolder.Finding finding) {
        List<String> liveNow = new ArrayList<>();
        boolean changed = false;
        String version = sudhaVersion();
        for (UiMolder.Line line : finding.lines) {
            if (line.status != UiMolder.Status.LEARNED && line.status != UiMolder.Status.ASK) continue;
            boolean live = line.status == UiMolder.Status.LEARNED;
            if (LearnedUi.knows(line.alias())) continue;
            boolean usable = LearnedUi.add(new LearnedUi.Entry(line.slot.key, line.alias(), line.shown,
                    today(), version, live));
            if (!LearnedUi.knows(line.alias())) continue;
            changed = true;
            if (usable) {
                liveNow.add("\"" + line.slot.primary() + "\" → \"" + line.shown + "\"");
            } else {
                AakritiNotifier.ask(app, line.slot.says, line.shown);
            }
        }
        if (changed && !LearnedUi.save(learnedFile)) Log.w(TAG, "Aakriti could not save her notebook");
        return liveNow;
    }

    /** A person allowed a proposed name. */
    boolean allow(String alias) {
        boolean changed = LearnedUi.allow(alias);
        if (changed) LearnedUi.save(learnedFile);
        return changed;
    }

    boolean forget(String alias) {
        boolean changed = LearnedUi.forget(alias);
        if (changed) LearnedUi.save(learnedFile);
        return changed;
    }

    /** Forgets every learned name. Only ever called from its own, separate button. */
    void forgetEverything() {
        LearnedUi.clear();
        LearnedUi.save(learnedFile);
    }

    Roster roster() {
        return roster;
    }

    private boolean takeResume() {
        String day = today();
        if (!day.equals(resumeDay)) {
            resumeDay = day;
            resumesToday = 0;
        }
        if (resumesToday >= MAX_RESUMES_A_DAY) return false;
        resumesToday++;
        return true;
    }

    // ================================================================ memory

    private SharedPreferences memory() {
        return app.getSharedPreferences(MEMORY, Context.MODE_PRIVATE);
    }

    String lastStopReason() {
        return memory().getString("stop_reason", "");
    }

    String lastStopState() {
        return memory().getString("stop_state", "");
    }

    long lastStopAt() {
        return memory().getLong("stop_at", 0);
    }

    /** Version of the Sudha app installed now, e.g. "3.2 (41)", or "". */
    String sudhaVersion() {
        try {
            String target = app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                    .getString(MainActivity.KEY_TARGET_PACKAGE, "").trim();
            if (target.isEmpty()) return "";
            PackageInfo info = app.getPackageManager().getPackageInfo(target, 0);
            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
            return (info.versionName == null ? "?" : info.versionName) + " (" + code + ")";
        } catch (Exception e) {
            return "";
        }
    }

    /** Notices when the Sudha app has been updated since Aakriti last looked. */
    private void noteSudhaVersion() {
        String now = sudhaVersion();
        if (now.isEmpty()) return;
        String known = memory().getString("sudha_version", "");
        if (known.equals(now)) return;
        SharedPreferences.Editor editor = memory().edit().putString("sudha_version", now);
        if (!known.isEmpty()) {
            editor.putString("sudha_version_before", known)
                    .putLong("sudha_updated_at", System.currentTimeMillis());
        }
        editor.apply();
    }

    /** "3.1 (40) → 3.2 (41)" when an update of the Sudha app has been noticed, else "". */
    String sudhaUpdateNote() {
        noteSudhaVersion();
        String before = memory().getString("sudha_version_before", "");
        String now = memory().getString("sudha_version", "");
        return before.isEmpty() || before.equals(now) ? "" : before + " → " + now;
    }

    private String today() {
        long now = System.currentTimeMillis();
        if (today.isEmpty() || now - todayReadAt > 20_000) {
            today = dayFormat.format(new Date(now));
            todayReadAt = now;
        }
        return today;
    }

    private final Runnable saveRoster = this::saveRosterNow;

    private void saveRosterNow() {
        rosterSavedAt = SystemClock.uptimeMillis();
        final String content = roster.encode();
        final File target = rosterFile;
        // Off the main thread: the bot's looks must never wait for the disk.
        new Thread(() -> LearnedUi.write(target, content), "aakriti-roster").start();
    }

    private void saveRosterSoon() {
        main.removeCallbacks(saveRoster);
        long since = SystemClock.uptimeMillis() - rosterSavedAt;
        main.postDelayed(saveRoster, since > 15_000 ? 1_500 : 15_000 - since);
    }

    // =============================================================== reports

    /** Newest report text file, or null. */
    static File latestReport(Context context) {
        File[] files = reportsFolder(context).listFiles((dir, name) -> name.endsWith(".txt"));
        if (files == null || files.length == 0) return null;
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        return files[0];
    }

    /** The picture taken with a report, or null when there is none. */
    static File pictureOf(File report) {
        if (report == null) return null;
        File picture = new File(report.getPath().replaceAll("\\.txt$", ".jpg"));
        return picture.isFile() && picture.length() > 0 ? picture : null;
    }

    private void report(AccessibilityService service, String state, String reason,
                        UiMolder.Finding finding, List<UiMolder.Label> labels, String headline) {
        try {
            File folder = reportsFolder(app);
            if (!folder.exists() && !folder.mkdirs()) return;
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File text = new File(folder, "report-" + stamp + ".txt");
            File picture = new File(folder, "report-" + stamp + ".jpg");

            StringBuilder body = new StringBuilder();
            body.append("SUTARA'S REPORT\n");
            body.append(new SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale.US).format(new Date())).append('\n');
            body.append("\nWHAT HAPPENED\n");
            body.append("The bot was in state ").append(state).append(" and stopped:\n  ")
                    .append(reason).append('\n');
            body.append("\nTHE SUDHA APP\n");
            String version = sudhaVersion();
            body.append("Installed version: ").append(version.isEmpty() ? "unknown" : version).append('\n');
            String update = sudhaUpdateNote();
            if (!update.isEmpty()) body.append("It was updated since I last worked with it: ").append(update).append('\n');
            body.append("Roster: ").append(roster.describe()).append('\n');
            body.append("\nWHAT I LOOKED FOR\n").append(finding.describe());
            if (!LearnedUi.entries().isEmpty()) {
                body.append("\nMY NOTEBOOK (names I had already learned)\n");
                for (LearnedUi.Entry entry : LearnedUi.entries()) {
                    UiVocabulary.Slot slot = UiVocabulary.slot(entry.slot);
                    body.append("  \"").append(slot == null ? entry.slot : slot.primary())
                            .append("\" is \"").append(entry.shown).append("\"")
                            .append(entry.live ? "" : "  (waiting for your OK)").append('\n');
                }
            }
            body.append("\nEVERY LABEL ON THE SCREEN  ([press] = can be pressed)\n");
            if (labels.isEmpty()) body.append("  (the screen could not be read)\n");
            for (UiMolder.Label label : labels) {
                body.append(label.pressable ? "  [press] " : "          ")
                        .append(label.text.replace('\n', ' ')).append('\n');
            }
            body.append("\nPicture: ").append(service == null ? "not taken (the run was still going)"
                    : Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? picture.getName() : "not available before Android 11").append('\n');
            LearnedUi.write(text, body.toString());
            prune(folder);

            // Short enough to travel as the caption of the picture.
            StringBuilder brief = new StringBuilder();
            brief.append(reason).append("\n\n");
            for (UiMolder.Line line : finding.lines) {
                if (line.status == UiMolder.Status.PRESENT) continue;
                brief.append("• \"").append(line.slot.primary()).append("\": ");
                if (line.status == UiMolder.Status.MISSING) {
                    brief.append(line.shown.isEmpty() ? "missing, nothing like it on screen"
                            : "missing; closest \"" + line.shown + "\" (" + UiMolder.percent(line.score) + ")");
                } else {
                    brief.append("now \"").append(line.shown).append("\"")
                            .append(line.status == UiMolder.Status.ASK ? " — needs your OK" : " — learned");
                }
                brief.append('\n');
            }
            memory().edit().putString("report_headline", headline)
                    .putString("report_brief", brief.toString().trim())
                    .putString("report_state", state)
                    .putLong("report_at", System.currentTimeMillis()).apply();

            final String detail = brief.toString().trim();
            Runnable announce = () -> AakritiNotifier.stuck(app, headline, detail);
            if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Keep the full-screen scene out of the picture.
                Runnable hold = beforePicture;
                if (hold != null) hold.run();
                takePicture(service, picture, announce);
            } else {
                announce.run();
            }
        } catch (Throwable e) {
            Log.w(TAG, "Aakriti could not write her report", e);
        }
    }

    String reportHeadline() {
        return memory().getString("report_headline", "");
    }

    String reportBrief() {
        return memory().getString("report_brief", "");
    }

    long reportAt() {
        return memory().getLong("report_at", 0);
    }

    private static void prune(File folder) {
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".txt"));
        if (files == null || files.length <= KEEP_REPORTS) return;
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        for (int i = KEEP_REPORTS; i < files.length; i++) {
            File picture = new File(files[i].getPath().replaceAll("\\.txt$", ".jpg"));
            if (picture.exists() && !picture.delete()) Log.w(TAG, "Old picture kept: " + picture.getName());
            if (!files[i].delete()) Log.w(TAG, "Old report kept: " + files[i].getName());
        }
    }

    /** Takes a picture of the screen (Android 11+), then runs {@code then} either way. */
    private void takePicture(AccessibilityService service, final File target, final Runnable then) {
        final boolean[] finished = {false};
        final Runnable finish = () -> {
            if (finished[0]) return;
            finished[0] = true;
            then.run();
        };
        main.postDelayed(finish, 3500);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            finish.run();
            return;
        }
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, app.getMainExecutor(),
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult result) {
                            Bitmap soft = null;
                            try (HardwareBuffer buffer = result.getHardwareBuffer()) {
                                Bitmap hard = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                                if (hard != null) {
                                    soft = hard.copy(Bitmap.Config.ARGB_8888, false);
                                    hard.recycle();
                                }
                            } catch (Throwable e) {
                                Log.w(TAG, "Picture could not be read", e);
                            }
                            final Bitmap picture = soft;
                            if (picture == null) {
                                finish.run();
                                return;
                            }
                            new Thread(() -> {
                                try {
                                    Bitmap small = picture;
                                    if (picture.getWidth() > 900) {
                                        int height = Math.round(picture.getHeight() * 900f / picture.getWidth());
                                        small = Bitmap.createScaledBitmap(picture, 900, height, true);
                                    }
                                    try (FileOutputStream out = new FileOutputStream(target)) {
                                        small.compress(Bitmap.CompressFormat.JPEG, 82, out);
                                    }
                                } catch (Throwable e) {
                                    Log.w(TAG, "Picture could not be saved", e);
                                }
                                main.post(finish);
                            }, "aakriti-picture").start();
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            Log.w(TAG, "Aakriti's picture failed: " + errorCode);
                            finish.run();
                        }
                    });
        } catch (Throwable e) {
            Log.w(TAG, "Aakriti could not take a picture", e);
            finish.run();
        }
    }
}
