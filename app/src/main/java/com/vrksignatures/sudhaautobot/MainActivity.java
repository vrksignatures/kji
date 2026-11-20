package com.vrksignatures.sudhaautobot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The app's one screen. Top to bottom: today's progress with the Start button; the three
 * things the phone has to allow; Aakriti; and, folded away until wanted, her notebook, the
 * settings, the music, the end-of-run card, and the support tools.
 *
 * <p>Everything a person needs on an ordinary day is in the first card. What the buttons
 * <i>do</i> is unchanged - every action below the "sound, scenes, clips" line is the same
 * code as before the redesign.</p>
 *
 * <p>All UI is built in code on purpose - the app carries no layouts beyond the
 * overlay panel, so there is nothing to keep in sync with the Java.</p>
 */
public final class MainActivity extends Activity {

    /** Package of the first choice; used as the prefilled default. */
    static final String DEFAULT_TARGET_PACKAGE = "in.vasista.suprabhat";
    /** Package named in the brief, kept as a fallback suggestion. */
    static final String ALTERNATE_TARGET_PACKAGE = "com.vrksignatures.sudhadairy";

    static final String PREFS = "bot_config";
    static final String KEY_TARGET_PACKAGE = "target_package";
    static final String KEY_EXPECTED_COUNT = "expected_count";
    /** Fast mode (see Tempo): on unless switched off. Read by the bot when START is pressed. */
    static final String KEY_FAST_MODE = "fast_mode";
    static final String KEY_MANUAL_HOME_ENTRY = "manual_home_entry";
    static final String KEY_MANUAL_PAYMENT_PAUSE = "manual_payment_pause";

    private static final int PICK_VICTORY_PICTURE = 43;
    private static final int PICK_DRAMA_PICTURE = 44;
    private static final int PICK_HAPPY_SONG = 45;
    private static final int PICK_SAD_SONG = 46;
    private static final int ASK_NOTIFICATIONS = 51;

    private static final int OK_COLOR = MagicUi.GOOD;
    private static final int MISSING_COLOR = MagicUi.BAD;
    private static final int WAITING_COLOR = MagicUi.WARN;

    private EditText packageInput;
    private EditText expectedInput;
    private TextView packageStatus;
    private TextView overlayStatus;
    private TextView accessibilityStatus;
    private ImageView packageMark;
    private ImageView overlayMark;
    private ImageView accessibilityMark;
    private TextView setupSummary;
    private TextView ledgerStatus;

    private ProgressBar todayBar;
    private TextView todayCount;
    private TextView resumeLine;
    private TextView placedStat;
    private TextView skippedStat;
    private TextView checkStat;
    private TextView victoryMedia;
    private TextView dramaMedia;
    private LinearLayout happySongs;
    private LinearLayout sadSongs;
    private TextView songProblem;
    private TextView songNow;
    /** Aakriti's card and her notebook. */
    private AakritiDesk aakriti;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(MagicUi.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(32));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        aakriti = new AakritiDesk(this);
        addHeader(root);
        addToday(root);
        addSetup(root);
        aakriti.addCard(root);
        Fold notebook = fold(root, getString(R.string.section_notebook));
        aakriti.fillNotebook(notebook.body, notebook.header, notebook::open);
        addOrderAndQuantityModes(fold(root, "Order, Quantity & Distributor Blueprint").body);
        addSettings(fold(root, getString(R.string.section_tuning)).body);
        addSongs(fold(root, getString(R.string.section_songs)).body);
        addCues(fold(root, getString(R.string.section_cues)).body);
        addAdvanced(fold(root, getString(R.string.section_backstage)).body);

        setContentView(scroll);
        // Silent UI: no tap on this screen makes the phone click.
        MagicUi.silence(scroll);
        setTitle(getString(R.string.app_name));

        if (savedInstanceState == null) {
            // The app has been opened: she waves. Nothing is said or played.
            aakriti.wave();
            askForNotifications();
            aakriti.open(getIntent() == null ? null : getIntent().getStringExtra(AakritiNotifier.EXTRA_OPEN));
        }
    }

    /** A tap on one of Aakriti's notifications while the screen is already open. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && aakriti != null) {
            aakriti.open(intent.getStringExtra(AakritiNotifier.EXTRA_OPEN));
        }
    }

    /** Android 13+ asks the person before an app may post notifications. Asked once. */
    private void askForNotifications() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        String permission = "android.permission.POST_NOTIFICATIONS";
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return;
        if (prefs().getBoolean("asked_notifications", false)) return;
        prefs().edit().putBoolean("asked_notifications", true).apply();
        Toast.makeText(this, R.string.notify_allow_hint, Toast.LENGTH_LONG).show();
        requestPermissions(new String[]{permission}, ASK_NOTIFICATIONS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshMedia();
        refreshSongs();
        if (aakriti != null) aakriti.refresh();
    }

    // ------------------------------------------------------------- the screen

    private void addHeader(LinearLayout root) {
        TextView name = MagicUi.text(this, getString(R.string.app_name), 26, MagicUi.TEXT);
        name.setTypeface(MagicUi.MEDIUM);
        name.setLetterSpacing(-0.01f);
        root.addView(name);
        root.addView(MagicUi.text(this, getString(R.string.header_sub), 14, MagicUi.MUTED),
                MagicUi.wrap(this, 2));
    }

    /** Today: how far the list has got, where the next run carries on, and the Start button. */
    private void addToday(LinearLayout root) {
        LinearLayout card = MagicUi.card(this);
        card.addView(label(getString(R.string.progress_title)));

        todayCount = MagicUi.text(this, "", 22, MagicUi.TEXT);
        todayCount.setTypeface(MagicUi.MEDIUM);
        card.addView(todayCount, MagicUi.wrap(this, 6));

        todayBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        todayBar.setMax(1000);
        todayBar.setProgressDrawable(getDrawable(R.drawable.panel_progress));
        card.addView(todayBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));
        ((LinearLayout.LayoutParams) todayBar.getLayoutParams()).topMargin = dp(12);

        LinearLayout stats = row();
        placedStat = stat();
        skippedStat = stat();
        checkStat = stat();
        stats.addView(placedStat, MagicUi.share(this, 1f, 0));
        stats.addView(skippedStat, MagicUi.share(this, 1f, 0));
        stats.addView(checkStat, MagicUi.share(this, 1f, 0));
        card.addView(stats, MagicUi.fill(this, 12));

        resumeLine = MagicUi.text(this, "", 13, MagicUi.MUTED);
        card.addView(resumeLine, MagicUi.fill(this, 12));
        ledgerStatus = MagicUi.text(this, "", 12, MagicUi.FAINT);
        ledgerStatus.setLineSpacing(0, 1.1f);
        card.addView(ledgerStatus, MagicUi.fill(this, 2));

        card.addView(MagicUi.primaryButton(this, getString(R.string.button_start), v -> startBot()),
                MagicUi.fill(this, 16));
        LinearLayout more = row();
        more.addView(MagicUi.tonalButton(this, getString(R.string.button_pause),
                v -> sendControl(SudhaAccessibilityService.ACTION_PAUSE)), MagicUi.share(this, 1f, 0));
        more.addView(MagicUi.tonalButton(this, getString(R.string.button_show_panel),
                v -> showPanel()), MagicUi.share(this, 1.5f, 10));
        card.addView(more, MagicUi.fill(this, 10));

        root.addView(card, MagicUi.fill(this, 22));
    }

    private TextView stat() {
        TextView view = MagicUi.text(this, "", 14, MagicUi.TEXT);
        view.setTypeface(MagicUi.MEDIUM);
        return view;
    }

    /** The three things the phone has to allow, each with its live state. */
    private void addSetup(LinearLayout root) {
        LinearLayout title = row();
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.addView(heading(getString(R.string.section_keys)), MagicUi.share(this, 1f, 0));
        setupSummary = MagicUi.text(this, "", 13, MagicUi.MUTED);
        title.addView(setupSummary);
        root.addView(title, MagicUi.fill(this, 26));

        LinearLayout card = MagicUi.card(this);
        card.setPadding(dp(18), dp(6), dp(12), dp(6));
        overlayStatus = statusLine();
        accessibilityStatus = statusLine();
        packageStatus = statusLine();
        overlayMark = MagicUi.icon(this, R.drawable.ic_alert_circle, MISSING_COLOR);
        accessibilityMark = MagicUi.icon(this, R.drawable.ic_alert_circle, MISSING_COLOR);
        packageMark = MagicUi.icon(this, R.drawable.ic_alert_circle, MISSING_COLOR);
        card.addView(key(overlayMark, getString(R.string.button_overlay), overlayStatus,
                v -> requestOverlayPermission()));
        card.addView(MagicUi.divider(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        card.addView(key(accessibilityMark, getString(R.string.button_accessibility), accessibilityStatus,
                v -> openAccessibilitySettings()));
        card.addView(MagicUi.divider(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        card.addView(key(packageMark, getString(R.string.button_install_target), packageStatus,
                v -> openTargetApp()));
        root.addView(card, MagicUi.fill(this, 10));
    }

    private View key(ImageView mark, String title, TextView status, View.OnClickListener action) {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(action);

        row.addView(mark, new LinearLayout.LayoutParams(dp(24), dp(24)));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = MagicUi.text(this, title, 15, MagicUi.TEXT);
        name.setTypeface(MagicUi.MEDIUM);
        words.addView(name);
        words.addView(status, MagicUi.wrap(this, 2));
        row.addView(words, MagicUi.share(this, 1f, 14));

        row.addView(MagicUi.icon(this, R.drawable.ic_chevron_right, MagicUi.FAINT),
                new LinearLayout.LayoutParams(dp(24), dp(24)));
        return row;
    }

    private void addSettings(LinearLayout card) {
        card.addView(body(getString(R.string.setup_intro)));

        card.addView(label(getString(R.string.label_package)), MagicUi.wrap(this, 14));
        packageInput = input(InputType.TYPE_CLASS_TEXT);
        packageInput.setHint(R.string.hint_package);
        packageInput.setText(prefs().getString(KEY_TARGET_PACKAGE, DEFAULT_TARGET_PACKAGE));
        card.addView(packageInput, MagicUi.fill(this, 6));

        card.addView(label(getString(R.string.label_expected)), MagicUi.wrap(this, 14));
        expectedInput = input(InputType.TYPE_CLASS_NUMBER);
        expectedInput.setText(String.valueOf(prefs().getInt(
                KEY_EXPECTED_COUNT, RetailerPolicy.EXPECTED_RETAILER_COUNT)));
        card.addView(expectedInput, MagicUi.fill(this, 6));

        card.addView(MagicUi.tonalButton(this, getString(R.string.button_save), v -> {
            if (saveSettings(true)) refreshStatus();
        }), MagicUi.fill(this, 16));

        // Fast mode (see Tempo). Kept with the bot's own settings; read when START is pressed.
        Switch fast = plainSwitch(getString(R.string.toggle_fast));
        fast.setChecked(prefs().getBoolean(KEY_FAST_MODE, true));
        fast.setOnCheckedChangeListener((button, on) -> prefs().edit().putBoolean(KEY_FAST_MODE, on).apply());
        card.addView(fast, MagicUi.fill(this, 10));
        card.addView(note(getString(R.string.fast_note)), MagicUi.fill(this, 2));

        // How see-through the open floating panel is (see Glass). Folded, it is always
        // a bare ring. Read by the panel each time it is opened.
        card.addView(label(getString(R.string.label_glass)), MagicUi.wrap(this, 16));
        final TextView glassSays = note("");
        android.widget.SeekBar glass = new android.widget.SeekBar(this);
        glass.setMax(Glass.LEVELS - 1);
        glass.setProgress(FeedbackPrefs.glass(this));
        glass.setProgressTintList(ColorStateList.valueOf(MagicUi.BRAND));
        glass.setThumbTintList(ColorStateList.valueOf(MagicUi.BRAND));
        glassSays.setText(getString(R.string.glass_now, Glass.showsThrough(FeedbackPrefs.glass(this))));
        glass.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar bar, int level, boolean byHand) {
                if (byHand) FeedbackPrefs.setGlass(MainActivity.this, level);
                glassSays.setText(getString(R.string.glass_now, Glass.showsThrough(level)));
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar bar) {
            }
        });
        card.addView(glass, MagicUi.fill(this, 6));
        card.addView(glassSays, MagicUi.fill(this, 2));
        card.addView(note(getString(R.string.glass_note)), MagicUi.fill(this, 2));
    }

    /**
     * Songs from the internet while the bot works (see MusicEngine): the switch, what is
     * playing, a try-out for each mood, and the person's own songs if they want any.
     */
    private void addSongs(LinearLayout card) {
        card.addView(body(getString(R.string.songs_intro)));
        card.addView(toggle(getString(R.string.toggle_music), FeedbackPrefs.KEY_MUSIC),
                MagicUi.fill(this, 8));

        songNow = MagicUi.text(this, "", 13, MagicUi.TEXT);
        card.addView(songNow, MagicUi.fill(this, 10));
        songProblem = MagicUi.text(this, "", 12, MagicUi.WARN);
        songProblem.setVisibility(View.GONE);
        card.addView(songProblem, MagicUi.fill(this, 4));

        LinearLayout tries = row();
        tries.addView(MagicUi.quietButton(this, getString(R.string.songs_try_happy), MagicUi.BRAND,
                v -> trySong(MusicMood.Mood.HAPPY)), MagicUi.share(this, 1f, 0));
        tries.addView(MagicUi.quietButton(this, getString(R.string.songs_try_sad), MagicUi.BRAND,
                v -> trySong(MusicMood.Mood.SAD)), MagicUi.share(this, 1f, 8));
        tries.addView(MagicUi.quietButton(this, getString(R.string.songs_stop), MagicUi.MUTED,
                v -> {
                    MusicEngine.stop();
                    songNow.postDelayed(this::refreshSongs, 400);
                }), MagicUi.share(this, 0.7f, 8));
        card.addView(tries, MagicUi.fill(this, 10));

        // Auto-Dialogues: film dialogues in place of the songs. Off unless switched on;
        // the same switch is the microphone button on the floating panel.
        Switch lines = plainSwitch(getString(R.string.toggle_lines));
        lines.setChecked(FeedbackPrefs.linesOn(this));
        lines.setOnCheckedChangeListener((button, on) -> {
            FeedbackPrefs.set(this, FeedbackPrefs.KEY_DIALOGUES, on);
            MusicEngine.stop();
        });
        card.addView(lines, MagicUi.fill(this, 14));
        card.addView(note(getString(R.string.lines_note)), MagicUi.fill(this, 2));
        LinearLayout lineTries = row();
        lineTries.addView(MagicUi.quietButton(this, getString(R.string.lines_try_cheer), MagicUi.BRAND,
                v -> trySong(MusicMood.Mood.CHEER)), MagicUi.share(this, 1f, 0));
        lineTries.addView(MagicUi.quietButton(this, getString(R.string.lines_try_gloom), MagicUi.BRAND,
                v -> trySong(MusicMood.Mood.GLOOM)), MagicUi.share(this, 1f, 8));
        card.addView(lineTries, MagicUi.fill(this, 8));

        card.addView(note(getString(R.string.songs_own_intro)), MagicUi.fill(this, 18));
        happySongs = songBlock(card, R.string.songs_happy, MusicMood.Mood.HAPPY, PICK_HAPPY_SONG);
        sadSongs = songBlock(card, R.string.songs_sad, MusicMood.Mood.SAD, PICK_SAD_SONG);
        card.addView(note(getString(R.string.songs_note)), MagicUi.fill(this, 14));
    }

    private LinearLayout songBlock(LinearLayout card, int title, MusicMood.Mood mood, int request) {
        card.addView(label(getString(title)), MagicUi.wrap(this, 14));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        card.addView(list, MagicUi.fill(this, 4));
        LinearLayout buttons = row();
        buttons.addView(MagicUi.quietButton(this, getString(R.string.songs_add_link), MagicUi.TEXT,
                v -> askForSong(mood)), MagicUi.share(this, 1f, 0));
        buttons.addView(MagicUi.quietButton(this, getString(R.string.songs_add_file), MagicUi.TEXT,
                v -> pick(request, "audio/*")), MagicUi.share(this, 1f, 8));
        card.addView(buttons, MagicUi.fill(this, 8));
        return list;
    }

    /** The full-screen card at the end of a run: its switch, a look at it, the person's own picture. */
    private void addCues(LinearLayout card) {
        card.addView(body(getString(R.string.sound_intro)));
        card.addView(toggle(getString(R.string.toggle_scenes),
                FeedbackPrefs.KEY_SCENES), MagicUi.fill(this, 8));

        card.addView(label(getString(R.string.preview_title)), MagicUi.wrap(this, 18));
        LinearLayout previews = row();
        previews.addView(MagicUi.quietButton(this, getString(R.string.preview_victory),
                MagicUi.TEXT, v -> previewVictory()), MagicUi.share(this, 1f, 0));
        previews.addView(MagicUi.quietButton(this, getString(R.string.preview_drama),
                MagicUi.TEXT, v -> previewDrama()), MagicUi.share(this, 1f, 8));
        card.addView(previews, MagicUi.fill(this, 8));

        victoryMedia = statusLine();
        dramaMedia = statusLine();
        card.addView(ownClips(getString(R.string.own_victory), victoryMedia,
                FeedbackCues.Cue.RUN_COMPLETE, PICK_VICTORY_PICTURE), MagicUi.fill(this, 20));
        card.addView(ownClips(getString(R.string.own_drama), dramaMedia,
                FeedbackCues.Cue.RUN_STOPPED, PICK_DRAMA_PICTURE), MagicUi.fill(this, 18));
        card.addView(note(getString(R.string.own_note)), MagicUi.fill(this, 12));
    }

    private View ownClips(String title, TextView status, FeedbackCues.Cue cue, int pictureRequest) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.addView(label(title));
        block.addView(status, MagicUi.wrap(this, 4));
        LinearLayout buttons = row();
        buttons.addView(MagicUi.quietButton(this, getString(R.string.own_picture), MagicUi.TEXT,
                v -> pick(pictureRequest, "image/*")), MagicUi.share(this, 1f, 0));
        buttons.addView(MagicUi.quietButton(this, getString(R.string.own_reset), MagicUi.MUTED,
                v -> resetOwn(cue)), MagicUi.share(this, 1f, 8));
        block.addView(buttons, MagicUi.fill(this, 8));
        return block;
    }

    private void addAdvanced(LinearLayout card) {
        LinearLayout first = row();
        first.addView(MagicUi.quietButton(this, getString(R.string.button_capture), MagicUi.TEXT,
                v -> captureNow()), MagicUi.share(this, 1f, 0));
        first.addView(MagicUi.quietButton(this, getString(R.string.button_share_capture),
                MagicUi.TEXT, v -> shareCapture()), MagicUi.share(this, 1f, 8));
        card.addView(first);
        LinearLayout second = row();
        second.addView(MagicUi.quietButton(this, getString(R.string.button_clear_captures),
                MagicUi.TEXT, v -> clearCaptures()), MagicUi.share(this, 1f, 0));
        second.addView(MagicUi.quietButton(this, getString(R.string.button_refresh),
                MagicUi.TEXT, v -> {
                    refreshStatus();
                    refreshMedia();
                }), MagicUi.share(this, 1f, 8));
        card.addView(second, MagicUi.fill(this, 8));
        card.addView(MagicUi.quietButton(this, getString(R.string.button_skipped), MagicUi.TEXT,
                v -> showSkipped()), MagicUi.fill(this, 8));
        card.addView(MagicUi.quietButton(this, getString(R.string.button_reset), MISSING_COLOR,
                v -> confirmReset()), MagicUi.fill(this, 8));
        card.addView(note(getString(R.string.legend)), MagicUi.fill(this, 12));
    }

    /** Section 4, 6, 8, 10 & 11: Manual Home/Payment toggles, Customer Demand, Employee's Chance, Tier 1 Reset. */
    private void addOrderAndQuantityModes(LinearLayout card) {
        Switch manualHome = plainSwitch("Manual Home-Screen Entry (don't auto-click Customers)");
        manualHome.setChecked(prefs().getBoolean(KEY_MANUAL_HOME_ENTRY, true));
        manualHome.setOnCheckedChangeListener((b, on) -> prefs().edit().putBoolean(KEY_MANUAL_HOME_ENTRY, on).apply());
        card.addView(manualHome, MagicUi.fill(this, 4));

        Switch manualPay = plainSwitch("Manual Pause at Payment (OFF = Auto-Pay CASH)");
        manualPay.setChecked(prefs().getBoolean(KEY_MANUAL_PAYMENT_PAUSE, false));
        manualPay.setOnCheckedChangeListener((b, on) -> prefs().edit().putBoolean(KEY_MANUAL_PAYMENT_PAUSE, on).apply());
        card.addView(manualPay, MagicUi.fill(this, 6));

        // Mode A: Customer Demand Overrides
        card.addView(label("CUSTOMER DEMAND OVERRIDES"), MagicUi.wrap(this, 14));
        final TextView demandSummary = note("");
        Runnable refreshDemand = () -> {
            java.util.List<QuantityModes.DemandRule> rules = QuantityModes.loadDemandRules(this);
            if (rules.isEmpty()) {
                demandSummary.setText("No Customer Demand rules added.");
            } else {
                StringBuilder sb = new StringBuilder();
                for (QuantityModes.DemandRule r : rules) sb.append("• ").append(r.summary()).append('\n');
                demandSummary.setText(sb.toString().trim());
            }
        };
        refreshDemand.run();
        card.addView(demandSummary, MagicUi.fill(this, 4));
        LinearLayout demandBtns = row();
        demandBtns.addView(MagicUi.quietButton(this, "+ Add Demand Rule", MagicUi.BRAND,
                v -> showAddDemandDialog(refreshDemand)), MagicUi.share(this, 1.3f, 0));
        demandBtns.addView(MagicUi.quietButton(this, "Clear Rules", MISSING_COLOR, v -> {
            QuantityModes.saveDemandRules(this, java.util.Collections.emptyList());
            refreshDemand.run();
            Toast.makeText(this, "Customer Demand rules cleared", Toast.LENGTH_SHORT).show();
        }), MagicUi.share(this, 1f, 8));
        card.addView(demandBtns, MagicUi.fill(this, 6));

        // Mode B: Employee's Chance (Anti-Bot Randomized Scatter)
        card.addView(label("EMPLOYEE'S CHANCE (ANTI-BOT VOLUME SCATTER)"), MagicUi.wrap(this, 16));
        final Switch ecSwitch = plainSwitch("Enable Employee's Chance");
        ecSwitch.setChecked(QuantityModes.isEmployeeChanceEnabled(this));
        card.addView(ecSwitch, MagicUi.fill(this, 4));
        final EditText extraMlInput = input(InputType.TYPE_CLASS_NUMBER);
        extraMlInput.setHint("Total extra volume ml (e.g. 10000)");
        int savedMl = QuantityModes.employeeChanceExtraMl(this);
        if (savedMl > 0) extraMlInput.setText(String.valueOf(savedMl));
        card.addView(extraMlInput, MagicUi.fill(this, 6));
        final EditText targetRetInput = input(InputType.TYPE_CLASS_NUMBER);
        targetRetInput.setHint("Target number of retailers (e.g. 75)");
        int savedTarget = QuantityModes.employeeChanceTargetRetailers(this);
        if (savedTarget > 0) targetRetInput.setText(String.valueOf(savedTarget));
        card.addView(targetRetInput, MagicUi.fill(this, 6));
        card.addView(MagicUi.tonalButton(this, "Save Employee's Chance", v -> {
            int ml = 0, cnt = 0;
            try { ml = Integer.parseInt(extraMlInput.getText().toString().trim()); } catch (Exception ignored) {}
            try { cnt = Integer.parseInt(targetRetInput.getText().toString().trim()); } catch (Exception ignored) {}
            QuantityModes.saveEmployeeChance(this, ecSwitch.isChecked(), ml, cnt);
            Toast.makeText(this, "Employee's Chance saved (" + (ecSwitch.isChecked() ? ml + "ml / " + cnt + " shops" : "OFF") + ")",
                    Toast.LENGTH_SHORT).show();
        }), MagicUi.fill(this, 8));

        // Tier 1 Permanent Distributor Blueprint (Strictly separated from Tier 2 Daily Reset)
        card.addView(label("TIER 1 PERMANENT DISTRIBUTOR BLUEPRINT"), MagicUi.wrap(this, 18));
        final TextView tier1Info = note(DistributorBlueprint.statusSummary(this));
        card.addView(tier1Info, MagicUi.fill(this, 4));
        card.addView(MagicUi.quietButton(this, "Reset Distributor History", MISSING_COLOR, v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Reset Distributor History?")
                    .setMessage("Clears ONLY the permanently learned Tier 1 Distributor Blueprint. Today's retailer ledger is NOT touched.")
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton("Reset Blueprint", (d, w) -> {
                        DistributorBlueprint.resetDistributorHistory(this);
                        tier1Info.setText(DistributorBlueprint.statusSummary(this));
                        Toast.makeText(this, "Permanent Distributor History cleared", Toast.LENGTH_SHORT).show();
                    })
                    .show();
        }), MagicUi.fill(this, 6));
    }

    private void showAddDemandDialog(Runnable onSaved) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        box.setPadding(p, p, p, p);

        final EditText cidInput = input(InputType.TYPE_CLASS_TEXT);
        cidInput.setHint("Customer ID (e.g. SMR2246)");
        box.addView(cidInput);

        final Switch exactSwitch = plainSwitch("Exact Pack Override (OFF = Volume Delta ml)");
        exactSwitch.setChecked(false);
        box.addView(exactSwitch);

        final EditText targetInput = input(InputType.TYPE_CLASS_TEXT);
        targetInput.setHint("Family (COW/SM/TM/FCM) or SKU (SM 500 ML)");
        box.addView(targetInput);

        final EditText valInput = input(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        valInput.setHint("Value: e.g. 1000 or -500 (ml) OR 6 (packs)");
        box.addView(valInput);

        new AlertDialog.Builder(this)
                .setTitle("Add Customer Demand Rule")
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("Add", (d, w) -> {
                    String cid = cidInput.getText().toString().trim().toUpperCase(Locale.ROOT);
                    String target = targetInput.getText().toString().trim().toUpperCase(Locale.ROOT);
                    int val;
                    try {
                        val = Integer.parseInt(valInput.getText().toString().trim());
                    } catch (Exception e) {
                        Toast.makeText(this, "Enter a valid number", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (cid.isEmpty() || target.isEmpty()) {
                        Toast.makeText(this, "Enter Customer ID and SKU/Family", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    java.util.List<QuantityModes.DemandRule> rules = QuantityModes.loadDemandRules(this);
                    rules.add(new QuantityModes.DemandRule(cid, exactSwitch.isChecked(), target, val));
                    QuantityModes.saveDemandRules(this, rules);
                    onSaved.run();
                })
                .show();
    }

    // ------------------------------------------------------------------ UI bits

    /** A section that stays folded until its title is tapped. */
    private static final class Fold {
        View header;
        LinearLayout body;
        ImageView chevron;

        void open() {
            body.setVisibility(View.VISIBLE);
            chevron.setRotation(180f);
        }

        void toggle() {
            if (body.getVisibility() == View.VISIBLE) {
                body.setVisibility(View.GONE);
                chevron.setRotation(0f);
            } else {
                open();
            }
        }
    }

    private Fold fold(LinearLayout root, String title) {
        Fold fold = new Fold();
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(52));
        header.setClickable(true);
        header.setFocusable(true);
        header.addView(heading(title), MagicUi.share(this, 1f, 0));
        fold.chevron = MagicUi.icon(this, R.drawable.ic_chevron_down, MagicUi.MUTED);
        header.addView(fold.chevron, new LinearLayout.LayoutParams(dp(24), dp(24)));
        header.setOnClickListener(v -> fold.toggle());
        fold.header = header;
        root.addView(header, MagicUi.fill(this, 14));
        fold.body = MagicUi.card(this);
        fold.body.setVisibility(View.GONE);
        root.addView(fold.body, MagicUi.fill());
        return fold;
    }

    /** A section title. */
    private TextView heading(String words) {
        TextView name = MagicUi.text(this, words, 17, MagicUi.TEXT);
        name.setTypeface(MagicUi.MEDIUM);
        return name;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        return row;
    }

    private TextView body(String text) {
        TextView view = MagicUi.text(this, text, 14, MagicUi.MUTED);
        view.setLineSpacing(0, 1.25f);
        return view;
    }

    /** Small print. */
    private TextView note(String text) {
        TextView view = MagicUi.text(this, text, 12, MagicUi.FAINT);
        view.setLineSpacing(0, 1.25f);
        return view;
    }

    private TextView label(String text) {
        TextView view = MagicUi.text(this, text, 12, MagicUi.MUTED);
        view.setTypeface(MagicUi.MEDIUM);
        view.setLetterSpacing(0.04f);
        return view;
    }

    private TextView statusLine() {
        return MagicUi.text(this, "", 13, MagicUi.MUTED);
    }

    private EditText input(int type) {
        EditText view = new EditText(this);
        view.setSingleLine(true);
        view.setInputType(type);
        view.setTextSize(15);
        view.setTextColor(MagicUi.TEXT);
        view.setHintTextColor(MagicUi.FAINT);
        view.setBackground(MagicUi.rounded(this, MagicUi.BG, MagicUi.LINE, MagicUi.RADIUS_CONTROL));
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        return view;
    }

    private Switch toggle(String words, String key) {
        Switch view = plainSwitch(words);
        view.setChecked(FeedbackPrefs.isOn(this, key));
        view.setOnCheckedChangeListener((button, on) -> FeedbackPrefs.set(this, key, on));
        return view;
    }

    /** A switch in the app's colours, not yet tied to any setting. */
    private Switch plainSwitch(String words) {
        Switch view = new Switch(this);
        view.setText(words);
        view.setTextSize(14);
        view.setTextColor(MagicUi.TEXT);
        view.setMinHeight(dp(48));
        int[][] states = {{android.R.attr.state_checked}, {}};
        view.setThumbTintList(new ColorStateList(states, new int[]{MagicUi.BRAND, 0xFFFFFFFF}));
        view.setTrackTintList(new ColorStateList(states,
                new int[]{MagicUi.withAlpha(MagicUi.BRAND, 0x66), 0xFFC9CDDA}));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ songs

    private void refreshSongs() {
        if (happySongs == null) return;
        showSongs(happySongs, MusicMood.Mood.HAPPY);
        showSongs(sadSongs, MusicMood.Mood.SAD);
        String title = MusicEngine.nowPlaying();
        String finding = MusicEngine.finding();
        if (!title.isEmpty()) {
            songNow.setText(getString(SongBook.locked(this) ? R.string.song_locked : R.string.song_now, title));
        } else if (!finding.isEmpty()) {
            songNow.setText(finding);
        } else if (!FeedbackPrefs.isOn(this, FeedbackPrefs.KEY_MUSIC)) {
            songNow.setText(R.string.song_off);
        } else {
            songNow.setText(R.string.song_waiting);
        }
        String problem = MusicEngine.lastProblem();
        songProblem.setText(getString(R.string.songs_problem, problem));
        songProblem.setVisibility(problem.isEmpty() ? View.GONE : View.VISIBLE);
        MagicUi.silence(happySongs);
        MagicUi.silence(sadSongs);
    }

    /** Finds a song of this mood on YouTube now and plays half a minute of it. */
    private void trySong(MusicMood.Mood mood) {
        if (!FeedbackPrefs.isOn(this, FeedbackPrefs.KEY_MUSIC)) {
            Toast.makeText(this, R.string.song_off, Toast.LENGTH_LONG).show();
            return;
        }
        MusicEngine.sample(this, mood);
        songNow.setText(R.string.songs_finding);
        // The search and the look-up take a few seconds; the line follows them.
        for (int after : new int[]{1500, 4000, 8000, 14000, 22000, 32000}) {
            songNow.postDelayed(this::refreshSongs, after);
        }
    }

    private void showSongs(LinearLayout list, MusicMood.Mood mood) {
        list.removeAllViews();
        java.util.List<SongBook.Song> songs = SongBook.list(this, mood);
        if (songs.isEmpty()) {
            list.addView(MagicUi.text(this, getString(R.string.songs_empty), 13, MagicUi.FAINT));
            return;
        }
        for (SongBook.Song song : songs) {
            LinearLayout line = row();
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setMinimumHeight(dp(44));
            TextView title = MagicUi.text(this, song.title, 14, MagicUi.TEXT);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setClickable(true);
            title.setOnClickListener(v -> MusicEngine.preview(this, song));
            line.addView(title, MagicUi.share(this, 1f, 0));
            ImageView remove = MagicUi.icon(this, R.drawable.ic_close, MagicUi.MUTED);
            remove.setPadding(dp(12), dp(12), dp(12), dp(12));
            remove.setContentDescription(getString(R.string.own_reset));
            remove.setOnClickListener(v -> {
                SongBook.remove(this, mood, song.address);
                refreshSongs();
            });
            line.addView(remove, new LinearLayout.LayoutParams(dp(44), dp(44)));
            list.addView(line);
        }
    }

    /** Asks for a link to a song: a YouTube link, or a direct link to a sound file. */
    private void askForSong(MusicMood.Mood mood) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.link_hint);
        FrameLayout holder = new FrameLayout(this);
        holder.setPadding(dp(20), dp(8), dp(20), 0);
        holder.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(R.string.songs_link_title)
                .setMessage(R.string.songs_link_message)
                .setView(holder)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.add, (dialog, which) -> {
                    String link = input.getText().toString().trim();
                    String problem = MusicMood.problem(link);
                    if (problem != null) {
                        new AlertDialog.Builder(this).setMessage(problem)
                                .setPositiveButton(R.string.close, null).show();
                        return;
                    }
                    String page = MusicMood.youTubePage(link);
                    if (page == null) {
                        addSong(mood, new SongBook.Song(MusicMood.title(link), link));
                        return;
                    }
                    // A YouTube link says nothing about the song: ask YouTube for its title.
                    Toast.makeText(this, R.string.songs_finding, Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        String title;
                        try {
                            title = YouTubeAudio.title(page);
                        } catch (Throwable e) {
                            title = "YouTube song " + MusicMood.youTubeId(page);
                        }
                        final String name = title;
                        runOnUiThread(() -> {
                            if (!isFinishing()) addSong(mood, new SongBook.Song(name, page));
                        });
                    }, "song-title").start();
                })
                .show();
    }

    private void addSong(MusicMood.Mood mood, SongBook.Song song) {
        if (!SongBook.add(this, mood, song)) {
            Toast.makeText(this, R.string.songs_full, Toast.LENGTH_LONG).show();
            return;
        }
        refreshSongs();
        Toast.makeText(this, R.string.songs_added, Toast.LENGTH_SHORT).show();
        MusicEngine.preview(this, song);
    }

    /** A song file picked on the phone: the app keeps the right to read it, not a copy. */
    private void songPicked(MusicMood.Mood mood, Intent data) {
        Uri picked = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // Some pickers give a one-time address; the song then plays until the phone restarts.
        }
        String name = null;
        try (android.database.Cursor cursor = getContentResolver().query(picked,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (Exception ignored) {
        }
        addSong(mood, new SongBook.Song(name == null ? "Song" : MusicMood.title(name), picked.toString()));
    }

    // ------------------------------------------------- end-of-run card, pictures

    private void previewVictory() {
        DailyLedger ledger = new DailyLedger(this);
        String[] words = FeedbackCues.victoryWords((int) (System.currentTimeMillis() / 1000));
        CinematicOverlay.preview(this, SceneView.Kind.VICTORY, words[0], words[1],
                FeedbackCues.tally(ledger.countBucket("SUCCESS"), ledger.countBucket("SKIPPED"),
                        ledger.countBucket("ERROR")) + "\n" + getString(R.string.preview_only));
    }

    private void previewDrama() {
        String[] words = FeedbackCues.dramaWords((int) (System.currentTimeMillis() / 1000));
        CinematicOverlay.preview(this, SceneView.Kind.DRAMA, words[0], words[1],
                getString(R.string.preview_only_drama));
    }

    private void pick(int request, String mime) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, request);
        } catch (Exception e) {
            try {
                startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType(mime), request);
            } catch (Exception none) {
                Toast.makeText(this, R.string.pick_unavailable, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == PICK_HAPPY_SONG || request == PICK_SAD_SONG) {
            songPicked(request == PICK_HAPPY_SONG ? MusicMood.Mood.HAPPY : MusicMood.Mood.SAD, data);
            return;
        }
        if (request != PICK_VICTORY_PICTURE && request != PICK_DRAMA_PICTURE) return;
        final FeedbackCues.Cue cue = request == PICK_VICTORY_PICTURE
                ? FeedbackCues.Cue.RUN_COMPLETE : FeedbackCues.Cue.RUN_STOPPED;
        final Uri picked = data.getData();
        final File target = FeedbackPrefs.customPicture(this, cue);
        new Thread(() -> {
            String problem = CustomMedia.importFile(getApplicationContext(), picked, target,
                    CustomMedia.MAX_PICTURE_BYTES);
            runOnUiThread(() -> afterImport(problem));
        }, "media-import").start();
    }

    private void afterImport(String problem) {
        if (isFinishing()) return;
        if (problem != null) {
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show();
            return;
        }
        refreshMedia();
        Toast.makeText(this, R.string.own_picture_saved, Toast.LENGTH_SHORT).show();
    }

    private void resetOwn(FeedbackCues.Cue cue) {
        File picture = FeedbackPrefs.customPicture(this, cue);
        boolean cleared = !picture.exists() || picture.delete();
        refreshMedia();
        Toast.makeText(this, cleared ? R.string.own_reset_done : R.string.capture_clear_failed,
                Toast.LENGTH_SHORT).show();
    }

    private void refreshMedia() {
        if (victoryMedia == null) return;
        victoryMedia.setText(describeOwn(FeedbackCues.Cue.RUN_COMPLETE));
        dramaMedia.setText(describeOwn(FeedbackCues.Cue.RUN_STOPPED));
    }

    private String describeOwn(FeedbackCues.Cue cue) {
        File picture = FeedbackPrefs.customPicture(this, cue);
        return getString(R.string.own_status, FeedbackPrefs.has(picture)
                ? getString(R.string.own_yours, CustomMedia.megabytes(picture.length()))
                : getString(R.string.own_builtin_picture));
    }

    // ------------------------------------------------------------- preferences

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private String savedPackage() {
        return prefs().getString(KEY_TARGET_PACKAGE, "").trim();
    }

    private int savedExpectedCount() {
        int value = prefs().getInt(KEY_EXPECTED_COUNT, RetailerPolicy.EXPECTED_RETAILER_COUNT);
        return value < 1 || value > 2000 ? RetailerPolicy.EXPECTED_RETAILER_COUNT : value;
    }

    /** Persists the form; returns false (and highlights the field) when invalid. */
    private boolean saveSettings(boolean showToast) {
        String target = packageInput.getText().toString().trim();
        if (target.isEmpty()) {
            packageInput.setError(getString(R.string.error_package_empty));
            return false;
        }
        int expected;
        try {
            expected = Integer.parseInt(expectedInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            expected = RetailerPolicy.EXPECTED_RETAILER_COUNT;
        }
        if (expected < 1 || expected > 2000) expected = RetailerPolicy.EXPECTED_RETAILER_COUNT;

        prefs().edit()
                .putString(KEY_TARGET_PACKAGE, target)
                .putInt(KEY_EXPECTED_COUNT, expected)
                .apply();

        if (showToast) {
            Toast.makeText(this, "Saved: " + target + " (" + expected + " retailers)",
                    Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    // ------------------------------------------------------------- permissions

    private void requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Floating panel permission already granted",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this,
                    "Enable SudhaAutoBot under Installed / Downloaded services",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Accessibility settings unavailable", Toast.LENGTH_LONG).show();
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String expected = new ComponentName(this, SudhaAccessibilityService.class)
                .flattenToString().toLowerCase(Locale.ROOT);
        String expectedShort = new ComponentName(this, SudhaAccessibilityService.class)
                .flattenToShortString().toLowerCase(Locale.ROOT);
        for (String entry : enabled.split(":")) {
            String value = entry.trim().toLowerCase(Locale.ROOT);
            if (value.equals(expected) || value.equals(expectedShort)) return true;
        }
        return false;
    }

    private boolean isTargetInstalled(String target) {
        try {
            getPackageManager().getPackageInfo(target, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // ----------------------------------------------------------------- actions

    private void openTargetApp() {
        if (!saveSettings(false)) return;
        Intent launch = getPackageManager().getLaunchIntentForPackage(savedPackage());
        if (launch == null) {
            Toast.makeText(this, getString(R.string.error_package_not_installed)
                    + " (" + savedPackage() + ")", Toast.LENGTH_LONG).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }

    /** Shows the overlay without starting a run (useful to reposition it). */
    private void showPanel() {
        if (!saveSettings(false)) return;
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
            return;
        }
        sendControl(SudhaAccessibilityService.ACTION_SHOW_PANEL);
        Toast.makeText(this, "Floating panel requested", Toast.LENGTH_SHORT).show();
    }

    private void startBot() {
        if (!saveSettings(false)) return;
        String target = savedPackage();

        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
            return;
        }
        if (!isAccessibilityServiceEnabled()) {
            openAccessibilitySettings();
            return;
        }
        if (!isTargetInstalled(target)) {
            new AlertDialog.Builder(this)
                    .setTitle("Package not found")
                    .setMessage("No app with package \"" + target + "\" is installed.\n\n"
                            + "Known candidates:\n• " + DEFAULT_TARGET_PACKAGE + "\n• "
                            + ALTERNATE_TARGET_PACKAGE
                            + "\n\nEnter the exact package name shown in "
                            + "Settings > Apps, then tap Save.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        sendControl(SudhaAccessibilityService.ACTION_START);
        Toast.makeText(this, "START sent — the run begins from the home screen",
                Toast.LENGTH_SHORT).show();
        moveTaskToBack(true);
    }

    private void sendControl(String action) {
        Intent intent = new Intent(action).setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.reset_title)
                .setMessage(R.string.reset_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.reset_confirm, (dialog, which) -> {
                    sendControl(SudhaAccessibilityService.ACTION_RESET);
                    // The service owns the file; give it a moment, then re-read status.
                    new Handler(Looper.getMainLooper()).postDelayed(this::refreshStatus, 600);
                })
                .show();
    }

    // --------------------------------------------------------------- inspector

    /**
     * Captures what the accessibility service can see right now.
     *
     * <p>Ten seconds is enough to tap this button, switch to the Sudha app and let the
     * screen settle; the service samples whatever is in the foreground at that moment.</p>
     */
    private void captureNow() {
        if (!isAccessibilityServiceEnabled()) {
            openAccessibilitySettings();
            return;
        }
        sendControl(SudhaAccessibilityService.ACTION_CAPTURE);
        Toast.makeText(this, getString(R.string.capture_hint), Toast.LENGTH_LONG).show();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            File[] files = ScreenCapture.list(this);
            if (files.length == 0) {
                Toast.makeText(this, R.string.capture_none, Toast.LENGTH_LONG).show();
                return;
            }
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                try (FileInputStream in = new FileInputStream(files[0])) {
                    byte[] chunk = new byte[4096];
                    int read;
                    while ((read = in.read(chunk)) > 0) buffer.write(chunk, 0, read);
                }
                showCaptureDialog(files[0].getName(),
                        new String(buffer.toByteArray(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                Toast.makeText(this, "Could not read " + files[0].getName(),
                        Toast.LENGTH_LONG).show();
            }
        }, 10000);
    }

    /** Scrollable, copyable view of the newest capture. */
    private void showCaptureDialog(String fileName, String content) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(10f);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        int pad = dp(12);
        view.setPadding(pad, pad, pad, pad);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);

        new AlertDialog.Builder(this)
                .setTitle(fileName)
                .setView(scroll)
                .setPositiveButton(R.string.capture_share, (dialog, which) -> shareCapture())
                .setNeutralButton(R.string.close, null)
                .show();
    }

    /** The file of retailers the bot gave up on (see SkipLog): read, share, clear. */
    private void showSkipped() {
        final java.util.List<String> lines = SkipLog.read(this, SkipLog.MAX_LINES);
        final StringBuilder all = new StringBuilder();
        // Newest first: the last run is what is usually wanted.
        for (int i = lines.size() - 1; i >= 0; i--) all.append(lines.get(i)).append('\n');

        TextView view = new TextView(this);
        view.setText(lines.isEmpty() ? getString(R.string.skipped_none) : all.toString().trim());
        view.setTextSize(11f);
        view.setTextIsSelectable(true);
        int pad = dp(14);
        view.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);

        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.skipped_title, lines.size()))
                .setView(scroll)
                .setNeutralButton(R.string.close, null);
        if (!lines.isEmpty()) {
            dialog.setPositiveButton(R.string.skipped_share, (d, which) -> {
                try {
                    startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.button_skipped))
                            .putExtra(Intent.EXTRA_TEXT, all.toString().trim()), getString(R.string.skipped_share)));
                } catch (Exception e) {
                    Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
                }
            });
            dialog.setNegativeButton(R.string.skipped_clear, (d, which) -> {
                SkipLog.clear(this);
                Toast.makeText(this, R.string.skipped_cleared, Toast.LENGTH_SHORT).show();
            });
        }
        dialog.show();
    }

    private void shareCapture() {
        sendControl(SudhaAccessibilityService.ACTION_SHARE_CAPTURE);
        Toast.makeText(this, R.string.capture_share_hint, Toast.LENGTH_LONG).show();
    }

    private void clearCaptures() {
        boolean cleared = ScreenCapture.clear(this);
        Toast.makeText(this, cleared ? R.string.capture_cleared : R.string.capture_clear_failed,
                Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ status

    private void refreshStatus() {
        boolean overlay = Settings.canDrawOverlays(this);
        overlayStatus.setText(overlay
                ? getString(R.string.status_overlay_ok)
                : getString(R.string.status_overlay_missing));
        mark(overlayMark, overlayStatus, overlay);

        String target = savedPackage();
        boolean installed = !target.isEmpty() && isTargetInstalled(target);
        if (target.isEmpty()) {
            packageStatus.setText(R.string.status_package_unset);
        } else if (installed) {
            packageStatus.setText(getString(R.string.status_package_ok, target));
        } else {
            packageStatus.setText(getString(R.string.status_package_missing, target));
        }
        mark(packageMark, packageStatus, installed);

        boolean accessibility = isAccessibilityServiceEnabled();
        accessibilityStatus.setText(accessibility
                ? getString(R.string.status_accessibility_ok)
                : getString(R.string.status_accessibility_missing));
        mark(accessibilityMark, accessibilityStatus, accessibility);

        int missing = (overlay ? 0 : 1) + (installed ? 0 : 1) + (accessibility ? 0 : 1);
        setupSummary.setText(missing == 0 ? getString(R.string.setup_done)
                : getString(R.string.setup_todo, missing));
        setupSummary.setTextColor(missing == 0 ? OK_COLOR : MISSING_COLOR);

        DailyLedger ledger = new DailyLedger(this);
        int placed = ledger.countBucket("SUCCESS");
        int skipped = ledger.countBucket("SKIPPED");
        int toCheck = ledger.countBucket("ERROR");
        int expected = Math.max(1, savedExpectedCount());
        try {
            expected = Math.max(1, Aakriti.with(this).expectedCount(savedExpectedCount()));
        } catch (Throwable ignored) {
        }
        int handled = ledger.processedCount();
        todayCount.setText(getString(R.string.today_of, handled, expected));
        todayBar.setProgress(Math.max(0, Math.min(1000, Math.round(handled * 1000f / expected))));
        placedStat.setText(getString(R.string.stat_placed, placed));
        placedStat.setTextColor(placed > 0 ? OK_COLOR : MagicUi.MUTED);
        skippedStat.setText(getString(R.string.stat_skipped, skipped));
        skippedStat.setTextColor(MagicUi.MUTED);
        checkStat.setText(getString(R.string.stat_check, toCheck));
        checkStat.setTextColor(toCheck > 0 ? MISSING_COLOR : MagicUi.MUTED);

        // Where the next START carries on: the box on the panel, else the day's own place.
        String typed = RunHistory.typed(this);
        String last = typed != null ? typed : ledger.lastHandledId();
        if (last == null && handled == 0) last = RunHistory.lastDone(this);
        resumeLine.setText(last == null ? getString(R.string.today_fresh)
                : getString(R.string.today_resume, last, typed != null ? "typed on the panel"
                        : StartPoint.age(RunHistory.lastDoneAt(this), System.currentTimeMillis())));

        StringBuilder builder = new StringBuilder();
        builder.append("Ledger ").append(ledger.sessionDate());
        if (ledger.hasPending()) {
            builder.append("\nIn flight: ").append(ledger.pendingId())
                    .append(" (").append(ledger.pendingPhase()).append(')');
        }
        ledgerStatus.setText(builder.toString());
        ledgerStatus.setTextColor(ledger.hasPending() ? WAITING_COLOR : MagicUi.FAINT);
    }

    private void mark(ImageView icon, TextView line, boolean ok) {
        icon.setImageResource(ok ? R.drawable.ic_check_circle : R.drawable.ic_alert_circle);
        icon.setColorFilter(ok ? OK_COLOR : MISSING_COLOR);
        line.setTextColor(ok ? MagicUi.MUTED : MISSING_COLOR);
    }
}
