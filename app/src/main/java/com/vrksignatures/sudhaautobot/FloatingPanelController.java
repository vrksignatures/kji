package com.vrksignatures.sudhaautobot;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The floating panel: the always-on-top control strip ("Oppo/Vivo safe UI").
 *
 * <p>Everything in this class is for the person watching - what the pod looks like, what
 * is said and shown at the milestones. The bot reads exactly one thing from it,
 * {@link #boundsOnScreen()}, so that it never presses its own window.</p>
 *
 * <p>Design notes for ColorOS / FuntouchOS class devices:</p>
 * <ul>
 *   <li>{@code TYPE_APPLICATION_OVERLAY} on API 26+, the deprecated
 *       {@code TYPE_PHONE} below that.</li>
 *   <li>{@code FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL} so the target app keeps
 *       keyboard focus while the panel is on screen.</li>
 *   <li>A small drag handle with a generous touch target; the panel is moved by
 *       updating {@link WindowManager.LayoutParams} rather than by re-inflating.</li>
 *   <li>Every text update is posted to the main looper, so callers may update the
 *       panel from a background thread.</li>
 * </ul>
 */
public final class FloatingPanelController {

    /** Callbacks wired to the three buttons. */
    public interface Actions {
        void onStart();

        void onPause();

        void onReset();

        void onHide();

        /** Read-only screen dump for verifying labels and the package name. */
        void onCapture();

        /** Silences the high-volume error alarm immediately. */
        default void onStopAlarm() {}
    }

    private final Context context;
    private final WindowManager windowManager;
    private final View root;
    private final WindowManager.LayoutParams params;
    private final LinearLayout body;
    private final TextView stateView;
    private final TextView currentView;
    private final TextView countsView;
    private final TextView progressView;
    private final TextView messageView;
    private final Button startPauseButton;
    private final Button stopAlarmButton;
    /** Aakriti's face, her status light, her mood and the progress hairline. All optional. */
    private final View avatarView;
    private final View statusDot;
    private final TextView moodView;
    private final android.widget.ProgressBar barView;
    private Boolean pillShowsPause;
    /** "Last done ID": the row, the box, and the line beside it. Shown only while not running. */
    private final View startRow;
    private final android.widget.EditText lastDoneField;
    private final TextView lastDoneNote;
    private final android.widget.ImageView songButton;
    private boolean editing;
    private boolean fieldProblem;
    /** The box being typed in: "Last done ID" or the question for Aakriti. */
    private android.widget.EditText editingField;
    /** Ask Aakriti on the dashboard: the row, the box, her answer. Shown only while not running. */
    private final View askRow;
    private final android.widget.EditText askField;
    private final TextView answerView;
    /** The folded panel: a ring and nothing else (see the layout). */
    private final View handle;
    private final View mini;
    private final View miniArt;
    /** Songs or Auto-Dialogues, on the dashboard's sound row. */
    private final android.widget.ImageView linesButton;
    /** The open panel's own padding; the folded ring has none. */
    private final int padLeft, padTop, padRight, padBottom;
    /** The glass step the words' pale edge was last drawn for; -1 before the first time. */
    private int haloLevel = -1;
    /** Where the ring was when it was opened, and where the dashboard was then put. */
    private int ringX, ringY, openX = Integer.MIN_VALUE, openY = Integer.MIN_VALUE;
    private final TextView miniTitle;
    private final TextView miniSub;
    private final android.widget.ImageView miniPlay;
    private WaveBars miniWave;
    /** Pause was pressed on the mini player: the orders do not start the music again until Play is. */
    private boolean musicHeld;
    /** A line shown in place of the bot's message for a few seconds (Aakriti's answer, the song lock). */
    private String flashLine;
    private long flashUntil;
    /** Decides when the music changes; see MusicMood. */
    private final MusicMood music = new MusicMood();
    /** Set for a few seconds after she has taught herself a renamed label. */
    private long learnedUntil;
    private final Handler main = new Handler(Looper.getMainLooper());

    private boolean attached;
    private boolean collapsed;
    private boolean running;
    /** Last frame, replayed after the panel is re-attached. */
    private String lastState = "IDLE";
    private String lastRetailer = "—";
    private String lastMessage = "Ready";
    private int success, skipped, errors, processed, expected = RetailerPolicy.EXPECTED_RETAILER_COUNT;

    // ---- Feedback (flourish, scenes). Display only; see FeedbackCues. ----
    /** Hands out each flourish and scene once; the first frame after the panel appears shows none. */
    private final FeedbackCues.Tracker cues = new FeedbackCues.Tracker();
    /** Words shown in place of the state name for a moment after an order is placed. */
    private String flourish;
    private long flourishUntil;
    private int cheers;
    private GradientDrawable panelBackground;
    private android.animation.ValueAnimator glow;
    private static final long FLOURISH_MS = 2600;
    private final Runnable endFlourish = () -> {
        flourish = null;
        renderSnapshot();
    };

    public FloatingPanelController(Context context, Actions actions) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.root = LayoutInflater.from(this.context).inflate(R.layout.floaty_layout, null, false);

        this.body = root.findViewById(R.id.panel_body);
        this.stateView = root.findViewById(R.id.panel_state);
        this.currentView = root.findViewById(R.id.panel_current);
        this.countsView = root.findViewById(R.id.panel_counts);
        this.progressView = root.findViewById(R.id.panel_progress);
        this.messageView = root.findViewById(R.id.panel_message);
        this.startPauseButton = root.findViewById(R.id.panel_start_pause);
        this.stopAlarmButton = root.findViewById(R.id.panel_stop_alarm);
        this.avatarView = root.findViewById(R.id.panel_avatar);
        this.statusDot = root.findViewById(R.id.panel_status_dot);
        this.moodView = root.findViewById(R.id.panel_mood);
        this.barView = root.findViewById(R.id.panel_bar);
        this.startRow = root.findViewById(R.id.panel_start_row);
        this.lastDoneField = root.findViewById(R.id.panel_last_done);
        this.lastDoneNote = root.findViewById(R.id.panel_last_done_note);
        this.songButton = root.findViewById(R.id.panel_song);
        this.askRow = root.findViewById(R.id.panel_ask_row);
        this.askField = root.findViewById(R.id.panel_ask);
        this.answerView = root.findViewById(R.id.panel_answer);
        this.handle = root.findViewById(R.id.panel_drag_handle);
        this.mini = root.findViewById(R.id.panel_mini);
        this.miniArt = root.findViewById(R.id.panel_mini_art);
        this.miniTitle = root.findViewById(R.id.panel_mini_title);
        this.miniSub = root.findViewById(R.id.panel_mini_sub);
        this.miniPlay = root.findViewById(R.id.panel_mini_play);
        this.linesButton = root.findViewById(R.id.panel_lines);
        this.padLeft = root.getPaddingLeft();
        this.padTop = root.getPaddingTop();
        this.padRight = root.getPaddingRight();
        this.padBottom = root.getPaddingBottom();
        // Before Aakriti takes a picture of the screen a run stopped on, the full-screen
        // scene is held back so that the picture shows the Sudha app and not the scene.
        Aakriti.beforePicture = () -> CinematicOverlay.holdBack(1600);

        this.params = buildParams();
        applyRoundedBackground();

        root.findViewById(R.id.panel_drag_handle).setOnTouchListener(new DragTouchListener());
        startPauseButton.setOnClickListener(v -> {
            if (running) {
                actions.onPause();
            } else {
                // Whatever is in the box is stored first, so that START sees it.
                endEditing();
                if (fieldProblem) {
                    // Not a retailer number: say so instead of starting somewhere unexpected.
                    flash(this.context.getString(R.string.start_note_invalid));
                    return;
                }
                actions.onStart();
            }
        });
        root.findViewById(R.id.panel_reset).setOnClickListener(v -> {
            endEditing();
            actions.onReset();
        });
        root.findViewById(R.id.panel_hide).setOnClickListener(v -> {
            endEditing();
            actions.onHide();
        });
        root.findViewById(R.id.panel_capture).setOnClickListener(v -> actions.onCapture());
        if (stopAlarmButton != null) {
            stopAlarmButton.setOnClickListener(v -> {
                setAlarmActive(false);
                actions.onStopAlarm();
            });
        }
        root.findViewById(R.id.panel_chat).setOnClickListener(v -> askAakriti());
        if (songButton != null) {
            songButton.setOnClickListener(v -> toggleSongLock());
            songButton.setOnLongClickListener(v -> tellSong());
        }
        wireLastDoneBox();
        wireAskBox();
        wireMiniPlayer();
        // Silent UI: no tap on the panel makes the phone click.
        MagicUi.silence(root);
    }

    // ------------------------------------------- the ring, and the sound row
    // Folded, the panel is a ring and nothing else: no fill, no picture, no word. A tap on
    // it opens the dashboard; a drag moves it. The song title and Play / Pause, which used
    // to be the folded face, are a row on the dashboard now, next to Songs / Auto-Dialogues.

    @SuppressLint("ClickableViewAccessibility")
    private void wireMiniPlayer() {
        if (mini == null) return;
        try {
            android.view.ViewGroup holder = root.findViewById(R.id.panel_mini_wave);
            if (holder != null) {
                miniWave = new WaveBars(context);
                holder.addView(miniWave, new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            }
        } catch (Throwable e) {
            android.util.Log.w("SudhaAutoBot", "No wave on the sound row", e);
        }
        mini.setOnTouchListener(new MiniTouchListener());
        if (miniTitle != null) miniTitle.setSelected(true);      // lets a long title scroll
        if (miniPlay != null) miniPlay.setOnClickListener(v -> togglePlay());
        if (linesButton != null) linesButton.setOnClickListener(v -> toggleLines());
    }

    /** Play / Pause on the sound row. */
    private void togglePlay() {
        try {
            if (MusicEngine.audible()) {
                musicHeld = true;
                MusicEngine.stop();
            } else {
                musicHeld = false;
                MusicEngine.playNow(context);
            }
        } catch (Throwable e) {
            android.util.Log.w("SudhaAutoBot", "Play / Pause skipped", e);
        }
        renderMini();
        main.postDelayed(this::renderMini, 400);
    }

    /**
     * Songs or Auto-Dialogues. On: no songs; a rousing film dialogue for each order placed
     * and a sad one for each error. Off: the songs again.
     */
    private void toggleLines() {
        try {
            boolean on = !FeedbackPrefs.linesOn(context);
            FeedbackPrefs.set(context, FeedbackPrefs.KEY_DIALOGUES, on);
            // Whatever was being heard belongs to the other way of working.
            MusicEngine.stop();
            musicHeld = false;
            if (!FeedbackPrefs.isOn(context, FeedbackPrefs.KEY_MUSIC)) {
                flash(context.getString(R.string.song_off));
            } else {
                flash(context.getString(on ? R.string.lines_on : R.string.lines_off));
                // In the middle of a run: get the first ones ready now.
                if (running) MusicEngine.warm(context);
            }
        } catch (Throwable e) {
            android.util.Log.w("SudhaAutoBot", "Auto-Dialogues not switched", e);
        }
        renderMini();
    }

    private final Runnable miniTick = new Runnable() {
        @Override
        public void run() {
            if (!attached || collapsed) return;
            renderMini();
            main.postDelayed(this, 1000);
        }
    };

    /** The sound row: the song or the dialogue, the wave, Play or Pause. Never a word about orders. */
    private void renderMini() {
        if (miniTitle == null || collapsed) return;
        try {
            boolean lines = FeedbackPrefs.linesOn(context);
            boolean on = MusicEngine.audible();
            String title = MusicEngine.nowPlaying();
            String line;
            if (!title.isEmpty()) line = title;
            else if (!MusicEngine.finding().isEmpty()) line = context.getString(R.string.mini_loading);
            else if (!FeedbackPrefs.isOn(context, FeedbackPrefs.KEY_MUSIC)) line = context.getString(R.string.mini_off);
            else if (musicHeld) line = context.getString(R.string.mini_paused);
            else line = context.getString(lines ? R.string.mini_idle_lines : R.string.mini_idle);
            if (!line.contentEquals(miniTitle.getText())) miniTitle.setText(line);
            String under = context.getString(lines ? R.string.mini_sub_lines : R.string.mini_sub);
            if (miniSub != null && !under.contentEquals(miniSub.getText())) miniSub.setText(under);
            if (miniPlay != null) miniPlay.setImageResource(on ? R.drawable.ic_pause : R.drawable.ic_play);
            if (miniWave != null) miniWave.setPlaying(on && !title.isEmpty());
            if (linesButton != null) {
                linesButton.setSelected(lines);
                linesButton.setImageTintList(android.content.res.ColorStateList.valueOf(
                        lines ? MagicUi.BRAND : MagicUi.MUTED));
            }
        } catch (Throwable e) {
            android.util.Log.w("SudhaAutoBot", "Sound row not drawn", e);
        }
    }

    /**
     * The folded ring. It has no colour of its own. The only hint of the bot on it: the
     * hairline turns amber when the run has stopped and wants a look, green when the whole
     * list is done.
     */
    private void renderRing() {
        if (miniArt == null || miniArt.getBackground() == null) return;
        try {
            android.graphics.drawable.Drawable ring = miniArt.getBackground().mutate();
            if ("ERROR".equals(lastState)) ring.setTint(MagicUi.WARN);
            else if ("COMPLETE".equals(lastState)) ring.setTint(MagicUi.GOOD);
            else ring.setTintList(null);
        } catch (Throwable e) {
            android.util.Log.w("SudhaAutoBot", "Ring not drawn", e);
        }
    }

    /** Folds the panel into the ring, or opens the dashboard. */
    private void setCollapsed(boolean fold) {
        endEditing();
        boolean was = collapsed;
        collapsed = fold;
        if (mini != null) mini.setVisibility(fold ? View.VISIBLE : View.GONE);
        if (handle != null) handle.setVisibility(fold && mini != null ? View.GONE : View.VISIBLE);
        body.setVisibility(fold ? View.GONE : View.VISIBLE);
        applyGlass();
        main.removeCallbacks(miniTick);
        if (fold) {
            // Opened at the edge of the screen and not moved since: the ring goes back
            // to where it was tapped.
            if (!was && params.x == openX && params.y == openY) {
                params.x = ringX;
                params.y = ringY;
            }
            openX = Integer.MIN_VALUE;
            renderRing();
        } else {
            if (was) keepOnScreen();
            renderSnapshot();
            renderMini();
            main.postDelayed(miniTick, 1000);
        }
        if (attached && windowManager != null) {
            try {
                windowManager.updateViewLayout(root, params);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * The ring can sit at the very edge of the screen; the dashboard is much wider. When
     * it opens it is moved in just far enough to be whole on the screen.
     */
    private void keepOnScreen() {
        try {
            ringX = params.x;
            ringY = params.y;
            android.util.DisplayMetrics screen = context.getResources().getDisplayMetrics();
            int any = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            root.measure(any, any);
            int[] at = Glass.fit(params.x, params.y, root.getMeasuredWidth(), root.getMeasuredHeight(),
                    screen.widthPixels, screen.heightPixels);
            params.x = at[0];
            params.y = at[1];
            openX = params.x;
            openY = params.y;
        } catch (Throwable e) {
            android.util.Log.w("SudhaAutoBot", "Panel not moved on to the screen", e);
        }
    }

    /**
     * Paints the panel for the face it shows. Folded: nothing at all - no sheet, no
     * padding, only the ring. Open: a sheet of white glass, as see-through as the setting
     * says, and a pale edge round every word so that it reads over whatever is behind.
     */
    private void applyGlass() {
        try {
            if (collapsed) {
                root.setBackground(null);
                root.setPadding(0, 0, 0, 0);
                return;
            }
            int level = FeedbackPrefs.glass(context);
            if (panelBackground != null) panelBackground.setColor(Glass.sheet(level));
            root.setBackground(panelBackground);
            root.setPadding(padLeft, padTop, padRight, padBottom);
            if (level != haloLevel) {
                haloLevel = level;
                halo(root, Glass.halo(level));
            }
        } catch (Throwable e) {
            android.util.Log.w("SudhaAutoBot", "Glass not painted", e);
        }
    }

    /** Draws the pale edge round every word under this view. The words themselves are left as they are. */
    private void halo(View view, int color) {
        if (view instanceof TextView) {
            boolean none = (color >>> 24) == 0;
            ((TextView) view).setShadowLayer(none ? 0f : dp(3), 0f, 0f, color);
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) halo(group.getChildAt(i), color);
        }
    }

    // ----------------------------------------------------------- Ask Aakriti
    // A box on the dashboard. She answers from the day's figures, her notebook, the last
    // stop and the file of skipped retailers; nothing typed here leaves the phone.

    @SuppressLint("ClickableViewAccessibility")
    private void wireAskBox() {
        if (askField == null) return;
        askField.setCursorVisible(false);
        askField.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) beginEditing(askField);
            return false;
        });
        askField.setOnEditorActionListener((view, action, event) -> {
            answerTyped();
            return true;
        });
        askField.setOnKeyListener((view, code, event) -> {
            if (code == android.view.KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == android.view.KeyEvent.ACTION_UP) answerTyped();
                return true;
            }
            if (code == android.view.KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == android.view.KeyEvent.ACTION_UP) endEditing();
                return true;
            }
            return false;
        });
    }

    private void answerTyped() {
        if (askField == null) return;
        String asked = askField.getText().toString().trim();
        endEditing();
        if (asked.isEmpty()) return;
        try {
            AakritiChat.Reply reply = AakritiChat.reply(asked, facts());
            askField.setText("");
            if (answerView != null) {
                answerView.setText(reply.text);
                answerView.setVisibility(View.VISIBLE);
            }
            gesture(Gesture.HOP);
            if (reply.action != AakritiChat.Action.NONE) openChat();
        } catch (Throwable e) {
            android.util.Log.w("SudhaAutoBot", "Aakriti could not answer", e);
        }
        renderSnapshot();
    }

    /** What she may talk about, read fresh. The same facts the chat in the app uses. */
    private AakritiChat.Facts facts() {
        AakritiChat.Facts facts = new AakritiChat.Facts();
        facts.placed = success;
        facts.skipped = skipped;
        facts.toCheck = errors;
        facts.handled = processed;
        facts.expected = expected;
        facts.ready = true;
        try {
            facts.skippedLog = SkipLog.read(context, 6);
            facts.skippedTotal = SkipLog.count(context);
            Aakriti aakriti = Aakriti.with(context);
            Roster roster = aakriti.roster();
            facts.roster = roster.describe();
            facts.distributor = roster.distributorLabel();
            facts.distributorBefore = roster.distributorBefore();
            facts.lastStop = aakriti.lastStopReason();
            facts.reportBrief = aakriti.reportBrief();
            facts.hasReport = Aakriti.latestReport(context) != null;
            facts.sudhaUpdate = aakriti.sudhaUpdateNote();
        } catch (Throwable e) {
            android.util.Log.w("SudhaAutoBot", "Some of Aakriti's facts are missing", e);
        }
        return facts;
    }

    /** Opens the full chat in the app (sharing a report and the notebook live there). */
    private void openChat() {
        android.content.Intent open = new android.content.Intent(context, MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(AakritiNotifier.EXTRA_OPEN, AakritiNotifier.OPEN_CHAT);
        endEditing();
        context.startActivity(open);
    }

    private void renderAskRow() {
        if (askRow == null) return;
        askRow.setVisibility(running ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------- "Last done ID"

    /**
     * The box on the panel. The panel's window does not normally take the keyboard (the
     * Sudha app must keep it), so the window is made focusable only while the box is being
     * typed in, and handed back the moment typing ends.
     */
    @SuppressLint("ClickableViewAccessibility")
    private void wireLastDoneBox() {
        if (lastDoneField == null) return;
        lastDoneField.setCursorVisible(false);
        // Six digits need no copy / paste menu, and a menu popping out of an overlay window
        // is one more thing that differs from phone to phone.
        lastDoneField.setLongClickable(false);
        lastDoneField.setTextIsSelectable(false);
        android.view.ActionMode.Callback noMenu = new android.view.ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(android.view.ActionMode mode, android.view.Menu menu) {
                return false;
            }

            @Override
            public boolean onPrepareActionMode(android.view.ActionMode mode, android.view.Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(android.view.ActionMode mode, android.view.MenuItem item) {
                return false;
            }

            @Override
            public void onDestroyActionMode(android.view.ActionMode mode) {
            }
        };
        lastDoneField.setCustomSelectionActionModeCallback(noMenu);
        lastDoneField.setCustomInsertionActionModeCallback(noMenu);
        lastDoneField.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) beginEditing(lastDoneField);
            return false;
        });
        lastDoneField.setOnEditorActionListener((view, action, event) -> {
            endEditing();
            return true;
        });
        lastDoneField.setOnKeyListener((view, code, event) -> {
            if (code == android.view.KeyEvent.KEYCODE_BACK
                    || code == android.view.KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == android.view.KeyEvent.ACTION_UP) endEditing();
                return true;
            }
            return false;
        });
        // A touch anywhere else on the screen ends the typing and gives the keyboard back.
        root.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) endEditing();
            return false;
        });
    }

    private android.view.inputmethod.InputMethodManager keyboard() {
        return (android.view.inputmethod.InputMethodManager)
                context.getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    private void beginEditing(final android.widget.EditText field) {
        if (field == null || running || !attached) return;
        if (editing && editingField == field) return;
        if (editing) endEditing();
        editing = true;
        editingField = field;
        if (field == lastDoneField) fieldProblem = false;
        try {
            params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            params.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
            windowManager.updateViewLayout(root, params);
            field.setCursorVisible(true);
            field.requestFocus();
            field.setSelection(field.getText().length());
            // The window needs a moment to receive focus before the keyboard will come.
            final Runnable show = () -> {
                if (!editing || editingField != field) return;
                try {
                    keyboard().showSoftInput(field, 0);
                } catch (Throwable ignored) {
                }
            };
            main.postDelayed(show, 150);
            main.postDelayed(show, 500);
        } catch (Throwable e) {
            editing = false;
            editingField = null;
            android.util.Log.w("SudhaAutoBot", "The box could not take the keyboard", e);
        }
    }

    /** Stores what is in the box and hands the keyboard back to the app underneath. */
    private void endEditing() {
        if (!editing) return;
        editing = false;
        final android.widget.EditText field = editingField == null ? lastDoneField : editingField;
        editingField = null;
        if (field == null) return;
        try {
            if (field == lastDoneField) {
                String typed = lastDoneField.getText().toString().trim();
                String id = RunHistory.setTyped(context, typed);
                fieldProblem = !typed.isEmpty() && id == null;
            }
            keyboard().hideSoftInputFromWindow(field.getWindowToken(), 0);
            field.clearFocus();
            field.setCursorVisible(false);
            params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            params.flags &= ~WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
            if (attached) windowManager.updateViewLayout(root, params);
        } catch (Throwable e) {
            android.util.Log.w("SudhaAutoBot", "The box could not be closed cleanly", e);
        }
        renderStartRow();
    }

    /** The box and the line beside it, from the day's history. Left alone while being typed in. */
    private void renderStartRow() {
        if (startRow == null || lastDoneField == null) return;
        startRow.setVisibility(running ? View.GONE : View.VISIBLE);
        if (running || editing) return;
        try {
            String typed = RunHistory.typed(context);
            String shown = typed == null ? "" : StartPoint.digits(typed);
            if (!shown.equals(lastDoneField.getText().toString()) && !fieldProblem) {
                lastDoneField.setText(shown);
            }
            String hint = context.getString(R.string.panel_last_done_hint);
            String note;
            int color = MagicUi.MUTED;
            if (fieldProblem) {
                note = context.getString(R.string.start_note_invalid);
                color = MagicUi.BAD;
            } else if (typed != null) {
                note = context.getString(R.string.start_note_typed, typed);
                color = MagicUi.BRAND;
            } else {
                String last = RunHistory.lastDone(context);
                String before = last == null ? RunHistory.beforeReset(context) : null;
                if (last != null) {
                    hint = StartPoint.digits(last);
                    note = context.getString(R.string.start_note_history, last,
                            StartPoint.age(RunHistory.lastDoneAt(context), System.currentTimeMillis()));
                } else if (before != null) {
                    hint = StartPoint.digits(before);
                    note = context.getString(R.string.start_note_reset, before);
                    color = MagicUi.WARN;
                } else {
                    note = context.getString(R.string.start_note_empty);
                }
            }
            lastDoneField.setHint(hint);
            if (lastDoneNote != null) {
                lastDoneNote.setText(note);
                lastDoneNote.setTextColor(color);
            }
        } catch (Throwable e) {
            android.util.Log.w("SudhaAutoBot", "History could not be shown", e);
        }
    }

    // ------------------------------------------------- Ask Aakriti, Lock song

    /** Shows a line in place of the bot's message for a few seconds. */
    private void flash(String line) {
        flashLine = line;
        flashUntil = System.currentTimeMillis() + FLASH_MS;
        main.removeCallbacks(endFlash);
        main.postDelayed(endFlash, FLASH_MS);
        renderSnapshot();
    }

    private static final long FLASH_MS = 6000;
    private final Runnable endFlash = () -> {
        flashLine = null;
        renderSnapshot();
    };

    /**
     * While the bot runs she answers on the panel, so the Sudha app stays in front. When it
     * is not running the full chat is opened in the app.
     */
    private void askAakriti() {
        try {
            gesture(Gesture.HOP);
            if (!running && !"COMPLETE".equals(lastState)) {
                String typed = RunHistory.typed(context);
                if (typed != null) flash(context.getString(R.string.panel_chat_resume, typed));
                if (askField != null) {
                    // The question box on the dashboard takes the keyboard.
                    beginEditing(askField);
                    return;
                }
                openChat();
                return;
            }
            flash("COMPLETE".equals(lastState)
                    ? context.getString(R.string.panel_chat_done, success, skipped, errors)
                    : context.getString(R.string.panel_chat_working, processed, expected, success, skipped, errors)
                            + " " + context.getString(R.string.panel_ask_running));
        } catch (Throwable e) {
            flash(context.getString(R.string.panel_chat_idle, processed, expected));
        }
    }

    /**
     * Lock song. On: the song being heard stays and starts again when it ends, whatever the
     * orders do. Off: upbeat after an order, sad after an error, as before.
     */
    private void toggleSongLock() {
        try {
            boolean on = !SongBook.locked(context);
            MusicEngine.setLocked(context, on);
            renderSong();
            if (!on) {
                flash(context.getString(R.string.song_unlocked));
            } else if (!MusicEngine.nowPlaying().isEmpty()) {
                flash(context.getString(R.string.song_locked, MusicEngine.nowPlaying()));
            } else if (!FeedbackPrefs.isOn(context, FeedbackPrefs.KEY_MUSIC)) {
                flash(context.getString(R.string.song_off));
            } else if (!MusicEngine.lastProblem().isEmpty()) {
                flash(context.getString(R.string.song_problem, MusicEngine.lastProblem()));
            } else {
                flash(context.getString(R.string.song_lock_waiting));
            }
        } catch (Throwable e) {
            android.util.Log.w("SudhaAutoBot", "Song lock skipped", e);
        }
    }

    /** A long press on the song button: what is playing, or why nothing is. */
    private boolean tellSong() {
        try {
            String title = MusicEngine.nowPlaying();
            if (!title.isEmpty()) {
                flash(context.getString(SongBook.locked(context)
                        ? R.string.song_locked : R.string.song_now, title));
            } else if (!FeedbackPrefs.isOn(context, FeedbackPrefs.KEY_MUSIC)) {
                flash(context.getString(R.string.song_off));
            } else if (!MusicEngine.finding().isEmpty()) {
                flash(MusicEngine.finding());
            } else if (!MusicEngine.lastProblem().isEmpty()) {
                flash(context.getString(R.string.song_problem, MusicEngine.lastProblem()));
            } else {
                flash(context.getString(R.string.song_waiting));
            }
        } catch (Throwable e) {
            android.util.Log.w("SudhaAutoBot", "Song note skipped", e);
        }
        return true;
    }

    private void renderSong() {
        if (songButton == null) return;
        boolean locked = SongBook.locked(context);
        songButton.setSelected(locked);
        songButton.setImageResource(locked ? R.drawable.ic_song_locked : R.drawable.ic_song);
        songButton.setColorFilter(locked ? MagicUi.BRAND : MagicUi.MUTED);
    }

    private WindowManager.LayoutParams buildParams() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        layoutParams.x = 16;
        layoutParams.y = 140;
        layoutParams.windowAnimations = 0;
        return layoutParams;
    }

    /**
     * A sheet of white glass: evenly rounded, with a hairline edge. Only the paint is set
     * here: the panel's size and position are the layout's, because the bot plans its
     * touches around where it sits. {@link #applyGlass} sets how see-through it is.
     */
    private void applyRoundedBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Glass.sheet(Glass.DEFAULT));
        background.setCornerRadius(dp(20));
        background.setStroke(Math.max(1, Math.round(dp(1))), REST_STROKE);
        root.setBackground(background);
        panelBackground = background;
    }

    private static final int REST_STROKE = 0xFFD9DCE8;

    private float dp(float value) {
        return value * context.getResources().getDisplayMetrics().density;
    }

    // ------------------------------------------------------------ attach/detach

    /** Adds the overlay; returns false when the permission is missing. */
    public boolean show() {
        if (attached || windowManager == null) return attached;
        try {
            windowManager.addView(root, params);
            attached = true;
            musicHeld = false;
            setCollapsed(false);
            renderSong();
            renderSnapshot();
            return true;
        } catch (Exception e) {
            attached = false;
            return false;
        }
    }

    public void remove() {
        if (!attached || windowManager == null) return;
        endEditing();
        main.removeCallbacks(miniTick);
        MusicEngine.stop();
        try {
            windowManager.removeViewImmediate(root);
        } catch (Exception ignored) {
            // Already detached by the system (e.g. overlay permission revoked).
        }
        attached = false;
    }

    public boolean isAttached() {
        return attached;
    }

    /**
     * Where the panel currently sits on screen, or null when it is not shown.
     *
     * <p>The panel takes any touch that lands on it, including the bot's own. The service
     * asks before pressing a spot so it can say "the panel is in the way" instead of
     * pressing its own window and wondering why nothing happened.</p>
     */
    public android.graphics.Rect boundsOnScreen() {
        if (!attached || root.getWidth() <= 0 || root.getHeight() <= 0) return null;
        int[] at = new int[2];
        root.getLocationOnScreen(at);
        return new android.graphics.Rect(at[0], at[1], at[0] + root.getWidth(), at[1] + root.getHeight());
    }

    // ------------------------------------------------------------- panel updates

    /**
     * Repaints the panel. Safe to call from any thread and with any state object;
     * values are snapshotted on the main looper.
     */
    public void update(BotState state, String retailerId, String message, boolean isRunning,
                       int successCount, int skippedCount, int errorCount,
                       int processedCount, int expectedCount) {
        main.post(() -> {
            boolean started = isRunning && !running;
            running = isRunning;
            lastState = state == null ? "IDLE" : state.name();
            lastRetailer = retailerId == null || retailerId.isEmpty() ? "—" : retailerId;
            lastMessage = message == null ? "" : message;
            success = successCount;
            skipped = skippedCount;
            errors = errorCount;
            processed = processedCount;
            expected = Math.max(1, expectedCount);
            if (running) endEditing();
            announce(cues.next(lastState, success));
            try {
                // Auto-Dialogues: a film dialogue for each outcome. Otherwise: the songs.
                MusicMood.Step step = FeedbackPrefs.linesOn(context)
                        ? music.nextLine(lastState, running, success, errors)
                        : music.next(lastState, running, success, errors);
                // Paused by hand on the sound row: the orders do not start it again.
                if (!musicHeld) MusicEngine.step(context, step);
            } catch (Throwable ignored) {
            }
            // A run has begun: fold into the ring, out of the way.
            if (started && attached && !collapsed) setCollapsed(true);
            renderSnapshot();
            renderRing();
        });
    }

    // ---------------------------------------------------------------- feedback

    /**
     * Flourish and scenes for a cue (see {@link FeedbackCues.Tracker}). Nothing is played:
     * the only thing the app lets be heard is the songs (see {@link MusicEngine}).
     *
     * <p>Everything here is for the person watching. It is wrapped so that a scene that will
     * not open can never disturb the panel, let alone the run.</p>
     */
    private void announce(FeedbackCues.Cue cue) {
        try {
            if (cue == FeedbackCues.Cue.NONE) {
                // A run that is moving again clears the stage.
                if (!FeedbackCues.isResting(lastState) && CinematicOverlay.isShowing()) {
                    CinematicOverlay.dismiss();
                }
                return;
            }
            boolean selfFixing = Aakriti.recovering();
            // Silent UI: no voice, no chime. A cue is something to see, never to hear.
            switch (cue) {
                case RUN_STARTED:
                    // A new run clears the stage, and the first songs are found now so that
                    // the first order is answered with music straight away.
                    CinematicOverlay.dismiss();
                    try {
                        MusicEngine.warm(context);
                    } catch (Throwable ignored) {
                    }
                    gesture(Gesture.HOP);
                    break;
                case ORDER_PLACED:
                    flourish = FeedbackCues.orderWords(cheers++);
                    flourishUntil = System.currentTimeMillis() + FLOURISH_MS;
                    main.removeCallbacks(endFlourish);
                    main.postDelayed(endFlourish, FLOURISH_MS);
                    pulse(MagicUi.GOOD);
                    gesture(Gesture.HOP);
                    break;
                case RUN_COMPLETE: {
                    pulse(MagicUi.BRAND);
                    gesture(Gesture.SPIN);
                    cheers++;
                    String detail = FeedbackCues.tally(success, skipped, errors)
                            + "\n" + processed + " of " + expected + " retailers handled";
                    // "Kaam complete hua" - on the pod, as a toast, as a (silent)
                    // notification, and (below) across the whole screen.
                    flourish = context.getString(R.string.panel_complete_flourish);
                    flourishUntil = System.currentTimeMillis() + COMPLETE_FLOURISH_MS;
                    main.removeCallbacks(endFlourish);
                    main.postDelayed(endFlourish, COMPLETE_FLOURISH_MS);
                    try {
                        android.widget.Toast.makeText(context, R.string.complete_toast,
                                android.widget.Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {
                    }
                    AakritiNotifier.complete(context, detail.replace('\n', ' '));

                    // The scene never goes over a bot that is still pressing things.
                    final boolean scene = !running && FeedbackPrefs.isOn(context, FeedbackPrefs.KEY_SCENES);
                    if (scene) {
                        CinematicOverlay.show(context, SceneView.Kind.VICTORY,
                                FeedbackCues.COMPLETE_HEADLINE,
                                errors > 0 ? FeedbackCues.COMPLETE_SUBLINE + "  (" + errors + " want a second look.)"
                                        : FeedbackCues.COMPLETE_SUBLINE,
                                detail);
                    }
                    break;
                }
                case RUN_STOPPED: {
                    if (selfFixing) {
                        // She has already worked out what changed and is starting again:
                        // no alarm, no scene - just a word on the pod.
                        learnedUntil = System.currentTimeMillis() + 6000;
                        pulse(MagicUi.GOOD);
                        gesture(Gesture.HOP);
                        break;
                    }
                    pulse(MagicUi.BAD);
                    gesture(Gesture.SHAKE);
                    if (running || !FeedbackPrefs.isOn(context, FeedbackPrefs.KEY_SCENES)) break;
                    String[] words = FeedbackCues.dramaWords(cheers++);
                    ErrorPlaybook.Entry known = ErrorPlaybook.lookup(lastMessage);
                    CinematicOverlay.show(context, SceneView.Kind.DRAMA, words[0],
                            known == null ? words[1] : known.title,
                            known == null ? lastMessage : known.advice + "\n\n" + lastMessage);
                    break;
                }
                default:
                    break;
            }
        } catch (Throwable e) {
            android.util.Log.w("SudhaAutoBot", "Feedback skipped", e);
        }
    }

    private static final long COMPLETE_FLOURISH_MS = 9000;

    private enum Gesture { HOP, SPIN, SHAKE }

    /** A small movement of Aakriti's face at a milestone. One shot; nothing loops. */
    private void gesture(Gesture kind) {
        if (avatarView == null || !attached) return;
        try {
            avatarView.animate().cancel();
            avatarView.setTranslationX(0f);
            avatarView.setTranslationY(0f);
            avatarView.setRotation(0f);
            switch (kind) {
                case HOP:
                    avatarView.animate().translationY(-dp(5)).setDuration(140).withEndAction(() ->
                            avatarView.animate().translationY(0f).setDuration(260)
                                    .setInterpolator(new android.view.animation.BounceInterpolator()).start())
                            .start();
                    break;
                case SPIN:
                    avatarView.animate().rotation(360f).setDuration(900)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f))
                            .withEndAction(() -> avatarView.setRotation(0f)).start();
                    break;
                default:
                    avatarView.animate().translationX(dp(4)).setDuration(420)
                            .setInterpolator(new android.view.animation.CycleInterpolator(4f))
                            .withEndAction(() -> avatarView.setTranslationX(0f)).start();
                    break;
            }
        } catch (Throwable ignored) {
        }
    }

    /** The panel's hairline flares in a colour and settles back. Its size does not change. */
    private void pulse(int color) {
        if (panelBackground == null) return;
        if (glow != null) glow.cancel();
        final int hairline = Math.max(1, Math.round(dp(1)));
        final int flare = Math.max(2, Math.round(dp(2.5f)));
        glow = android.animation.ValueAnimator.ofFloat(1f, 0f);
        glow.setDuration(1400);
        glow.addUpdateListener(step -> {
            float lit = (float) step.getAnimatedValue();
            int blended = (int) new android.animation.ArgbEvaluator().evaluate(lit, REST_STROKE, color);
            panelBackground.setStroke(lit > 0.05f ? flare : hairline, blended);
        });
        glow.start();
    }

    /** Convenience overload driven by the ledger directly. */
    public void updateFromLedger(BotState state, String retailerId, String message,
                                 boolean isRunning, DailyLedger ledger, int expectedCount) {
        if (ledger == null) {
            update(state, retailerId, message, isRunning, success, skipped, errors, processed, expectedCount);
            return;
        }
        update(state, retailerId, message, isRunning,
                ledger.countBucket("SUCCESS"),
                ledger.countBucket("SKIPPED"),
                ledger.countBucket("ERROR"),
                ledger.processedCount(),
                expectedCount);
    }

    private void renderSnapshot() {
        boolean cheering = flourish != null && System.currentTimeMillis() < flourishUntil;
        stateView.setText(cheering ? flourish : lastState);
        stateView.setTextColor(cheering ? MagicUi.GOOD : colorForState());
        currentView.setText(lastRetailer);
        countsView.setText(counts());
        progressView.setText(processed + " / " + expected);
        if (barView != null) {
            barView.setProgress(Math.max(0, Math.min(1000, Math.round(processed * 1000f / Math.max(1, expected)))));
        }
        if (moodView != null) moodView.setText(mood());
        if (statusDot != null && statusDot.getBackground() != null) {
            statusDot.getBackground().mutate().setTint(dotColor());
        }
        boolean flashing = flashLine != null && System.currentTimeMillis() < flashUntil;
        messageView.setText(flashing ? flashLine : lastMessage);
        messageView.setTextColor(flashing ? MagicUi.BRAND : MagicUi.MUTED);
        renderStartRow();
        renderAskRow();
        renderMini();
        startPauseButton.setText(running
                ? context.getString(R.string.action_pause)
                : context.getString(R.string.action_start));
        if (pillShowsPause == null || pillShowsPause != running) {
            pillShowsPause = running;
            startPauseButton.setBackgroundResource(running
                    ? R.drawable.panel_pill_pause : R.drawable.panel_pill_start);
            startPauseButton.setTextColor(running ? MagicUi.BRAND : 0xFFFFFFFF);
        }
        if (!attached) return;
        try {
            windowManager.updateViewLayout(root, params);
        } catch (Exception ignored) {
        }
    }

    /** "Placed 40 · Skipped 2 · Check 0": quiet words, each figure in ink (red when something needs a look). */
    private CharSequence counts() {
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder();
        count(out, "Placed", success, MagicUi.TEXT);
        out.append("  ·  ");
        count(out, "Skipped", skipped, MagicUi.TEXT);
        out.append("  ·  ");
        count(out, "Check", errors, errors > 0 ? MagicUi.BAD : MagicUi.TEXT);
        return out;
    }

    private static void count(android.text.SpannableStringBuilder out, String word, int value, int color) {
        int from = out.length();
        out.append(word).append(' ');
        out.setSpan(new android.text.style.RelativeSizeSpan(0.92f), from, out.length(), 0);
        out.setSpan(new android.text.style.ForegroundColorSpan(MagicUi.MUTED), from, out.length(), 0);
        from = out.length();
        out.append(String.valueOf(value));
        out.setSpan(new android.text.style.ForegroundColorSpan(color), from, out.length(), 0);
        out.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), from, out.length(), 0);
    }

    /** One line about how the new employee is getting on. */
    private String mood() {
        if (System.currentTimeMillis() < learnedUntil) return context.getString(R.string.mood_learning);
        switch (lastState) {
            case "IDLE":
                return context.getString(R.string.mood_idle);
            case "PAUSED":
                return context.getString(R.string.mood_paused);
            case "ERROR":
                return context.getString(R.string.mood_stopped);
            case "COMPLETE":
                return context.getString(R.string.mood_complete);
            case "PLACE_ORDER":
            case "PAYMENT":
                return context.getString(R.string.mood_paying);
            default:
                return context.getString(R.string.mood_working);
        }
    }

    private int dotColor() {
        switch (lastState) {
            case "ERROR":
                return MagicUi.BAD;
            case "PAUSED":
                return MagicUi.WARN;
            case "COMPLETE":
                return MagicUi.BRAND;
            case "IDLE":
                return MagicUi.FAINT;
            default:
                return MagicUi.GOOD;
        }
    }

    private int colorForState() {
        switch (lastState) {
            case "SUCCESS":
            case "COMPLETE":
                return MagicUi.GOOD;
            case "ERROR":
                return MagicUi.BAD;
            case "PAUSED":
                return MagicUi.WARN;
            case "IDLE":
                return MagicUi.MUTED;
            default:
                return MagicUi.BRAND;
        }
    }

    /** Shows/hides the bright-red STOP ALARM button and auto-expands panel when ringing. */
    public void setAlarmActive(boolean active) {
        main.post(() -> {
            if (active && attached && collapsed) setCollapsed(false);
            if (stopAlarmButton != null) {
                stopAlarmButton.setVisibility(active ? View.VISIBLE : View.GONE);
            }
        });
    }

    /** Flashes a short message without changing the state machine. */
    public void toastState(String message) {
        main.post(() -> {
            lastMessage = message;
            messageView.setText(message);
        });
    }

    // ------------------------------------------------------------------- drag

    @SuppressLint("ClickableViewAccessibility")
    private final class DragTouchListener implements View.OnTouchListener {
        private int startX, startY;
        private float downX, downY;
        private boolean dragged;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = params.x;
                    startY = params.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    dragged = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (Math.abs(dx) + Math.abs(dy) > dp(8)) dragged = true;
                    if (dragged) {
                        params.x = startX + (int) dx;
                        params.y = Math.max(0, startY + (int) dy);
                        if (attached && windowManager != null) {
                            try {
                                windowManager.updateViewLayout(root, params);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragged) toggleCollapsed();
                    return true;
                default:
                    return true;
            }
        }
    }

    /** A tap on the dashboard's top strip folds the panel into the ring. */
    private void toggleCollapsed() {
        setCollapsed(!collapsed);
    }

    /**
     * Touches on the folded ring: a tap opens the dashboard; a drag moves the ring.
     */
    @SuppressLint("ClickableViewAccessibility")
    private final class MiniTouchListener implements View.OnTouchListener {
        private int startX, startY;
        private float downX, downY;
        private boolean dragged;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = params.x;
                    startY = params.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    dragged = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (Math.abs(dx) + Math.abs(dy) > dp(8)) dragged = true;
                    if (dragged) {
                        params.x = startX + (int) dx;
                        params.y = Math.max(0, startY + (int) dy);
                        if (attached && windowManager != null) {
                            try {
                                windowManager.updateViewLayout(root, params);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragged && collapsed) setCollapsed(false);
                    return true;
                default:
                    return true;
            }
        }
    }
}
