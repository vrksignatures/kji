package com.vrksignatures.sudhaautobot;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/** What the person switched on or off, and where their own pictures for the end-of-run card are kept. */
final class FeedbackPrefs {

    static final String PREFS = "feedback";
    /** The full-screen card when a run ends. */
    static final String KEY_SCENES = "full_screen_scenes";
    /** Songs while the bot works, streamed from the internet (see MusicEngine). */
    static final String KEY_MUSIC = "music_songs";
    /** Auto-Dialogues: film dialogues for each order and each error, in place of the songs. Off unless switched on. */
    static final String KEY_DIALOGUES = "auto_dialogues";

    private FeedbackPrefs() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isOn(Context context, String key) {
        return prefs(context).getBoolean(key, true);
    }

    /** Auto-Dialogues is off until it is switched on. */
    static boolean linesOn(Context context) {
        return prefs(context).getBoolean(KEY_DIALOGUES, false);
    }

    /** How see-through the open panel is: a step of {@link Glass}. */
    static final String KEY_GLASS = "panel_glass";

    static int glass(Context context) {
        return Glass.clamp(prefs(context).getInt(KEY_GLASS, Glass.DEFAULT));
    }

    static void setGlass(Context context, int level) {
        prefs(context).edit().putInt(KEY_GLASS, Glass.clamp(level)).apply();
    }

    static void set(Context context, String key, boolean on) {
        prefs(context).edit().putBoolean(key, on).apply();
    }

    private static File folder(Context context) {
        File folder = new File(context.getApplicationContext().getFilesDir(), "custom_media");
        if (!folder.exists() && !folder.mkdirs()) return folder;
        return folder;
    }

    private static String slot(FeedbackCues.Cue cue) {
        return cue == FeedbackCues.Cue.RUN_STOPPED ? "drama" : "victory";
    }

    /** The person's own picture or GIF for the scene. May not exist. */
    static File customPicture(Context context, FeedbackCues.Cue cue) {
        return new File(folder(context), slot(cue) + ".picture");
    }

    static boolean has(File file) {
        return file != null && file.isFile() && file.length() > 0;
    }
}
