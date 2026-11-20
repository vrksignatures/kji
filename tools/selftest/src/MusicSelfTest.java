import com.vrksignatures.sudhaautobot.MusicMood;
import com.vrksignatures.sudhaautobot.MusicMood.Step;

import java.io.File;
import java.nio.file.Files;

/**
 * The music rule (when a song starts, changes and stops), what is looked for on YouTube,
 * which links are accepted as songs, and how the player (ExoPlayer, sound only) is wired
 * into the app. The streaming itself needs a phone and the internet and is not run here.
 *
 * The rule is fed the same four values the floating panel is given on every update.
 */
public final class MusicSelfTest {
    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        aRun();
        stops();
        links();
        whatIsLookedFor();
        titles();
        dialogues();
        wiring();
        System.out.println();
        System.out.println(failures == 0
                ? "ALL " + checks + " CHECKS PASSED"
                : failures + " OF " + checks + " CHECKS FAILED");
        if (failures != 0) System.exit(1);
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL  " + what);
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title);
    }

    private static void step(MusicMood rule, String state, boolean running, int success, int errors,
                             Step want, String what) {
        Step got = rule.next(state, running, success, errors);
        check(got == want, what + ": wanted " + want + ", got " + got);
    }

    private static void aRun() {
        section("a run: upbeat on an order, sad on an error, back again");
        MusicMood rule = new MusicMood();
        // The panel appears on a day that already has 10 orders and 1 error in the ledger.
        step(rule, "IDLE", false, 10, 1, Step.NONE, "the first frame never starts a song");
        step(rule, "IDLE", false, 10, 1, Step.NONE, "idle stays quiet");
        step(rule, "SCANNING_LIST", true, 10, 1, Step.NONE, "START alone starts no song");
        step(rule, "OPENING_RETAILER", true, 10, 1, Step.NONE, "working, nothing placed yet");
        step(rule, "SCANNING_LIST", true, 11, 1, Step.HAPPY, "an order is placed");
        check(rule.mood() == MusicMood.Mood.HAPPY, "the mood is happy");
        step(rule, "OPENING_RETAILER", true, 11, 1, Step.NONE, "the song carries on between orders");
        step(rule, "SCANNING_LIST", true, 12, 1, Step.NONE, "a second order does not restart the song");
        step(rule, "SCANNING_LIST", true, 12, 2, Step.SAD, "a retailer ends in an error");
        step(rule, "OPENING_RETAILER", true, 12, 2, Step.NONE, "the sad song carries on");
        step(rule, "SCANNING_LIST", true, 12, 3, Step.NONE, "a second error does not restart it");
        step(rule, "SCANNING_LIST", true, 13, 3, Step.HAPPY, "the next order brings the upbeat song back");
        // Skips ("Already created") change neither count: nothing happens.
        for (int i = 0; i < 20; i++) {
            step(rule, "SCANNING_LIST", true, 13, 3, Step.NONE, "skipped rows change nothing");
        }
        step(rule, "SCANNING_LIST", true, 14, 4, Step.SAD, "an order and an error in one frame: the error wins");
    }

    private static void stops() {
        section("pause, stop, finish");
        MusicMood rule = new MusicMood();
        rule.next("IDLE", false, 0, 0);
        step(rule, "SCANNING_LIST", true, 1, 0, Step.HAPPY, "playing");
        step(rule, "PAUSED", false, 1, 0, Step.QUIET, "pause fades the song out");
        step(rule, "PAUSED", false, 1, 0, Step.NONE, "and only once");
        step(rule, "SCANNING_LIST", true, 1, 0, Step.NONE, "resume waits for the next order");
        step(rule, "SCANNING_LIST", true, 2, 0, Step.HAPPY, "which starts it again");
        step(rule, "COMPLETE", false, 2, 0, Step.QUIET, "the list is done: the music fades out");
        step(rule, "COMPLETE", false, 2, 0, Step.NONE, "complete stays quiet");

        MusicMood stopped = new MusicMood();
        stopped.next("IDLE", false, 0, 0);
        stopped.next("SCANNING_LIST", true, 1, 0);
        step(stopped, "ERROR", false, 1, 0, Step.SAD_THEN_QUIET, "the run stops on an error");
        step(stopped, "ERROR", false, 1, 0, Step.NONE, "the sad song is not restarted");
        step(stopped, "IDLE", false, 1, 0, Step.QUIET, "after Reset: quiet");

        MusicMood silentStop = new MusicMood();
        silentStop.next("IDLE", false, 0, 0);
        step(silentStop, "ERROR", false, 0, 0, Step.SAD_THEN_QUIET, "a stop with no song playing still plays the sad one");

        MusicMood completeOrder = new MusicMood();
        completeOrder.next("SCANNING_LIST", true, 5, 0);
        step(completeOrder, "COMPLETE", false, 6, 0, Step.NONE, "the last order of the day starts no song as the run ends");
    }

    private static void links() {
        section("which links are accepted");
        // One YouTube video, in every form its link is shared in: accepted, and reduced to
        // the same plain address.
        String page = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
        String[] youTube = {
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "https://youtu.be/dQw4w9WgXcQ",
                "https://youtu.be/dQw4w9WgXcQ?si=AbCdEf123", "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
                "https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVM", "https://youtube.com/shorts/dQw4w9WgXcQ",
                "https://www.youtube.com/embed/dQw4w9WgXcQ", "https://www.youtube.com/live/dQw4w9WgXcQ?feature=share",
                "https://www.youtube.com/watch?list=PL123&v=dQw4w9WgXcQ&t=42s", "HTTPS://WWW.YOUTUBE.COM/watch?v=dQw4w9WgXcQ",
                "  https://youtu.be/dQw4w9WgXcQ  ",
        };
        for (String link : youTube) {
            check(MusicMood.problem(link) == null, "a YouTube song link is accepted: " + link);
            check(page.equals(MusicMood.youTubePage(link)), "and is the same song: " + link);
            check(MusicMood.source(link) == MusicMood.Source.YOUTUBE, "and is known to be YouTube: " + link);
        }
        // YouTube, but not one song.
        String[] notOneSong = {
                "https://www.youtube.com/", "https://www.youtube.com/@tseries",
                "https://www.youtube.com/playlist?list=PL123", "https://www.youtube.com/results?search_query=90s",
                "https://www.youtube.com/watch?v=short", "https://youtu.be/", "https://www.youtube.com/watch",
        };
        for (String link : notOneSong) {
            String why = MusicMood.problem(link);
            check(why != null && why.contains("not one song"), "refused, with the way out: " + link);
            check(MusicMood.youTubePage(link) == null, "and gives no address: " + link);
        }
        check(MusicMood.youTubeId("https://example.com/watch?v=dQw4w9WgXcQ") == null, "another site is not YouTube");
        check(MusicMood.youTubeId("https://notyoutube.com/watch?v=dQw4w9WgXcQ") == null, "nor is a look-alike name");
        check(MusicMood.youTubeId(null) == null && MusicMood.youTubeId("") == null, "nothing gives nothing");
        // Sites that hand out no sound at all.
        String[] refused = {
                "https://open.spotify.com/track/1", "https://gaana.com/song/x",
                "https://www.jiosaavn.com/song/x", "https://wynk.in/music/song/x",
                "https://music.apple.com/in/album/x", "https://music.amazon.in/albums/x",
                "https://www.hungama.com/song/x", "https://soundcloud.com/a/b",
                "https://www.instagram.com/reel/x", "https://www.facebook.com/watch?v=1",
        };
        for (String link : refused) {
            String why = MusicMood.problem(link);
            check(why != null && why.contains("not a sound file"), "refused with the reason: " + link);
        }
        check(MusicMood.problem("http://example.com/a.mp3") != null, "plain http is refused");
        check(MusicMood.problem("http://www.youtube.com/watch?v=dQw4w9WgXcQ") != null, "also for YouTube");
        check(MusicMood.problem("") != null && MusicMood.problem(null) != null, "empty is refused");
        check(MusicMood.problem("song.mp3") != null, "a bare file name is not a link");
        check(MusicMood.problem("https://a b.com/x.mp3") != null, "a link with a space is refused");
        check(MusicMood.problem("ftp://example.com/a.mp3") != null, "ftp is refused");
        String[] accepted = {
                "https://example.com/music/song.mp3", "https://cdn.example.org/a/b/track.m4a?x=1",
                "https://radio.example.in:8443/stream", "HTTPS://EXAMPLE.COM/SONG.OGG",
                "https://example.com/talk-about-youtube.mp3",
        };
        for (String link : accepted) {
            check(MusicMood.problem(link) == null, "accepted: " + link);
            check(MusicMood.source(link) == MusicMood.Source.DIRECT, "played as it is: " + link);
        }
        check(MusicMood.source("content://media/external/audio/media/42") == MusicMood.Source.FILE,
                "a file picked on the phone");
    }

    private static void whatIsLookedFor() {
        section("what is looked for on YouTube");
        for (MusicMood.Mood mood : new MusicMood.Mood[]{MusicMood.Mood.HAPPY, MusicMood.Mood.SAD}) {
            String[] lines = MusicMood.searches(mood);
            check(lines.length >= 4, mood + ": several different searches, for variety");
            java.util.Set<String> different = new java.util.HashSet<>(java.util.Arrays.asList(lines));
            check(different.size() == lines.length, mood + ": no search twice");
            for (String line : lines) {
                String lower = line.toLowerCase();
                check(lower.contains("80s") || lower.contains("90s"), "from the 80s / 90s: " + line);
                check(lower.contains("hindi") || lower.contains("bollywood") || lower.contains("govinda")
                        || lower.contains("kumar") || lower.contains("aziz") || lower.contains("udit"),
                        "Hindi film music: " + line);
                check(!line.contains("http") && !line.contains("watch?v="), "words, not an address: " + line);
            }
        }
        for (String line : MusicMood.searches(MusicMood.Mood.SAD)) {
            String lower = line.toLowerCase();
            check(lower.contains("sad") || lower.contains("dard") || lower.contains("suspense"),
                    "sad or tense: " + line);
        }
        for (String line : MusicMood.searches(MusicMood.Mood.HAPPY)) {
            check(!line.toLowerCase().contains("sad"), "not sad: " + line);
        }
        String[] lines = MusicMood.searches(MusicMood.Mood.HAPPY);
        lines[0] = "changed";
        check(!"changed".equals(MusicMood.searches(MusicMood.Mood.HAPPY)[0]), "the list cannot be altered from outside");

        // One song, not a compilation.
        check(MusicMood.fits("Tujhe Dekha To - Kumar Sanu", 302), "a five-minute song");
        check(MusicMood.fits("Ek Do Teen", 100) && MusicMood.fits("Long song", 540), "the shortest and the longest taken");
        check(!MusicMood.fits("Ek Do Teen", 99), "a clip is too short");
        check(!MusicMood.fits("Ek Do Teen", 541), "a compilation is too long");
        check(!MusicMood.fits("Live now", 0) && !MusicMood.fits("Live now", -1), "a live broadcast has no length");
        check(!MusicMood.fits("", 200) && !MusicMood.fits(null, 200), "no title, no song");
        String[] notASong = {"90s Hits JUKEBOX", "Nonstop Dance Songs", "Kumar Sanu Mashup", "Top 10 Sad Songs",
                "Tujhe Dekha To (Karaoke)", "Tujhe Dekha To Remix", "Tujhe Dekha To | Slowed + Reverb",
                "Tujhe Dekha To Lofi", "Best of Kishore Kumar", "DDLJ Full Movie", "Tujhe Dekha To Cover",
                "Tujhe Dekha To Instrumental", "Tujhe Dekha To WhatsApp Status", "DDLJ Trailer"};
        for (String title : notASong) check(!MusicMood.fits(title, 240), "not one song as recorded: " + title);

        // The sound stream: the best one that costs no more than about 1 MB a minute.
        check(MusicMood.bestStream(new int[]{48, 128, 160, 70}) == 1, "128 kbps out of 48 / 128 / 160 / 70");
        check(MusicMood.bestStream(new int[]{160, 256}) == 0, "all too big: the smallest");
        check(MusicMood.bestStream(new int[]{50}) == 0, "one stream: that one");
        check(MusicMood.bestStream(new int[]{-1, 128}) == 1, "a known size is preferred to an unknown one");
        check(MusicMood.bestStream(new int[]{-1}) == 0, "unknown is still a stream");
        check(MusicMood.bestStream(new int[]{}) == -1 && MusicMood.bestStream(null) == -1, "none: -1");
        check(MusicMood.bestStream(new int[]{140, 141}) == 0, "140 is still within the limit");

        check("Tujhe Dekha To - Kumar Sanu".equals(MusicMood.tidy("Tujhe  Dekha\nTo", "Kumar Sanu")), "title and artist on one line");
        check("Kumar Sanu Hits".equals(MusicMood.tidy("Kumar Sanu Hits", "kumar sanu")), "the artist is not said twice");
        check("Ek Do Teen - Alka Yagnik".equals(MusicMood.tidy("Ek Do Teen", "Alka Yagnik - Topic")), "YouTube's \"- Topic\" goes");
        check("Song".equals(MusicMood.tidy(null, null)) && "Song".equals(MusicMood.tidy(" ", "")), "nothing gives \"Song\"");
        check(MusicMood.tidy("x".repeat(100), "y").length() <= 60, "long titles are cut");
    }

    private static void titles() {
        section("a name for a song");
        check("Mere Sapno Ki Rani".equals(MusicMood.title("https://x.com/a/Mere_Sapno_Ki_Rani.mp3")),
                "underscores become spaces, the ending goes");
        check("my song".equals(MusicMood.title("https://x.com/my%20song.m4a?token=1")), "%20 and the query");
        check("stream".equals(MusicMood.title("https://radio.example.in/stream/")), "a stream");
        check("Song".equals(MusicMood.title("")) && "Song".equals(MusicMood.title(null)), "nothing gives \"Song\"");
        check("Tujhe Dekha To".equals(MusicMood.title("Tujhe Dekha To.mp3")),
                "a file name from the phone");
        check(MusicMood.title("https://x.com/" + "a".repeat(80) + ".mp3").length() <= 41, "long names are cut");
    }

    private static void line(MusicMood rule, String state, boolean running, int success, int errors,
                             Step want, String what) {
        Step got = rule.nextLine(state, running, success, errors);
        check(got == want, what + ": wanted " + want + ", got " + got);
    }

    /** Auto-Dialogues: a film dialogue for each outcome, in place of the songs. */
    private static void dialogues() throws Exception {
        section("Auto-Dialogues: a film dialogue for each order and each error");
        MusicMood rule = new MusicMood();
        line(rule, "IDLE", false, 0, 0, Step.NONE, "the panel appears: nothing");
        line(rule, "RETAILER_LIST", true, 0, 0, Step.NONE, "START: nothing yet");
        line(rule, "ORDER_FORM", true, 0, 0, Step.NONE, "working: nothing");
        line(rule, "RETAILER_LIST", true, 1, 0, Step.CHEER, "the first order is placed: a rousing dialogue");
        line(rule, "RETAILER_LIST", true, 1, 0, Step.NONE, "nothing new: nothing");
        line(rule, "RETAILER_LIST", true, 2, 0, Step.CHEER, "the second order: another one (each order, not only the first)");
        line(rule, "RETAILER_LIST", true, 3, 0, Step.CHEER, "the third: another");
        line(rule, "RETAILER_LIST", true, 3, 1, Step.GLOOM, "a retailer ends in an error: a sad one");
        line(rule, "RETAILER_LIST", true, 3, 2, Step.GLOOM, "another error: another sad one");
        line(rule, "RETAILER_LIST", true, 4, 2, Step.CHEER, "the next order: rousing again");
        line(rule, "RETAILER_LIST", true, 5, 3, Step.GLOOM, "an order and an error at once: the bad news is what is said");
        line(rule, "RETAILER_LIST", false, 5, 3, Step.NONE, "paused: nothing (a dialogue ends by itself)");
        line(rule, "RETAILER_LIST", true, 5, 3, Step.NONE, "started again: nothing until something happens");
        line(rule, "ERROR", false, 5, 3, Step.GLOOM, "the run stops on an error: a sad one");
        line(rule, "ERROR", false, 5, 3, Step.NONE, "and only once for that stop");
        line(rule, "RETAILER_LIST", true, 5, 3, Step.NONE, "started again after the stop: nothing");
        line(rule, "ERROR", false, 5, 3, Step.GLOOM, "a second stop: a sad one again");
        line(rule, "COMPLETE", false, 6, 3, Step.CHEER, "the last order and the list is complete: the last cheer is still said");
        line(rule, "COMPLETE", false, 6, 3, Step.NONE, "then nothing");
        check(rule.mood() == MusicMood.Mood.SILENT, "the song mood stays at quiet throughout");

        MusicMood fresh = new MusicMood();
        line(fresh, "RETAILER_LIST", true, 40, 2, Step.NONE, "the panel appears in the middle of a day's work: old orders are not cheered");
        line(fresh, "RETAILER_LIST", true, 41, 2, Step.CHEER, "the next one is");

        MusicMood mixed = new MusicMood();
        step(mixed, "RETAILER_LIST", true, 0, 0, Step.NONE, "songs first: nothing yet");
        step(mixed, "RETAILER_LIST", true, 1, 0, Step.HAPPY, "an order: an upbeat song");
        line(mixed, "RETAILER_LIST", true, 1, 0, Step.NONE, "Auto-Dialogues switched on in the middle of a run: nothing for the old order");
        line(mixed, "RETAILER_LIST", true, 2, 0, Step.CHEER, "the next order: a dialogue");
        step(mixed, "RETAILER_LIST", true, 2, 0, Step.NONE, "switched off again: nothing for the old order");
        step(mixed, "RETAILER_LIST", true, 3, 0, Step.HAPPY, "the next order: the songs start again from quiet");

        MusicMood.Mood cheer = MusicMood.Mood.CHEER;
        MusicMood.Mood gloom = MusicMood.Mood.GLOOM;
        check(MusicMood.isLine(cheer) && MusicMood.isLine(gloom) && !MusicMood.isLine(MusicMood.Mood.HAPPY)
                && !MusicMood.isLine(MusicMood.Mood.SAD) && !MusicMood.isLine(MusicMood.Mood.SILENT) && !MusicMood.isLine(null),
                "the two dialogue moods are told from the song moods");
        check(MusicMood.keepsLine(cheer, cheer), "a cheer being heard is not cut short by the next order");
        check(MusicMood.keepsLine(gloom, gloom), "nor a sad one by the next error");
        check(MusicMood.keepsLine(gloom, cheer), "a sad one is not cut short by good news");
        check(!MusicMood.keepsLine(cheer, gloom), "bad news does cut a cheer");
        check(!MusicMood.keepsLine(MusicMood.Mood.HAPPY, cheer) && !MusicMood.keepsLine(MusicMood.Mood.SAD, gloom)
                && !MusicMood.keepsLine(MusicMood.Mood.SILENT, cheer), "a song gives way to a dialogue");

        for (MusicMood.Mood mood : new MusicMood.Mood[]{cheer, gloom}) {
            String[] lines = MusicMood.searches(mood);
            check(lines.length >= 5, mood + ": several different searches, for variety");
            check(new java.util.HashSet<>(java.util.Arrays.asList(lines)).size() == lines.length, mood + ": no search twice");
            for (String words : lines) {
                String lower = words.toLowerCase();
                check(lower.contains("dialogue"), "a film dialogue is asked for: " + words);
                check(!lower.contains("song") && !lower.contains("jukebox"), "not a song: " + words);
                check(!words.contains("http") && !words.contains("watch?v="), "words, not an address: " + words);
            }
            for (String song : MusicMood.searches(mood == cheer ? MusicMood.Mood.HAPPY : MusicMood.Mood.SAD)) {
                check(!java.util.Arrays.asList(lines).contains(song), "the dialogue searches are not the song searches");
            }
        }
        for (String words : MusicMood.searches(gloom)) {
            String lower = words.toLowerCase();
            check(lower.contains("sad") || lower.contains("emotional") || lower.contains("dramatic")
                    || lower.contains("tareekh"), "sad or dramatic: " + words);
        }
        for (String words : MusicMood.searches(cheer)) {
            check(!words.toLowerCase().contains("sad") && !words.toLowerCase().contains("emotional"), "not sad: " + words);
        }

        check(MusicMood.fitsLine("Dhai Kilo Ka Haath | Sunny Deol Dialogue | Damini", 28), "a half-minute film dialogue");
        check(MusicMood.fitsLine("Best Dialogues - Sholay", 4) && MusicMood.fitsLine("Deewaar dialogue", 75),
                "the shortest and the longest taken");
        check(MusicMood.fitsLine("शोले का मशहूर डायलॉग", 20), "one titled in Hindi letters");
        check(!MusicMood.fitsLine("Deewaar dialogue", 3), "a sting is too short");
        check(!MusicMood.fitsLine("Deewaar dialogue", 76) && !MusicMood.fitsLine("Deewaar dialogue", 600),
                "a whole scene or a collection is too long");
        check(!MusicMood.fitsLine("Deewaar dialogue", 0) && !MusicMood.fitsLine("Deewaar dialogue", -1), "no length, not taken");
        check(!MusicMood.fitsLine("Mere Paas Maa Hai", 20), "a title that does not say it is a dialogue is not taken");
        check(!MusicMood.fitsLine("", 20) && !MusicMood.fitsLine(null, 20), "no title, not taken");
        for (String title : new String[]{"Sholay dialogue song remix", "Gabbar dialogue mimicry", "Dialogue reaction video",
                "Top 10 dialogues of Amitabh", "Dialogue DJ mix", "Pushpa dialogue spoof", "Movie dialogue ringtone",
                "Dialogue with lyrics", "Free Fire dialogue", "Back to back dialogues", "Dialogue trailer"}) {
            check(!MusicMood.fitsLine(title, 30), "not one film dialogue: " + title);
        }
        check(!MusicMood.fits("Deewaar dialogue", 30), "and a dialogue is never taken for a song (too short)");
        check(MusicMood.LINE_PLAY_MS == 20_000, "a dialogue is heard for at most 20 seconds");

        String app = "../../app/src/main/java/com/vrksignatures/sudhaautobot/";
        String engine = read(app + "MusicEngine.java");
        check(engine.contains("case CHEER:") && engine.contains("say(app, MusicMood.Mood.CHEER);")
                && engine.contains("case GLOOM:") && engine.contains("say(app, MusicMood.Mood.GLOOM);"),
                "the player says a dialogue for each of the two steps");
        String say = engine.substring(engine.indexOf("private static void say("), engine.indexOf("private static void begin("));
        check(say.contains("FeedbackPrefs.KEY_MUSIC") && say.contains("MusicMood.keepsLine(heard.mood, mood)")
                && say.contains("restUntil") && say.contains("playing = MusicMood.Mood.SILENT;"),
                "it obeys the music switch, does not cut a dialogue short, rests when YouTube is down, and is not followed by a song");
        check(engine.contains("voice.volume = 1f;") && engine.contains("MusicMood.LINE_PLAY_MS"),
                "a dialogue starts at full loudness and is faded out after its time");
        check(engine.contains("SongBook.locked(app) && !preview && !line"), "a dialogue is never put on repeat by Lock song");
        String ended = engine.substring(engine.indexOf("private static void ended("), engine.indexOf("private static void quiet("));
        check(ended.indexOf("MusicMood.isLine(voice.mood)") > 0 && ended.indexOf("MusicMood.isLine(voice.mood)") < ended.indexOf("play(app, playing)"),
                "when a dialogue ends nothing follows it");
        check(engine.contains("YouTubeAudio.search(words, MusicMood.isLine(mood))") && engine.contains("FeedbackPrefs.linesOn(app)"),
                "dialogues are looked for on YouTube like the songs, when the switch says so");
        String youTube = read(app + "YouTubeAudio.java");
        check(youTube.contains("MusicMood.fitsLine(") && youTube.contains("lines ? new String[]{\"videos\"}"),
                "what is a dialogue is decided by the tested rule; the song catalogue is not asked for dialogues");
        String book = read(app + "SongBook.java");
        check(book.contains("return \"cheer\";") && book.contains("return \"gloom\";"),
                "the dialogues found are kept apart from the songs found");
        String prefs = read(app + "FeedbackPrefs.java");
        check(prefs.contains("getBoolean(KEY_DIALOGUES, false)"), "Auto-Dialogues is off until it is switched on");
        String panel = read(app + "FloatingPanelController.java");
        check(panel.contains("FeedbackPrefs.linesOn(context)") && panel.contains("music.nextLine(lastState, running, success, errors)")
                && panel.contains(": music.next(lastState, running, success, errors);"),
                "the panel follows the dialogue rule while the switch is on and the song rule while it is off");
        String toggle = panel.substring(panel.indexOf("private void toggleLines()"), panel.indexOf("private final Runnable miniTick"));
        check(toggle.contains("FeedbackPrefs.set(context, FeedbackPrefs.KEY_DIALOGUES, on)") && toggle.contains("MusicEngine.stop()"),
                "the button on the dashboard switches it, and whatever was being heard stops");
        check(read("../../app/src/main/res/layout/floaty_layout.xml").contains("@+id/panel_lines"), "and has its place there");
        String setup = read(app + "MainActivity.java");
        check(setup.contains("R.string.toggle_lines") && setup.contains("trySong(MusicMood.Mood.CHEER)")
                && setup.contains("trySong(MusicMood.Mood.GLOOM)"), "the setup screen has the switch and a try-out for each kind");
        for (String file : new String[]{"SudhaAccessibilityService.java", "RetailerSequence.java", "DailyLedger.java"}) {
            String source = read(app + file);
            check(!source.contains("KEY_DIALOGUES") && !source.contains("nextLine(") && !source.contains("linesOn("),
                    file + " knows nothing of the dialogues");
        }
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), "UTF-8");
    }

    private static void wiring() throws Exception {
        section("wiring");
        String app = "../../app/src/main/java/com/vrksignatures/sudhaautobot/";
        String service = read(app + "SudhaAccessibilityService.java");
        check(!service.contains("MusicEngine") && !service.contains("SongBook") && !service.contains("MusicMood")
                        && !service.contains("YouTubeAudio") && !service.contains("media3"),
                "the bot's own code knows nothing of the music");
        String panel = read(app + "FloatingPanelController.java");
        check(panel.contains("MusicEngine.step("), "the panel feeds the music rule");
        check(panel.contains("MusicEngine.stop("), "hiding the panel stops the music");
        check(panel.contains("MusicEngine.warm("), "a run that begins gets the first songs ready");
        check(panel.contains("MusicEngine.setLocked(") && panel.contains("R.id.panel_song"),
                "Lock song is on the floating panel");
        check(read("../../app/src/main/res/layout/floaty_layout.xml").contains("@+id/panel_song"),
                "and has its button there");

        String engine = read(app + "MusicEngine.java");
        check(engine.contains("androidx.media3.exoplayer.ExoPlayer") && engine.contains("new ExoPlayer.Builder("),
                "the player is ExoPlayer");
        check(!engine.contains("android.media.MediaPlayer"), "and no longer the phone's MediaPlayer");
        check(engine.contains("setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)"),
                "sound only: the picture track is switched off");
        check(!engine.contains("PlayerView") && !engine.contains("SurfaceView") && !engine.contains("setVideoSurface"),
                "and there is nothing to draw a picture on");
        check(engine.contains("FeedbackPrefs.KEY_MUSIC"), "the player obeys its switch");
        check(engine.contains("Player.REPEAT_MODE_ONE") && engine.contains("SongBook.locked("),
                "a locked song is put on repeat");
        String play = engine.substring(engine.indexOf("private static void play("), engine.indexOf("private static void begin("));
        check(play.indexOf("locked && front != null") > 0 && play.indexOf("locked && front != null") < play.indexOf("begin("),
                "and is kept when the mood changes");
        check(engine.contains("FADE_MS") && engine.contains("other.target = 0f"), "songs cross-fade");
        check(engine.contains("NET.execute(") && engine.contains("MAIN.post("),
                "the internet is asked off the main thread, the players are touched on it");
        check(engine.contains("MOST_FAILURES") && engine.contains("REST_MS"),
                "when YouTube cannot be reached it is left alone for a while");

        String youTube = read(app + "YouTubeAudio.java");
        check(youTube.contains("org.schabi.newpipe.extractor"), "YouTube is asked through NewPipe Extractor");
        check(youTube.contains("getAudioStreams()") && !youTube.contains("getVideoStreams")
                        && !youTube.contains("getVideoOnlyStreams"),
                "only sound streams are asked for");
        check(youTube.contains("MusicMood.fits(") && youTube.contains("MusicMood.bestStream("),
                "what is a song and which stream is taken are decided by the tested rule");
        for (String file : new String[]{"MusicEngine.java", "YouTubeAudio.java", "SongBook.java", "MusicMood.java"}) {
            String source = read(app + file);
            check(!source.contains("watch?v=dQw") && !java.util.regex.Pattern.compile("watch\\?v=[A-Za-z0-9_-]{11}")
                    .matcher(source).find() && !source.contains("youtu.be/"),
                    file + " carries no song address of its own");
        }

        String gradle = read("../../app/build.gradle.kts");
        check(gradle.contains("androidx.media3:media3-exoplayer:"), "ExoPlayer is in the build");
        check(gradle.contains("NewPipeExtractor:"), "so is the YouTube look-up");
        check(gradle.contains("isCoreLibraryDesugaringEnabled = true") && gradle.contains("desugar_jdk_libs_nio"),
                "with what it needs on Android 7 to 12");
        check(read("../../settings.gradle.kts").contains("jitpack.io"), "and the place it is published");
        check(read("../../gradle.properties").contains("android.useAndroidX=true"), "AndroidX is on");
        check(read("../../app/src/main/AndroidManifest.xml").contains("android.permission.INTERNET"),
                "the app may use the internet");
        String rule = read(app + "MusicMood.java");
        check(!rule.contains("import android") && !rule.contains("import androidx") && !rule.contains("newpipe"),
                "the rule is plain Java");
    }
}
