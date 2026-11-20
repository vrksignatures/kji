package com.vrksignatures.sudhaautobot;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The songs, by mood, and the "lock song" switch. Kept in {@code SharedPreferences}
 * ("songs").
 *
 * <p>For each mood there are two lists. <b>Found</b>: what the last YouTube search
 * answered (see {@link MusicMood#searches}); filled and refilled by {@link MusicEngine},
 * never by hand. <b>Own</b>: links and files the person added on the setup screen. While
 * a mood has own songs, only those are played for it; otherwise the found ones are.</p>
 *
 * <p>The app itself carries no song and no song address.</p>
 */
final class SongBook {

    static final class Song {
        final String title;
        /** A YouTube link, a direct https link, or a content:// address of a file on the phone. */
        final String address;

        Song(String title, String address) {
            this.title = title == null || title.trim().isEmpty() ? "Song" : title.trim();
            this.address = address == null ? "" : address.trim();
        }
    }

    static final int MAX_SONGS = 30;
    private static final String FILE = "songs";
    private static final String LOCKED = "locked";
    private static final String LOCKED_ADDRESS = "locked_address";
    private static final String LOCKED_TITLE = "locked_title";

    private SongBook() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static String key(MusicMood.Mood mood) {
        if (mood == MusicMood.Mood.CHEER) return "cheer";
        if (mood == MusicMood.Mood.GLOOM) return "gloom";
        return mood == MusicMood.Mood.SAD ? "sad" : "happy";
    }

    static List<Song> list(Context context, MusicMood.Mood mood) {
        List<Song> songs = new ArrayList<>();
        try {
            JSONArray saved = new JSONArray(prefs(context).getString(key(mood), "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject one = saved.optJSONObject(i);
                if (one == null || one.optString("a").isEmpty()) continue;
                songs.add(new Song(one.optString("t"), one.optString("a")));
            }
        } catch (Exception ignored) {
        }
        return songs;
    }

    private static void save(Context context, MusicMood.Mood mood, List<Song> songs) {
        JSONArray out = new JSONArray();
        try {
            for (Song song : songs) out.put(new JSONObject().put("t", song.title).put("a", song.address));
        } catch (Exception ignored) {
        }
        prefs(context).edit().putString(key(mood), out.toString()).apply();
    }

    /** Returns false when the list is full or the song is already in it. */
    static boolean add(Context context, MusicMood.Mood mood, Song song) {
        List<Song> songs = list(context, mood);
        if (songs.size() >= MAX_SONGS) return false;
        for (Song have : songs) if (have.address.equals(song.address)) return false;
        songs.add(song);
        save(context, mood, songs);
        return true;
    }

    static void remove(Context context, MusicMood.Mood mood, String address) {
        List<Song> songs = list(context, mood);
        for (int i = songs.size() - 1; i >= 0; i--) {
            if (songs.get(i).address.equals(address)) songs.remove(i);
        }
        save(context, mood, songs);
    }

    /**
     * The song after the one played last for this mood: from the person's own list when
     * it has any, otherwise from the found list; null when both are empty.
     */
    static Song next(Context context, MusicMood.Mood mood) {
        List<Song> songs = list(context, mood);
        String turn = "turn_" + key(mood);
        if (songs.isEmpty()) {
            songs = found(context, mood);
            turn = "found_turn_" + key(mood);
        }
        if (songs.isEmpty()) return null;
        int at = (prefs(context).getInt(turn, -1) + 1) % songs.size();
        prefs(context).edit().putInt(turn, at).apply();
        return songs.get(at);
    }

    /** How many songs there are to choose from for this mood right now. */
    static int choices(Context context, MusicMood.Mood mood) {
        int own = list(context, mood).size();
        return own > 0 ? own : found(context, mood).size();
    }

    // ------------------------------------------------------- found on YouTube

    static final int MAX_FOUND = 25;

    static List<Song> found(Context context, MusicMood.Mood mood) {
        List<Song> songs = new ArrayList<>();
        try {
            JSONArray saved = new JSONArray(prefs(context).getString("found_" + key(mood), "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject one = saved.optJSONObject(i);
                if (one == null || one.optString("a").isEmpty()) continue;
                songs.add(new Song(one.optString("t"), one.optString("a")));
            }
        } catch (Exception ignored) {
        }
        return songs;
    }

    /** When the found list for this mood was last filled; 0 when never. */
    static long foundAt(Context context, MusicMood.Mood mood) {
        return prefs(context).getLong("found_at_" + key(mood), 0L);
    }

    /** Replaces the found list for this mood (at most {@link #MAX_FOUND} songs are kept). */
    static void setFound(Context context, MusicMood.Mood mood, List<Song> songs, long now) {
        JSONArray out = new JSONArray();
        try {
            for (int i = 0; i < songs.size() && i < MAX_FOUND; i++) {
                out.put(new JSONObject().put("t", songs.get(i).title).put("a", songs.get(i).address));
            }
        } catch (Exception ignored) {
        }
        prefs(context).edit().putString("found_" + key(mood), out.toString())
                .putLong("found_at_" + key(mood), now).putInt("found_turn_" + key(mood), -1).apply();
    }

    /** Takes one song out of the found list (YouTube no longer plays it). */
    static void dropFound(Context context, MusicMood.Mood mood, String address) {
        List<Song> songs = found(context, mood);
        boolean changed = false;
        for (int i = songs.size() - 1; i >= 0; i--) {
            if (songs.get(i).address.equals(address)) {
                songs.remove(i);
                changed = true;
            }
        }
        if (!changed) return;
        long at = foundAt(context, mood);
        int turn = prefs(context).getInt("found_turn_" + key(mood), -1);
        setFound(context, mood, songs, at);
        prefs(context).edit().putInt("found_turn_" + key(mood), Math.max(-1, turn - 1)).apply();
    }

    /** Which line of {@link MusicMood#searches} is used next for this mood; moves on by one. */
    static int nextSearch(Context context, MusicMood.Mood mood, int lines) {
        String turn = "search_turn_" + key(mood);
        int at = (prefs(context).getInt(turn, -1) + 1) % Math.max(1, lines);
        prefs(context).edit().putInt(turn, at).apply();
        return at;
    }

    // ------------------------------------------------------------- lock song

    static boolean locked(Context context) {
        return prefs(context).getBoolean(LOCKED, false);
    }

    /** The song the lock holds; null while the lock is off or has not caught a song yet. */
    static Song lockedSong(Context context) {
        SharedPreferences prefs = prefs(context);
        String address = prefs.getString(LOCKED_ADDRESS, "");
        if (!prefs.getBoolean(LOCKED, false) || address.isEmpty()) return null;
        return new Song(prefs.getString(LOCKED_TITLE, ""), address);
    }

    /** Turns the lock on (holding {@code song}, which may be null) or off. */
    static void lock(Context context, boolean on, Song song) {
        SharedPreferences.Editor edit = prefs(context).edit().putBoolean(LOCKED, on);
        if (on && song != null) {
            edit.putString(LOCKED_ADDRESS, song.address).putString(LOCKED_TITLE, song.title);
        } else if (!on) {
            edit.remove(LOCKED_ADDRESS).remove(LOCKED_TITLE);
        }
        edit.apply();
    }
}
