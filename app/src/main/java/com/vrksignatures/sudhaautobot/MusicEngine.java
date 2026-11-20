package com.vrksignatures.sudhaautobot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Songs while the bot works, streamed from the internet with ExoPlayer: an upbeat Hindi
 * film song of the 80s / 90s once an order is placed, a cross-fade to a sad or tense one
 * after an error, back again on the next order. The rule is {@link MusicMood}; the songs
 * are found on YouTube ({@link YouTubeAudio}) or are the person's own ({@link SongBook}).
 *
 * <p><b>Sound only.</b> A YouTube song is played from its sound-only stream; where
 * YouTube offers none, from its HLS list with the picture track switched off. No picture
 * is decoded and nothing is drawn.</p>
 *
 * <p>Two players overlap for the length of a cross-fade. The players live in the
 * accessibility service's process, so the music carries on while the Sudha app is in
 * front. Searching and looking up a stream happen on a worker thread; the players are
 * only touched on the main thread.</p>
 *
 * <p><b>Auto-Dialogues</b> ({@link FeedbackPrefs#KEY_DIALOGUES}) is the other way of
 * working: no songs; one short Hindi film dialogue, found on YouTube the same way, is
 * said for an order placed and another for an error ({@link #say}). It starts at full
 * loudness, is heard once for at most {@link MusicMood#LINE_PLAY_MS}, and then it is quiet.</p>
 *
 * <p>For the ears only: nothing here is read by the bot, every call is fenced, and a song
 * that will not load is skipped.</p>
 */
final class MusicEngine {

    private static final String TAG = "SudhaAutoBot";
    private static final long FADE_MS = 2400;
    private static final long TICK_MS = 60;
    /** How long a sad song plays after the run has stopped. */
    private static final long LINGER_MS = 45_000;
    private static final long PREVIEW_MS = 30_000;
    private static final long LOAD_TIMEOUT_MS = 25_000;
    /** How many songs are tried, one after another, before giving up until the next change. */
    private static final int TRIES = 3;
    /** After this many failures in a row nothing more is tried for {@link #REST_MS}. */
    private static final int MOST_FAILURES = 6;
    private static final long REST_MS = 10 * 60_000L;
    /** A YouTube stream address is good for a few hours; it is not trusted for longer than this. */
    private static final long STREAM_KEEP_MS = 90 * 60_000L;
    /** The found lists are searched afresh after this long, so the songs change from day to day. */
    private static final long FOUND_KEEP_MS = 20 * 60 * 60_000L;

    /** One player and the loudness it is heading for. */
    private static final class Voice {
        final ExoPlayer player;
        final SongBook.Song song;
        final MusicMood.Mood mood;
        float volume;
        float target = 1f;
        boolean started;
        /** How many times this song was started again after its stream address went stale. */
        int renewed;

        Voice(ExoPlayer player, SongBook.Song song, MusicMood.Mood mood) {
            this.player = player;
            this.song = song;
            this.mood = mood;
        }
    }

    /** A looked-up stream and when it was looked up. */
    private static final class Kept {
        final YouTubeAudio.Stream stream;
        final long at;

        Kept(YouTubeAudio.Stream stream, long at) {
            this.stream = stream;
            this.at = at;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService NET = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "sudha-music");
        thread.setDaemon(true);
        return thread;
    });
    private static final List<Voice> VOICES = new ArrayList<>();
    private static final Map<String, Kept> STREAMS = new HashMap<>();
    /** The song chosen ahead of time for each mood, so a change of mood is quick. */
    private static final Map<MusicMood.Mood, SongBook.Song> UP_NEXT = new HashMap<>();
    private static Voice front;
    private static MusicMood.Mood playing = MusicMood.Mood.SILENT;
    private static boolean ticking;
    private static volatile int generation;
    /** Songs that failed one after another; a run of them means YouTube or the network is down. */
    private static int failuresInRow;
    private static long restUntil;
    private static volatile String problem = "";
    private static volatile String finding = "";

    private MusicEngine() {
    }

    // ------------------------------------------------------------------ calls

    /** Carries out what {@link MusicMood} decided. Safe from any thread. */
    static void step(Context context, MusicMood.Step step) {
        if (step == null || step == MusicMood.Step.NONE) return;
        final Context app = context.getApplicationContext();
        MAIN.post(() -> {
            try {
                MAIN.removeCallbacks(LINGER_OVER);
                switch (step) {
                    case HAPPY:
                        play(app, MusicMood.Mood.HAPPY);
                        break;
                    case SAD:
                        play(app, MusicMood.Mood.SAD);
                        break;
                    case SAD_THEN_QUIET:
                        play(app, MusicMood.Mood.SAD);
                        MAIN.postDelayed(LINGER_OVER, LINGER_MS);
                        break;
                    case CHEER:
                        say(app, MusicMood.Mood.CHEER);
                        break;
                    case GLOOM:
                        say(app, MusicMood.Mood.GLOOM);
                        break;
                    default:
                        quiet();
                        break;
                }
            } catch (Throwable e) {
                Log.w(TAG, "Music skipped", e);
            }
        });
    }

    private static final Runnable LINGER_OVER = MusicEngine::quiet;

    /**
     * A run has begun: find the songs and look up the first one of each mood now, so that
     * the first order and the first error are answered with music at once.
     */
    static void warm(Context context) {
        final Context app = context.getApplicationContext();
        if (!FeedbackPrefs.isOn(app, FeedbackPrefs.KEY_MUSIC)) return;
        NET.execute(() -> {
            final boolean lines = linesOn(app);
            for (MusicMood.Mood mood : moods(lines)) {
                try {
                    if (!lines && SongBook.locked(app) && SongBook.lockedSong(app) != null) {
                        streamFor(SongBook.lockedSong(app));
                        return;
                    }
                    SongBook.Song ahead;
                    synchronized (UP_NEXT) {
                        ahead = UP_NEXT.get(mood);
                    }
                    if (ahead == null) {
                        ahead = choose(app, mood);
                        if (ahead == null) continue;
                        synchronized (UP_NEXT) {
                            UP_NEXT.put(mood, ahead);
                        }
                    }
                    streamFor(ahead);
                } catch (Throwable e) {
                    Log.w(TAG, "Music could not be made ready (" + mood + "): " + YouTubeAudio.reason(e));
                }
            }
        });
    }

    /** True while Auto-Dialogues is switched on. */
    private static boolean linesOn(Context app) {
        return FeedbackPrefs.linesOn(app);
    }

    /** The two moods that are in use: dialogues with Auto-Dialogues on, songs without. */
    private static MusicMood.Mood[] moods(boolean lines) {
        return lines ? new MusicMood.Mood[]{MusicMood.Mood.CHEER, MusicMood.Mood.GLOOM}
                : new MusicMood.Mood[]{MusicMood.Mood.HAPPY, MusicMood.Mood.SAD};
    }

    /** Fades everything out. */
    static void stop() {
        MAIN.post(MusicEngine::quiet);
    }

    /**
     * The mini player's Play button: an upbeat song now, whether or not the bot is working.
     * Songs follow one another until {@link #stop}; a run's orders and errors still change
     * the mood as usual.
     */
    static void playNow(Context context) {
        final Context app = context.getApplicationContext();
        MAIN.post(() -> {
            try {
                // Asked for by hand: try at once, even after a string of failures.
                restUntil = 0;
                failuresInRow = 0;
                MAIN.removeCallbacks(LINGER_OVER);
                if (linesOn(app)) {
                    // Auto-Dialogues: the button says one rousing dialogue.
                    say(app, MusicMood.Mood.CHEER);
                    return;
                }
                play(app, playing == MusicMood.Mood.SAD ? MusicMood.Mood.SAD : MusicMood.Mood.HAPPY);
            } catch (Throwable e) {
                Log.w(TAG, "Music skipped", e);
            }
        });
    }

    /** True while a song is being heard or looked for. */
    static boolean audible() {
        return front != null || playing != MusicMood.Mood.SILENT || !finding.isEmpty();
    }

    /** The title of the song being heard, or "". */
    static String nowPlaying() {
        Voice now = front;
        return now == null ? "" : now.song.title;
    }

    /** "Finding a song" while one is being looked for, or "". */
    static String finding() {
        return finding;
    }

    /** Why the last song could not be played, or "". */
    static String lastProblem() {
        return problem;
    }

    /**
     * Lock song. On: the song being heard stays - it is not swapped when the mood changes
     * and it starts again when it ends. Off: the songs follow the orders again.
     */
    static void setLocked(Context context, boolean on) {
        final Context app = context.getApplicationContext();
        Voice now = front;
        SongBook.lock(app, on, now == null ? null : now.song);
        MAIN.post(() -> {
            try {
                Voice heard = front;
                if (heard != null) {
                    heard.player.setRepeatMode(on ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
                    // Unlocked while the other mood was due: go to it now.
                    if (!on && playing != MusicMood.Mood.SILENT && heard.mood != playing) play(app, playing);
                }
            } catch (Throwable e) {
                Log.w(TAG, "Song lock skipped", e);
            }
        });
    }

    /** Plays one song now for half a minute, for the "try it" taps on the setup screen. */
    static void preview(Context context, SongBook.Song song) {
        final Context app = context.getApplicationContext();
        MAIN.post(() -> {
            try {
                // Not over the music of a run that is going on.
                if (playing != MusicMood.Mood.SILENT) return;
                MAIN.removeCallbacks(LINGER_OVER);
                MAIN.postDelayed(LINGER_OVER, PREVIEW_MS);
                begin(app, MusicMood.Mood.HAPPY, song, 1, true);
            } catch (Throwable e) {
                Log.w(TAG, "Music preview skipped", e);
            }
        });
    }

    /** Finds a song of this mood now and plays half a minute of it, for the try-out buttons. */
    static void sample(Context context, MusicMood.Mood mood) {
        final Context app = context.getApplicationContext();
        MAIN.post(() -> {
            try {
                // Not over the music of a run that is going on.
                if (playing != MusicMood.Mood.SILENT) return;
                restUntil = 0;
                failuresInRow = 0;
                MAIN.removeCallbacks(LINGER_OVER);
                MAIN.postDelayed(LINGER_OVER, PREVIEW_MS + LOAD_TIMEOUT_MS);
                begin(app, mood, null, TRIES, true);
            } catch (Throwable e) {
                Log.w(TAG, "Music try-out skipped", e);
            }
        });
    }

    // ----------------------------------------------------------------- inside

    private static void play(Context app, MusicMood.Mood mood) {
        if (!FeedbackPrefs.isOn(app, FeedbackPrefs.KEY_MUSIC)) {
            quiet();
            return;
        }
        playing = mood;
        boolean locked = SongBook.locked(app);
        if (locked && front != null) {
            // The locked song stays whatever happens.
            if (SongBook.lockedSong(app) == null) SongBook.lock(app, true, front.song);
            front.player.setRepeatMode(Player.REPEAT_MODE_ONE);
            return;
        }
        // The right kind of song is already being heard (the lock was just taken off, say).
        if (!locked && front != null && front.mood == mood && front.started) return;
        // YouTube or the network is down: do not keep knocking (and using data) for a while.
        if (System.currentTimeMillis() < restUntil) return;
        begin(app, mood, locked ? SongBook.lockedSong(app) : null, TRIES, false);
    }

    /** The dialogue asked for last and when, so that the same one is not asked for twice while it loads. */
    private static MusicMood.Mood lineAsked = MusicMood.Mood.SILENT;
    private static long lineAskedAt;

    /**
     * Auto-Dialogues: says one film dialogue of this mood, once. A dialogue that is being
     * heard is not cut short by good news; bad news cuts a cheer ({@link MusicMood#keepsLine}).
     */
    private static void say(Context app, MusicMood.Mood mood) {
        if (!FeedbackPrefs.isOn(app, FeedbackPrefs.KEY_MUSIC)) {
            quiet();
            return;
        }
        long now = System.currentTimeMillis();
        Voice heard = front;
        if (heard != null && MusicMood.keepsLine(heard.mood, mood)) return;
        // The same kind was asked for a moment ago and is still being fetched.
        if (heard == null && MusicMood.keepsLine(lineAsked, mood) && now >= lineAskedAt
                && now - lineAskedAt < LOAD_TIMEOUT_MS && !finding.isEmpty()) return;
        // YouTube or the network is down: do not keep knocking for a while.
        if (now < restUntil) return;
        lineAsked = mood;
        lineAskedAt = now;
        // Whatever song was on gives way; a dialogue is not followed by a song.
        playing = MusicMood.Mood.SILENT;
        begin(app, mood, null, 2, false);
    }

    /**
     * Chooses a song (unless one is given), looks up its stream off the main thread, then
     * cross-fades to it once it has started.
     */
    private static void begin(Context app, MusicMood.Mood mood, SongBook.Song given, int tries, boolean preview) {
        final int mine = ++generation;
        NET.execute(() -> {
            SongBook.Song song = given;
            try {
                if (song == null) {
                    synchronized (UP_NEXT) {
                        song = UP_NEXT.remove(mood);
                    }
                }
                if (song == null) {
                    finding = "Finding a song";
                    song = choose(app, mood);
                }
                if (song == null) {
                    finding = "";
                    problem = "YouTube answered with no song for this mood";
                    return;
                }
                if (mine != generation) {
                    // Overtaken by a newer request: keep the choice for later.
                    if (given == null) {
                        synchronized (UP_NEXT) {
                            UP_NEXT.put(mood, song);
                        }
                    }
                    finding = "";
                    return;
                }
                finding = "Loading " + song.title;
                final YouTubeAudio.Stream stream = streamFor(song);
                final SongBook.Song chosen = song;
                MAIN.post(() -> {
                    finding = "";
                    if (mine != generation) return;
                    start(app, chosen, stream, mood, tries, mine, preview);
                });
                // While this one plays, get the next one of each mood ready.
                if (!preview) ahead(app);
            } catch (Throwable e) {
                finding = "";
                final SongBook.Song broken = song;
                final String why = YouTubeAudio.reason(e);
                // YouTube itself refused this one song (removed, private, not for this
                // country): it is not offered again. A network failure says nothing about the song.
                final boolean refused = e instanceof org.schabi.newpipe.extractor.exceptions.ExtractionException
                        && !(e instanceof org.schabi.newpipe.extractor.exceptions.ReCaptchaException);
                MAIN.post(() -> failed(app, broken, mood, tries, mine, why, preview, refused));
            }
        });
    }

    /** Off the main thread: the next song for this mood, searching YouTube when the list is empty or old. */
    private static SongBook.Song choose(Context app, MusicMood.Mood mood) throws Exception {
        boolean own = !SongBook.list(app, mood).isEmpty();
        if (!own) {
            long now = System.currentTimeMillis();
            long at = SongBook.foundAt(app, mood);
            boolean empty = SongBook.found(app, mood).isEmpty();
            if (empty || now - at > FOUND_KEEP_MS || at > now) {
                try {
                    refill(app, mood, now);
                } catch (Exception e) {
                    // Yesterday's list is better than silence.
                    if (empty) throw e;
                }
            }
        }
        return SongBook.next(app, mood);
    }

    private static void refill(Context app, MusicMood.Mood mood, long now) throws Exception {
        String[] lines = MusicMood.searches(mood);
        Exception last = null;
        // One line of search words per round; a line that answers nothing passes its turn.
        for (int round = 0; round < 2; round++) {
            String words = lines[SongBook.nextSearch(app, mood, lines.length)];
            try {
                List<SongBook.Song> songs = YouTubeAudio.search(words, MusicMood.isLine(mood));
                if (!songs.isEmpty()) {
                    java.util.Collections.shuffle(songs);
                    SongBook.setFound(app, mood, songs, now);
                    Log.i(TAG, "Music: \"" + words + "\" gave " + songs.size() + " songs");
                    return;
                }
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null) throw last;
        throw new java.io.IOException("YouTube answered with no song");
    }

    /** Off the main thread: the stream for a song (a fresh look-up for YouTube; null for anything else). */
    private static YouTubeAudio.Stream streamFor(SongBook.Song song) throws Exception {
        if (MusicMood.source(song.address) != MusicMood.Source.YOUTUBE) return null;
        String page = MusicMood.youTubePage(song.address);
        if (page == null) throw new java.io.IOException("not a link to one YouTube song");
        long now = System.currentTimeMillis();
        synchronized (STREAMS) {
            Kept kept = STREAMS.get(page);
            if (kept != null && now - kept.at < STREAM_KEEP_MS && kept.at <= now) return kept.stream;
        }
        YouTubeAudio.Stream stream = YouTubeAudio.resolve(page);
        synchronized (STREAMS) {
            if (STREAMS.size() > 40) STREAMS.clear();
            STREAMS.put(page, new Kept(stream, now));
        }
        return stream;
    }

    /** Off the main thread, best effort: chooses and looks up the next song of each mood. */
    private static void ahead(Context app) {
        for (MusicMood.Mood mood : moods(linesOn(app))) {
            try {
                SongBook.Song next;
                synchronized (UP_NEXT) {
                    next = UP_NEXT.get(mood);
                }
                if (next == null) {
                    next = choose(app, mood);
                    if (next == null) continue;
                    synchronized (UP_NEXT) {
                        UP_NEXT.put(mood, next);
                    }
                }
                streamFor(next);
            } catch (Throwable e) {
                Log.w(TAG, "Music: next " + mood + " song not ready: " + YouTubeAudio.reason(e));
            }
        }
    }

    /** Main thread: builds a player for the song and starts it silently; the fade begins when it is ready. */
    private static void start(Context app, SongBook.Song song, YouTubeAudio.Stream stream,
                              MusicMood.Mood mood, int tries, int mine, boolean preview) {
        Voice made = null;
        try {
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setUserAgent(stream == null ? YouTubeAudio.BROWSER : stream.userAgent)
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(20_000);
            ExoPlayer player = new ExoPlayer.Builder(app)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(
                            new DefaultDataSource.Factory(app, http)))
                    .build();
            final Voice voice = new Voice(player, song, mood);
            made = voice;
            // Sound only: the picture track is never chosen, so it is never downloaded.
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build());
            // Music, without taking the "audio focus": the Sudha app is not interrupted.
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false);
            player.setVolume(0f);
            final boolean line = MusicMood.isLine(mood);
            player.setRepeatMode(SongBook.locked(app) && !preview && !line
                    ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
            MediaItem item;
            if (stream == null) {
                item = MediaItem.fromUri(song.address);
            } else if (stream.hls) {
                item = new MediaItem.Builder().setUri(stream.url)
                        .setMimeType(MimeTypes.APPLICATION_M3U8).build();
            } else {
                item = MediaItem.fromUri(stream.url);
            }
            final Runnable gaveUp = () -> {
                if (!voice.started) failed(app, voice, tries, mine, "took too long to load", preview);
            };
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    try {
                        if (state == Player.STATE_READY && !voice.started) {
                            MAIN.removeCallbacks(gaveUp);
                            if (mine != generation) {
                                release(voice);
                                return;
                            }
                            voice.started = true;
                            failuresInRow = 0;
                            for (Voice other : VOICES) other.target = 0f;
                            VOICES.add(voice);
                            front = voice;
                            if (!preview) playing = mood;
                            problem = "";
                            if (line) {
                                // A dialogue is heard from its first word, and not for long.
                                voice.volume = 1f;
                                MAIN.postDelayed(() -> {
                                    if (front == voice) quiet();
                                }, MusicMood.LINE_PLAY_MS);
                            }
                            if (!preview && !line && SongBook.locked(app) && SongBook.lockedSong(app) == null) {
                                SongBook.lock(app, true, song);
                                player.setRepeatMode(Player.REPEAT_MODE_ONE);
                            }
                            tick();
                        } else if (state == Player.STATE_ENDED) {
                            ended(app, voice);
                        }
                    } catch (Throwable e) {
                        Log.w(TAG, "Music skipped", e);
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    MAIN.removeCallbacks(gaveUp);
                    String why = "could not be played (" + error.getErrorCodeName() + ")";
                    if (voice.started) broke(app, voice, why);
                    else failed(app, voice, tries, mine, why, preview);
                }
            });
            player.setMediaItem(item);
            player.setPlayWhenReady(true);
            player.prepare();
            MAIN.postDelayed(gaveUp, LOAD_TIMEOUT_MS);
        } catch (Throwable e) {
            Log.w(TAG, "Music: player could not be made", e);
            if (made != null) failed(app, made, tries, mine, "could not be opened", preview);
            else failed(app, song, mood, tries, mine, "could not be opened", preview, false);
        }
    }

    private static void failed(Context app, Voice voice, int tries, int mine, String why, boolean preview) {
        VOICES.remove(voice);
        if (front == voice) front = null;
        release(voice);
        failed(app, voice.song, voice.mood, tries, mine, why, preview, false);
    }

    /**
     * A song that will not play is skipped and the next one is tried, a few times over.
     *
     * @param refused true when YouTube refused this very song; it is then taken off the found list
     */
    private static void failed(Context app, SongBook.Song song, MusicMood.Mood mood, int tries, int mine,
                               String why, boolean preview, boolean refused) {
        problem = song == null ? why : "\"" + song.title + "\" " + why;
        Log.w(TAG, "Song skipped: " + problem);
        if (song != null) {
            String page = MusicMood.youTubePage(song.address);
            if (page != null) {
                synchronized (STREAMS) {
                    STREAMS.remove(page);
                }
            }
            if (refused && !preview) SongBook.dropFound(app, mood, song.address);
        }
        if (mine != generation) return;
        if (++failuresInRow >= MOST_FAILURES) {
            failuresInRow = 0;
            restUntil = System.currentTimeMillis() + REST_MS;
            problem = problem + ". Music is resting for 10 minutes";
            Log.w(TAG, "Music: " + MOST_FAILURES + " songs failed in a row; resting");
            return;
        }
        if (tries <= 1) return;
        if (!preview && !MusicMood.isLine(mood) && SongBook.locked(app) && SongBook.lockedSong(app) != null) return;
        begin(app, mood, null, tries - 1, preview);
    }

    /** A song that stopped in the middle (the stream address went stale, the network dropped). */
    private static void broke(Context app, Voice voice, String why) {
        Log.w(TAG, "Song stopped: \"" + voice.song.title + "\" " + why);
        boolean wasFront = front == voice;
        VOICES.remove(voice);
        if (wasFront) front = null;
        release(voice);
        if (MusicMood.isLine(voice.mood)) {
            // A dialogue that broke off is not said again.
            if (wasFront) playing = MusicMood.Mood.SILENT;
            return;
        }
        if (!wasFront || playing == MusicMood.Mood.SILENT) return;
        String page = MusicMood.youTubePage(voice.song.address);
        if (page != null) {
            synchronized (STREAMS) {
                STREAMS.remove(page);
            }
        }
        if (SongBook.locked(app)) {
            // The locked song is the one wanted: look its stream up again and carry on.
            begin(app, playing, voice.song, 2, false);
        } else {
            begin(app, playing, null, TRIES, false);
        }
    }

    private static void ended(Context app, Voice voice) {
        try {
            // A locked song is on repeat and never gets here.
            if (voice != front) return;
            VOICES.remove(voice);
            front = null;
            release(voice);
            if (MusicMood.isLine(voice.mood)) {
                // Said once; then it is quiet until the next order or error.
                playing = MusicMood.Mood.SILENT;
                return;
            }
            if (playing != MusicMood.Mood.SILENT) play(app, playing);
        } catch (Throwable e) {
            Log.w(TAG, "Music skipped", e);
        }
    }

    private static void quiet() {
        generation++;
        playing = MusicMood.Mood.SILENT;
        front = null;
        finding = "";
        for (Voice voice : VOICES) voice.target = 0f;
        tick();
    }

    private static void release(Voice voice) {
        try {
            voice.player.release();
        } catch (Throwable ignored) {
        }
    }

    private static void tick() {
        if (ticking) return;
        ticking = true;
        MAIN.post(TICK);
    }

    /** Moves every loudness one step towards where it is heading; stops when all have arrived. */
    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            float pace = TICK_MS / (float) FADE_MS;
            boolean moving = false;
            for (int i = VOICES.size() - 1; i >= 0; i--) {
                Voice voice = VOICES.get(i);
                if (voice.volume != voice.target) {
                    voice.volume = voice.volume < voice.target
                            ? Math.min(voice.target, voice.volume + pace)
                            : Math.max(voice.target, voice.volume - pace);
                    moving = true;
                }
                try {
                    voice.player.setVolume(voice.volume);
                } catch (Throwable ignored) {
                }
                if (voice.target == 0f && voice.volume == 0f) {
                    VOICES.remove(i);
                    release(voice);
                }
            }
            if (moving) {
                MAIN.postDelayed(this, TICK_MS);
            } else {
                ticking = false;
            }
        }
    };
}
