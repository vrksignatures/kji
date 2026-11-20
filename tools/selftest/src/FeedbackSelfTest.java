import com.vrksignatures.sudhaautobot.ErrorPlaybook;
import com.vrksignatures.sudhaautobot.FeedbackCues;
import com.vrksignatures.sudhaautobot.FeedbackCues.Cue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * When does the bot show a flourish or put on a scene? And is the app silent while it does?
 *
 * Runs the real {@link FeedbackCues.Tracker} over the sequences of states the service draws
 * on the panel and counts what comes out. The rule under test is the one the person asked
 * for: milestones only - one cue per order placed, one when the run ends, one when it
 * stops - and silence for everything else, however often the panel is repainted.
 *
 * It also checks the promise the redesign was made under: the automation does not read any
 * of the feedback classes.
 */
public final class FeedbackSelfTest {

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        oneRetailer();
        wholeRun();
        repaintsAreSilent();
        showingThePanelAgainIsSilent();
        successScreenSeenTwice();
        ledgerReset();
        stopAndResume();
        runStart();
        wordsFitThePanel();
        playbookIsEmpty();
        automationDoesNotReadFeedback();

        if (failures > 0) {
            System.out.println(failures + " OF " + checks + " CHECKS FAILED");
            System.exit(1);
        }
        System.out.println("ALL " + checks + " CHECKS PASSED");
    }

    /** States for one retailer, as the service moves through them. */
    private static final String[] RETAILER = {
            "RETAILER_LIST", "DETAILS", "HISTORY", "COPY_ORDER", "COPY_ORDER", "COPY_ORDER",
            "PLACE_ORDER", "PAYMENT", "PAYMENT", "PAYMENT", "SUCCESS", "RETURNING_TO_LIST",
            "RETURNING_TO_LIST", "RETAILER_LIST"};

    private static int[] count(FeedbackCues.Tracker tracker, String[] states, int[] placedAt) {
        int[] seen = new int[Cue.values().length];
        for (int i = 0; i < states.length; i++) {
            seen[tracker.next(states[i], placedAt[i]).ordinal()]++;
        }
        return seen;
    }

    private static void oneRetailer() {
        FeedbackCues.Tracker tracker = new FeedbackCues.Tracker();
        tracker.next("IDLE", 0);
        int[] placed = new int[RETAILER.length];
        for (int i = 0; i < RETAILER.length; i++) placed[i] = i >= 10 ? 1 : 0;
        int[] seen = count(tracker, RETAILER, placed);
        check("one retailer: one cheer", seen[Cue.ORDER_PLACED.ordinal()] == 1);
        check("one retailer: nothing else", seen[Cue.RUN_COMPLETE.ordinal()] == 0
                && seen[Cue.RUN_STOPPED.ordinal()] == 0);
        check("one retailer: the run's start is marked once", seen[Cue.RUN_STARTED.ordinal()] == 1);
        check("one retailer: every other step silent",
                seen[Cue.NONE.ordinal()] == RETAILER.length - 2);
    }

    private static void wholeRun() {
        for (int retailers : new int[]{1, 7, 125}) {
            FeedbackCues.Tracker tracker = new FeedbackCues.Tracker();
            tracker.next("IDLE", 0);
            tracker.next("HOME", 0);
            int cheers = 0, ends = 0, stops = 0, steps = 0;
            int placed = 0;
            for (int r = 0; r < retailers; r++) {
                for (String state : RETAILER) {
                    if ("SUCCESS".equals(state)) placed++;
                    Cue cue = tracker.next(state, placed);
                    steps++;
                    if (cue == Cue.ORDER_PLACED) cheers++;
                    if (cue == Cue.RUN_COMPLETE) ends++;
                    if (cue == Cue.RUN_STOPPED) stops++;
                }
            }
            Cue last = tracker.next("COMPLETE", placed);
            check(retailers + " retailers: a cheer each", cheers == retailers);
            check(retailers + " retailers: the ending plays once", last == Cue.RUN_COMPLETE && ends == 0);
            check(retailers + " retailers: no stop", stops == 0);
            check(retailers + " retailers: " + steps + " steps, " + (steps - cheers) + " silent",
                    steps - cheers == retailers * (RETAILER.length - 1));
            check("the ending is not repeated by a repaint",
                    tracker.next("COMPLETE", placed) == Cue.NONE);
        }
    }

    private static void repaintsAreSilent() {
        for (String state : new String[]{"SUCCESS", "COMPLETE", "ERROR", "PAYMENT", "PAUSED"}) {
            FeedbackCues.Tracker tracker = new FeedbackCues.Tracker();
            tracker.next("HOME", 0);
            tracker.next(state, 1);
            boolean silent = true;
            for (int i = 0; i < 50; i++) silent &= tracker.next(state, 1) == Cue.NONE;
            check("repainting " + state + " fifty times is silent", silent);
        }
    }

    private static void showingThePanelAgainIsSilent() {
        for (String state : new String[]{"ERROR", "COMPLETE", "SUCCESS"}) {
            check("a panel that first appears in " + state + " says nothing",
                    new FeedbackCues.Tracker().next(state, 3) == Cue.NONE);
        }
        check("no previous state means no cue", FeedbackCues.on(null, "ERROR") == Cue.NONE);
        check("no next state means no cue", FeedbackCues.on("HOME", null) == Cue.NONE);
    }

    private static void successScreenSeenTwice() {
        FeedbackCues.Tracker tracker = new FeedbackCues.Tracker();
        tracker.next("PAYMENT", 4);
        check("first look at the success screen cheers", tracker.next("SUCCESS", 5) == Cue.ORDER_PLACED);
        tracker.next("RETURNING_TO_LIST", 5);
        check("second look at the same success screen does not",
                tracker.next("SUCCESS", 5) == Cue.NONE);
        tracker.next("RETURNING_TO_LIST", 5);
        tracker.next("PAYMENT", 5);
        check("the next order cheers again", tracker.next("SUCCESS", 6) == Cue.ORDER_PLACED);
    }

    private static void ledgerReset() {
        FeedbackCues.Tracker tracker = new FeedbackCues.Tracker();
        tracker.next("PAYMENT", 40);
        tracker.next("SUCCESS", 41);
        tracker.next("IDLE", 0);                      // Reset pressed: counts back to nought
        tracker.next("PAYMENT", 0);
        check("after a reset the first order cheers", tracker.next("SUCCESS", 1) == Cue.ORDER_PLACED);
    }

    private static void stopAndResume() {
        FeedbackCues.Tracker tracker = new FeedbackCues.Tracker();
        tracker.next("PAYMENT", 2);
        check("a stop plays once", tracker.next("ERROR", 2) == Cue.RUN_STOPPED);
        check("and not again", tracker.next("ERROR", 2) == Cue.NONE);
        check("pausing is silent", tracker.next("PAUSED", 2) == Cue.NONE);
        check("starting again is silent", tracker.next("HOME", 2) == Cue.NONE);
        check("a second stop plays again", tracker.next("ERROR", 2) == Cue.RUN_STOPPED);
        check("resting states", FeedbackCues.isResting("ERROR") && FeedbackCues.isResting("COMPLETE")
                && FeedbackCues.isResting("PAUSED") && FeedbackCues.isResting("IDLE")
                && !FeedbackCues.isResting("PAYMENT") && !FeedbackCues.isResting("SUCCESS"));
    }

    /** The welcome belongs to the start of a run, and to nothing else. */
    private static void runStart() {
        for (String from : new String[]{"IDLE", "COMPLETE", "ERROR"}) {
            for (String to : new String[]{"HOME", "PAYMENT"}) {
                FeedbackCues.Tracker tracker = new FeedbackCues.Tracker();
                tracker.next(from, 0);
                check(from + " → " + to + " starts a run", tracker.next(to, 0) == Cue.RUN_STARTED);
                check("and only once", tracker.next(to, 0) == Cue.NONE
                        && tracker.next("RETAILER_LIST", 0) == Cue.NONE);
            }
        }
        FeedbackCues.Tracker paused = new FeedbackCues.Tracker();
        paused.next("PAYMENT", 3);
        check("pausing is silent", paused.next("PAUSED", 3) == Cue.NONE);
        check("carrying on after a pause is not a new run", paused.next("HOME", 3) == Cue.NONE);
        FeedbackCues.Tracker first = new FeedbackCues.Tracker();
        check("the first frame is silent, whatever it shows", first.next("HOME", 0) == Cue.NONE);
        // Resting states among themselves never start anything.
        for (String from : new String[]{"IDLE", "COMPLETE", "ERROR", "PAUSED"}) {
            for (String to : new String[]{"IDLE", "PAUSED"}) {
                FeedbackCues.Tracker tracker = new FeedbackCues.Tracker();
                tracker.next(from, 0);
                check(from + " → " + to + " is silent", tracker.next(to, 0) == Cue.NONE);
            }
        }
        // A whole day: one start, however many retailers.
        FeedbackCues.Tracker day = new FeedbackCues.Tracker();
        day.next("IDLE", 0);
        int starts = 0, placed = 0;
        if (day.next("HOME", 0) == Cue.RUN_STARTED) starts++;
        for (int r = 0; r < 300; r++) {
            for (String state : RETAILER) {
                if ("SUCCESS".equals(state)) placed++;
                if (day.next(state, placed) == Cue.RUN_STARTED) starts++;
            }
        }
        check("300 retailers, one welcome", starts == 1);
    }

    /** The cheer is printed where the state name goes; it must not be wider than the widest name. */
    private static void wordsFitThePanel() {
        int widest = "RETURNING_TO_LIST".length();
        for (int turn = -3; turn < 12; turn++) {
            String words = FeedbackCues.orderWords(turn);
            check("\"" + words + "\" fits the state line",
                    words.codePointCount(0, words.length()) <= widest);
            check("victory words " + turn, FeedbackCues.victoryWords(turn).length == 2
                    && !FeedbackCues.victoryWords(turn)[0].isEmpty());
            check("drama words " + turn, FeedbackCues.dramaWords(turn).length == 2
                    && !FeedbackCues.dramaWords(turn)[0].isEmpty());
        }
        check("tally, all three", FeedbackCues.tally(118, 5, 2)
                .equals("118 placed  ·  5 skipped  ·  2 to check"));
        check("tally, clean run", FeedbackCues.tally(125, 0, 0).equals("125 placed"));
    }

    private static void playbookIsEmpty() {
        check("the playbook is an empty placeholder", ErrorPlaybook.size() == 0);
        check("an unnamed problem has no entry",
                ErrorPlaybook.lookup("In-flight order SMR2252 never reached a payment screen") == null);
        check("no message, no entry", ErrorPlaybook.lookup(null) == null);
    }

    /**
     * The run logic must not depend on anything added for looks or sound. If the service
     * (or any class it decides with) ever names one of these, this fails.
     */
    private static void automationDoesNotReadFeedback() throws Exception {
        String app = "../../app/src/main/java/com/vrksignatures/sudhaautobot/";
        String[] logic = {"SudhaAccessibilityService.java", "WorkflowStateMachine.java",
                "DailyLedger.java", "RetailerSequence.java", "ListScroller.java",
                "CalendarDateSelector.java", "CalendarGridFinder.java", "DateRollbackPolicy.java",
                "AccessibilityTree.java", "RetailerPolicy.java", "AppNotices.java", "FabFinder.java",
                // What Aakriti reads and learns with is held to the same rule.
                "Aakriti.java", "LearnedUi.java", "UiMolder.java", "UiVocabulary.java",
                "ScreenLabels.java", "AccountId.java", "Roster.java"};
        String[] looks = {"FeedbackCues", "FeedbackPrefs", "VeenaPlayer", "CinematicOverlay",
                "SceneView", "MagicUi", "AuroraView", "OrbGaugeView", "CustomMedia", "ErrorPlaybook",
                "VoicePlayer", "AakritiChat", "AakritiDesk",
                // The music and everything it is built on.
                "MusicEngine", "MusicMood", "SongBook", "YouTubeAudio", "media3", "exoplayer",
                "ExoPlayer", "newpipe"};
        for (String file : logic) {
            String source = new String(Files.readAllBytes(Paths.get(app + file)), StandardCharsets.UTF_8);
            for (String name : looks) {
                check(file + " does not use " + name, !source.contains(name));
            }
        }
        String panel = new String(Files.readAllBytes(
                Paths.get(app + "FloatingPanelController.java")), StandardCharsets.UTF_8);
        check("the panel is where the cues are raised", panel.contains("FeedbackCues.Tracker"));
        // The network is for the music and nothing else.
        java.io.File[] all = new java.io.File(app).listFiles();
        java.util.List<String> online = new java.util.ArrayList<>();
        for (java.io.File file : all == null ? new java.io.File[0] : all) {
            if (!file.getName().endsWith(".java")) continue;
            String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (source.contains("HttpURLConnection") || source.contains("HttpDataSource")
                    || source.contains("java.net.URL") || source.contains("java.net.Socket")) {
                online.add(file.getName());
            }
        }
        java.util.Collections.sort(online);
        check("the network is opened by the music classes only: " + online,
                online.equals(java.util.Arrays.asList("MusicEngine.java", "YouTubeAudio.java")));
        // Silent UI: nothing in the app speaks, chimes or clicks. (The songs are ExoPlayer's.)
        String[] noises = {"android.media.MediaPlayer", "SoundPool", "ToneGenerator", "TextToSpeech",
                "playSoundEffect", "RingtoneManager", "AudioTrack(", "R.raw."};
        for (java.io.File file : all == null ? new java.io.File[0] : all) {
            if (!file.getName().endsWith(".java")) continue;
            String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            for (String noise : noises) {
                check(file.getName() + " makes no sound with " + noise, !source.contains(noise));
            }
        }
        java.io.File clips = new java.io.File("../../app/src/main/res/raw");
        check("no sound clip is shipped", !clips.exists() || clips.list() == null || clips.list().length == 0);
        check("taps on the panel do not click", panel.contains("MagicUi.silence(root)"));
        String screen = new String(Files.readAllBytes(Paths.get(app + "MainActivity.java")), StandardCharsets.UTF_8);
        check("taps on the setup screen do not click", screen.contains("MagicUi.silence(scroll)"));
        String notifier = new String(Files.readAllBytes(Paths.get(app + "AakritiNotifier.java")), StandardCharsets.UTF_8);
        check("notifications make no sound", notifier.contains("channel.setSound(null, null)")
                && notifier.contains("enableVibration(false)"));
        check("no voice greeting when the app opens or a run starts",
                !screen.contains("welcome(") && !panel.contains("welcome("));

        // No maker's mark anywhere the person looks. The package name is the only place.
        java.util.List<java.io.File> shown = new java.util.ArrayList<>();
        collect(new java.io.File("../../app/src/main"), shown);
        for (java.io.File file : shown) {
            String name = file.getName();
            if (!name.endsWith(".java") && !name.endsWith(".xml")) continue;
            String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            check(name + " shows no maker's mark", !source.contains("VRKSignatures")
                    && !source.contains("@string/signature") && !source.contains("MagicUi.signature"));
        }

        for (String file : logic) {
            String source = new String(Files.readAllBytes(Paths.get(app + file)), StandardCharsets.UTF_8);
            check(file + " opens no connection", !source.contains("HttpURLConnection")
                    && !source.contains("java.net.URL") && !source.contains("Socket"));
        }
    }

    private static void collect(java.io.File folder, java.util.List<java.io.File> into) {
        java.io.File[] inside = folder.listFiles();
        if (inside == null) return;
        for (java.io.File one : inside) {
            if (one.isDirectory()) collect(one, into);
            else into.add(one);
        }
    }

    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + name);
        }
    }
}
