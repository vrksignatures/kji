package com.vrksignatures.sudhaautobot;

import java.util.Locale;

/**
 * When the music changes, what is looked for on YouTube, and which links can be played at
 * all. Plain Java, no Android.
 *
 * <p>The rule, frame by frame as the floating panel is updated:</p>
 * <ul>
 *   <li>an order is placed: an upbeat song (the one already playing carries on);</li>
 *   <li>a retailer ends in an error: cross-fade to a sad song, until the next order is placed;</li>
 *   <li>the run stops on an error: a sad song for a short while, then quiet;</li>
 *   <li>paused, idle or complete: quiet.</li>
 * </ul>
 * The first frame after the panel appears never starts anything.
 *
 * <p><b>Auto-Dialogues</b> is the second way of working ({@link #nextLine}): no songs; a
 * rousing Hindi film dialogue when an order is placed, a sad or dramatic one when a
 * retailer ends in an error or the run stops. Each is heard once and then it is quiet.</p>
 */
public final class MusicMood {

    /** {@code CHEER} and {@code GLOOM} are film dialogues (Auto-Dialogues); the others are songs. */
    public enum Mood { SILENT, HAPPY, SAD, CHEER, GLOOM }

    /** What the player should do now. {@code CHEER} and {@code GLOOM}: say one film dialogue. */
    public enum Step { NONE, HAPPY, SAD, SAD_THEN_QUIET, QUIET, CHEER, GLOOM }

    /** True for the two dialogue moods. */
    public static boolean isLine(Mood mood) {
        return mood == Mood.CHEER || mood == Mood.GLOOM;
    }

    private boolean primed;
    private int success;
    private int errors;
    private Mood mood = Mood.SILENT;

    public Mood mood() {
        return mood;
    }

    public Step next(String state, boolean running, int successNow, int errorsNow) {
        boolean placed = primed && successNow > success;
        boolean failed = primed && errorsNow > errors;
        boolean first = !primed;
        primed = true;
        success = successNow;
        errors = errorsNow;
        if (first) return Step.NONE;

        if ("COMPLETE".equals(state)) return quiet();
        if ("ERROR".equals(state)) {
            if (mood == Mood.SAD) return Step.NONE;
            mood = Mood.SAD;
            return Step.SAD_THEN_QUIET;
        }
        if (!running) return quiet();
        if (failed) {
            if (mood == Mood.SAD) return Step.NONE;
            mood = Mood.SAD;
            return Step.SAD;
        }
        if (placed) {
            if (mood == Mood.HAPPY) return Step.NONE;
            mood = Mood.HAPPY;
            return Step.HAPPY;
        }
        return Step.NONE;
    }

    private Step quiet() {
        if (mood == Mood.SILENT) return Step.NONE;
        mood = Mood.SILENT;
        return Step.QUIET;
    }

    // --------------------------------------------------------- Auto-Dialogues

    private boolean stopShown;

    /**
     * The rule while Auto-Dialogues is on. An order placed: {@code CHEER}. A retailer that
     * ended in an error, or the run stopping on one: {@code GLOOM}. Anything else: nothing
     * (a dialogue ends by itself, so there is nothing to switch off). The same counts are
     * followed as in {@link #next}, so the two can be changed between in the middle of a run.
     */
    public Step nextLine(String state, boolean running, int successNow, int errorsNow) {
        boolean placed = primed && successNow > success;
        boolean failed = primed && errorsNow > errors;
        boolean stopNow = "ERROR".equals(state);
        boolean stopped = primed && stopNow && !stopShown;
        boolean first = !primed;
        primed = true;
        success = successNow;
        errors = errorsNow;
        stopShown = stopNow;
        // Songs, when they are switched back on, start from quiet.
        mood = Mood.SILENT;
        if (first) return Step.NONE;
        if (failed || stopped) return Step.GLOOM;
        if (placed) return Step.CHEER;
        return Step.NONE;
    }

    /**
     * What a dialogue that is asked for does to the one being heard: true = leave the one
     * being heard alone. Good news never cuts a dialogue short; bad news cuts a cheer.
     */
    public static boolean keepsLine(Mood heard, Mood asked) {
        if (!isLine(heard) || !isLine(asked)) return false;
        return !(asked == Mood.GLOOM && heard == Mood.CHEER);
    }

    // ------------------------------------------------------- what to look for

    /**
     * What is typed into YouTube's search, one line per round, for each mood. Hindi film
     * songs of the 1980s and 1990s: lively ones while orders go through, sad or tense ones
     * after an error. The app carries no song and no song address of its own; whatever
     * YouTube answers on the day is what is played.
     */
    private static final String[] HAPPY_SEARCHES = {
            "90s bollywood dance hits hindi songs",
            "80s hindi superhit upbeat songs kishore kumar",
            "govinda 90s hit songs",
            "bappi lahiri disco songs 80s hindi",
            "udit narayan alka yagnik 90s peppy songs",
            "90s hindi party songs",
    };
    private static final String[] SAD_SEARCHES = {
            "90s bollywood sad songs kumar sanu",
            "80s hindi sad songs kishore kumar",
            "90s hindi dard bhare geet",
            "bollywood suspense thriller songs 80s 90s hindi",
            "mohammed aziz sad songs 80s",
            "lata mangeshkar sad songs 80s hindi",
    };

    /**
     * Auto-Dialogues: what is typed into YouTube's search for a rousing film dialogue and
     * for a sad or dramatic one. Words only, as for the songs; which clips come back is
     * YouTube's answer on the day.
     */
    private static final String[] CHEER_SEARCHES = {
            "bollywood powerful dialogue short hindi",
            "amitabh bachchan famous dialogue short",
            "sunny deol dhai kilo ka haath dialogue",
            "sholay famous dialogue short",
            "shahrukh khan famous dialogue short hindi",
            "hindi movie josh dialogue short",
            "south movie hindi dubbed mass dialogue short",
    };
    private static final String[] GLOOM_SEARCHES = {
            "bollywood sad dialogue short hindi",
            "emotional hindi movie dialogue short",
            "devdas sad dialogue shahrukh khan",
            "dilip kumar emotional dialogue",
            "hindi film dramatic dialogue short",
            "tareekh pe tareekh dialogue sunny deol",
    };

    /** The search words for a mood. Never empty; {@code SILENT} is given the upbeat ones. */
    public static String[] searches(Mood mood) {
        switch (mood == null ? Mood.HAPPY : mood) {
            case SAD:
                return SAD_SEARCHES.clone();
            case CHEER:
                return CHEER_SEARCHES.clone();
            case GLOOM:
                return GLOOM_SEARCHES.clone();
            default:
                return HAPPY_SEARCHES.clone();
        }
    }

    /** A dialogue clip is this long; shorter is a sting, longer is a scene or a collection. */
    public static final long SHORTEST_LINE_S = 4;
    public static final long LONGEST_LINE_S = 75;
    /** A dialogue is heard for at most this long, then faded out. */
    public static final long LINE_PLAY_MS = 20_000;

    /** Words in a title that say "this is not one dialogue from a film". */
    private static final String[] NOT_ONE_LINE = {
            "song", "jukebox", "full movie", "reaction", "mashup", "remix", "trailer", "teaser",
            "interview", "karaoke", "mimicry", "spoof", "roast", "review", "explained",
            "lyric", "ringtone", "dj ", "tutorial", "how to", "top 10", "top 20", "top 50",
            "collection", "back to back", "all dialogues", "gameplay", "free fire", "pubg",
    };

    /**
     * True when a search result looks like one film dialogue: 4 to 75 seconds long, with
     * "dialogue" in its title (in English or Hindi letters), and not a song, a mimicry, a
     * reaction, a collection and so on.
     */
    public static boolean fitsLine(String title, long seconds) {
        if (seconds < SHORTEST_LINE_S || seconds > LONGEST_LINE_S) return false;
        String lower = (title == null ? "" : title).toLowerCase(Locale.ROOT) + " ";
        if (!lower.contains("dialog") && !lower.contains("डायलॉग") && !lower.contains("डायलाग")) return false;
        for (String word : NOT_ONE_LINE) if (lower.contains(word)) return false;
        return true;
    }

    /** A single song is this long, give or take. Shorter is a clip; longer is a compilation. */
    public static final long SHORTEST_SONG_S = 100;
    public static final long LONGEST_SONG_S = 9 * 60;

    /** Words in a title that say "this is not one song as it was recorded". */
    private static final String[] NOT_ONE_SONG = {
            "jukebox", "nonstop", "non-stop", "non stop", "mashup", "full album", "all songs",
            "top 10", "top 20", "top 50", "best of", "collection", "playlist", "full movie",
            "karaoke", "instrumental", "ringtone", "status", "slowed", "reverb", "lofi", "lo-fi",
            "8d audio", "dj ", "remix", "cover", "reaction", "trailer", "teaser", "scene",
            "interview", "making of",
    };

    /**
     * True when a search result looks like one song: between 1 min 40 s and 9 min long and
     * with a title that is not a compilation, a remix, a karaoke track and so on.
     *
     * @param seconds the length YouTube gives; 0 or less when it gives none (a live stream)
     */
    public static boolean fits(String title, long seconds) {
        if (seconds < SHORTEST_SONG_S || seconds > LONGEST_SONG_S) return false;
        String lower = (title == null ? "" : title).toLowerCase(Locale.ROOT) + " ";
        if (lower.trim().isEmpty()) return false;
        for (String word : NOT_ONE_SONG) if (lower.contains(word)) return false;
        return true;
    }

    /** The loudest stream the app will take: about 1 MB a minute. Above this it is wasted on a phone speaker. */
    public static final int MOST_KBPS = 140;

    /**
     * Which of a song's sound-only streams to play, given their sizes in kbps (0 or less
     * when unknown): the best one that is not above {@link #MOST_KBPS}; if all are above,
     * the smallest; -1 when there is none. Keeps the data used to about 1 MB a minute.
     */
    public static int bestStream(int[] kbps) {
        int best = -1;
        for (int i = 0; kbps != null && i < kbps.length; i++) {
            if (best < 0) {
                best = i;
                continue;
            }
            int have = kbps[best] <= 0 ? Integer.MAX_VALUE : kbps[best];
            int here = kbps[i] <= 0 ? Integer.MAX_VALUE : kbps[i];
            boolean haveFits = have <= MOST_KBPS;
            boolean hereFits = here <= MOST_KBPS;
            if (hereFits && (!haveFits || here > have)) best = i;
            else if (!hereFits && !haveFits && here < have) best = i;
        }
        return best;
    }

    // ------------------------------------------------------------------ links

    /** Where a song comes from. */
    public enum Source {
        /** A YouTube address: the sound-only stream behind it is looked up when it is played. */
        YOUTUBE,
        /** A direct https address of a sound file or an internet-radio stream. */
        DIRECT,
        /** A sound file picked on the phone. */
        FILE
    }

    public static Source source(String address) {
        String text = address == null ? "" : address.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return Source.FILE;
        return isYouTubeHost(host(lower)) ? Source.YOUTUBE : Source.DIRECT;
    }

    private static boolean isYouTubeHost(String host) {
        return host.equals("youtu.be") || host.equals("youtube.com") || host.endsWith(".youtube.com")
                || host.equals("youtube-nocookie.com") || host.endsWith(".youtube-nocookie.com");
    }

    /**
     * The eleven-character id of the video a YouTube link points at; null when the link is
     * not a YouTube link or names no single video (a channel, a playlist, the home page).
     */
    public static String youTubeId(String link) {
        String text = link == null ? "" : link.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return null;
        String host = host(lower);
        if (!isYouTubeHost(host)) return null;
        String rest = text.substring(text.indexOf("://") + 3);
        int slash = rest.indexOf('/');
        String path = slash < 0 ? "" : rest.substring(slash + 1);
        String query = "";
        int mark = path.indexOf('?');
        if (mark >= 0) {
            query = path.substring(mark + 1);
            path = path.substring(0, mark);
        }
        int hash = query.indexOf('#');
        if (hash >= 0) query = query.substring(0, hash);
        hash = path.indexOf('#');
        if (hash >= 0) path = path.substring(0, hash);
        String id = null;
        if (host.equals("youtu.be")) {
            id = firstPart(path);
        } else if (path.equals("watch") || path.startsWith("watch/")) {
            for (String pair : query.split("&")) {
                if (pair.startsWith("v=")) id = pair.substring(2);
            }
        } else {
            for (String kind : new String[]{"shorts/", "embed/", "live/", "v/"}) {
                if (path.startsWith(kind)) id = firstPart(path.substring(kind.length()));
            }
        }
        return id != null && id.matches("[A-Za-z0-9_-]{11}") ? id : null;
    }

    private static String firstPart(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(0, slash);
    }

    /** The plain address of a YouTube video, whatever form the link was pasted in; or null. */
    public static String youTubePage(String link) {
        String id = youTubeId(link);
        return id == null ? null : "https://www.youtube.com/watch?v=" + id;
    }

    /** Sites whose links open a web page or an app the bot cannot get a sound out of. */
    private static final String[] PAGES = {
            "spotify.com", "gaana.com", "jiosaavn.com", "saavn.com",
            "wynk.in", "music.apple.com", "music.amazon", "hungama.com", "soundcloud.com",
            "instagram.com", "facebook.com",
    };

    /**
     * Why a pasted link cannot be used as a song, in plain words; null when it can be tried.
     * Playable: a link to one YouTube video (its sound only is played), and a direct
     * {@code https} link to a sound file or an audio stream.
     */
    public static String problem(String link) {
        String text = link == null ? "" : link.trim();
        if (text.isEmpty()) return "Paste a link first.";
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://")) {
            return "Only https links are played. This one starts with http.";
        }
        if (!lower.startsWith("https://") || lower.length() < 12 || lower.contains(" ")) {
            return "That is not a link. It should start with https://";
        }
        String host = host(lower);
        if (isYouTubeHost(host)) {
            return youTubeId(text) != null ? null
                    : "That YouTube link is not one song (it is a channel, a playlist or a search). "
                    + "Open the song on YouTube, tap Share, copy the link and paste that.";
        }
        for (String page : PAGES) {
            if (host.equals(page) || host.endsWith("." + page) || host.contains(page)) {
                return "That link opens a page on " + page + ", not a sound file, and that site "
                        + "does not hand out a playable sound link. Use a YouTube link, a direct "
                        + "link to an mp3 / m4a / ogg file or an internet-radio stream, or pick a "
                        + "song file that is on this phone.";
            }
        }
        return null;
    }

    private static String host(String lowerLink) {
        String rest = lowerLink.substring(lowerLink.indexOf("://") + 3);
        int end = rest.length();
        for (char stop : new char[]{'/', '?', '#', ':'}) {
            int at = rest.indexOf(stop);
            if (at >= 0 && at < end) end = at;
        }
        return rest.substring(0, end);
    }

    /** A song title as it is shown: one line, no more than 60 characters. */
    public static String tidy(String title, String artist) {
        String name = title == null ? "" : title.replaceAll("\\s+", " ").trim();
        String by = artist == null ? "" : artist.replaceAll("\\s+", " ").trim();
        if (by.endsWith(" - Topic")) by = by.substring(0, by.length() - 8).trim();
        if (name.isEmpty()) name = "Song";
        if (!by.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(by.toLowerCase(Locale.ROOT))) {
            name = name + " - " + by;
        }
        return name.length() > 60 ? name.substring(0, 59).trim() + "…" : name;
    }

    /** A name for a link when none was given: the file name without its ending, or the site. */
    public static String title(String link) {
        String text = link == null ? "" : link.trim();
        int query = text.indexOf('?');
        if (query >= 0) text = text.substring(0, query);
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        String name = text.substring(text.lastIndexOf('/') + 1);
        if (name.isEmpty() || name.contains(":")) return "Song";
        int dot = name.lastIndexOf('.');
        if (dot > 0 && name.length() - dot <= 5) name = name.substring(0, dot);
        name = name.replace("%20", " ").replace('_', ' ').replace('+', ' ').trim();
        if (name.length() > 40) name = name.substring(0, 40).trim() + "…";
        return name.isEmpty() ? "Song" : name;
    }
}
