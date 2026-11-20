package com.vrksignatures.sudhaautobot;

import android.app.Activity;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.ViewGroup;
import android.view.WindowManager;

/**
 * Puts a {@link SceneView} on screen: over everything when a run has ended, or inside the
 * setup screen for the preview buttons.
 *
 * <p>The full-screen scene is shown only once the bot has <b>stopped driving the screen</b>
 * (run finished or run stopped). While a run is going nothing is drawn outside the
 * floating panel, so the scene can never sit on top of a button the bot is about to press
 * or appear in a picture the bot takes of the calendar.</p>
 */
final class CinematicOverlay {

    private static final String TAG = "SudhaAutoBot";
    private static final long VICTORY_MS = 8_000;
    /** Shorter, so that the screen the run stopped on is soon visible again. */
    private static final long DRAMA_MS = 6_000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static SceneView shown;
    private static WindowManager shownIn;
    private static final Runnable CLOSE = CinematicOverlay::dismiss;
    /** A scene that has been asked for but is being held back; see {@link #holdBack}. */
    private static Runnable waiting;
    /** The scene being previewed inside the setup screen, and how to close it. */
    private static SceneView previewed;
    private static Runnable previewClose;
    private static long heldUntil;

    private CinematicOverlay() {
    }

    /** Shows a scene above every app. Does nothing without the overlay permission. */
    static void show(Context context, SceneView.Kind kind, String headline, String subline,
                     String detail) {
        final Context app = context.getApplicationContext();
        final Runnable[] self = new Runnable[1];
        self[0] = () -> {
            try {
                // Still being held back (a picture of the screen is being taken): try again
                // once the hold is over, unless the scene has been called off meanwhile.
                long wait = heldUntil - android.os.SystemClock.uptimeMillis();
                if (wait > 0) {
                    waiting = self[0];
                    MAIN.postDelayed(self[0], wait);
                    return;
                }
                dismiss();
                if (!Settings.canDrawOverlays(app)) return;
                WindowManager manager = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
                if (manager == null) return;
                SceneView scene = new SceneView(app, kind, headline, subline, detail,
                        FeedbackPrefs.customPicture(app, cueFor(kind)));
                scene.setSoundEffectsEnabled(false);
                scene.setOnClickListener(v -> dismiss());
                int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                        PixelFormat.TRANSLUCENT);
                manager.addView(scene, params);
                shown = scene;
                shownIn = manager;
                scene.play();
                MAIN.postDelayed(CLOSE, kind == SceneView.Kind.VICTORY ? VICTORY_MS : DRAMA_MS);
            } catch (Throwable e) {
                Log.w(TAG, "Scene could not be shown", e);
                shown = null;
                shownIn = null;
            }
        };
        MAIN.post(self[0]);
    }

    /**
     * Keeps any scene off the screen for a moment. Used while Aakriti takes a picture of
     * the screen a run stopped on, so that the picture shows the Sudha app and not the scene.
     */
    static void holdBack(long ms) {
        heldUntil = Math.max(heldUntil, android.os.SystemClock.uptimeMillis() + ms);
    }

    /** Removes the scene, if one is up. Safe to call at any time, from any thread. */
    static void dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(CLOSE);
            return;
        }
        MAIN.removeCallbacks(CLOSE);
        if (waiting != null) {
            MAIN.removeCallbacks(waiting);
            waiting = null;
        }
        SceneView scene = shown;
        WindowManager manager = shownIn;
        shown = null;
        shownIn = null;
        if (scene == null || manager == null) return;
        try {
            scene.stop();
            manager.removeViewImmediate(scene);
        } catch (Throwable ignored) {
            // Already gone (for example the overlay permission was taken away).
        }
    }

    static boolean isShowing() {
        return shown != null;
    }

    /** Plays a scene inside the setup screen, for "show me what it looks like". */
    static void preview(Activity activity, SceneView.Kind kind, String headline, String subline,
                        String detail) {
        try {
            final SceneView scene = new SceneView(activity, kind, headline, subline, detail,
                    FeedbackPrefs.customPicture(activity, cueFor(kind)));
            if (previewClose != null) previewClose.run();
            final Runnable close = () -> {
                MAIN.removeCallbacksAndMessages(scene);
                scene.stop();
                ViewGroup parent = (ViewGroup) scene.getParent();
                if (parent != null) parent.removeView(scene);
                if (previewed == scene) {
                    previewed = null;
                    previewClose = null;
                }
            };
            previewed = scene;
            previewClose = close;
            scene.setSoundEffectsEnabled(false);
            scene.setOnClickListener(v -> close.run());
            scene.setElevation(MagicUi.dp(activity, 40));
            activity.addContentView(scene, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            scene.play();
            MAIN.postAtTime(close, scene, android.os.SystemClock.uptimeMillis()
                    + (kind == SceneView.Kind.VICTORY ? VICTORY_MS : DRAMA_MS));
        } catch (Throwable e) {
            Log.w(TAG, "Preview could not be shown", e);
        }
    }

    static FeedbackCues.Cue cueFor(SceneView.Kind kind) {
        return kind == SceneView.Kind.VICTORY
                ? FeedbackCues.Cue.RUN_COMPLETE : FeedbackCues.Cue.RUN_STOPPED;
    }
}
