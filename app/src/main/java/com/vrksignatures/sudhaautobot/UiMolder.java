package com.vrksignatures.sudhaautobot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How Aakriti works out that the Sudha app has renamed something.
 *
 * <p>Given the screen the bot was expecting and the labels that are actually on screen, it
 * looks for each word the bot relies on there ({@link UiVocabulary}). For a word that has
 * gone missing it asks: is there one label that plainly means the same thing? "Submit
 * Order" for "Place Order", "Show Customers" for "Show Retailers", "Customer Info" for
 * "Customer Details".</p>
 *
 * <p>This is rule-based matching - word lists and spelling distance - that runs on the
 * phone in a millisecond. It is not a language model and it does not guess:</p>
 * <ul>
 *   <li>a new name is believed only on a screen that is still recognisably the right one
 *       by its <em>other</em> words (or when two renames on it agree with each other);</li>
 *   <li>the match must be strong and clearly better than the runner-up;</li>
 *   <li>a label with a word like <em>cancel</em>, <em>delete</em> or <em>back</em> in it is
 *       never taken for anything;</li>
 *   <li>for a button, the label must belong to something that can be pressed.</li>
 * </ul>
 * Whatever does not pass is not learned; it is written into the report for a person.
 */
public final class UiMolder {

    /** A match at least this strong is believed. */
    static final double CONFIDENT = 0.65;
    /** ... and it must beat the next best label by this much. */
    static final double MARGIN = 0.15;

    /** One label on the screen. */
    public static final class Label {
        public final String text;
        /** True when it, or something close around it, can be pressed. */
        public final boolean pressable;

        public Label(String text, boolean pressable) {
            this.text = text == null ? "" : text;
            this.pressable = pressable;
        }
    }

    public enum Status {
        /** The word the bot knows is on screen. Nothing to do. */
        PRESENT,
        /** Missing; a new name was found and may be used at once. */
        LEARNED,
        /** Missing; a new name was found but a person has to allow it. */
        ASK,
        /** Missing and nothing on screen can be trusted to be it. */
        MISSING
    }

    /** What became of one word. */
    public static final class Line {
        public final UiVocabulary.Slot slot;
        public final Status status;
        /** The label taken (LEARNED / ASK) or the closest one seen (MISSING); may be "". */
        public final String shown;
        public final double score;
        public final String why;

        Line(UiVocabulary.Slot slot, Status status, String shown, double score, String why) {
            this.slot = slot;
            this.status = status;
            this.shown = shown;
            this.score = score;
            this.why = why;
        }

        /** The new name as it is written in the notebook: the words, without any figures. */
        public String alias() {
            return phraseOf(LearnedUi.normalize(shown));
        }
    }

    public static final class Finding {
        /** The screen the bot was expecting, or null when its state expects none. */
        public final UiVocabulary.Screen screen;
        /** True when the labels really do look like that screen. */
        public final boolean recognised;
        public final List<Line> lines;

        Finding(UiVocabulary.Screen screen, boolean recognised, List<Line> lines) {
            this.screen = screen;
            this.recognised = recognised;
            this.lines = lines;
        }

        /** True when a new name was found for at least one word. */
        public boolean changed() {
            for (Line line : lines) {
                if (line.status == Status.LEARNED || line.status == Status.ASK) return true;
            }
            return false;
        }

        public List<Line> with(Status status) {
            List<Line> out = new ArrayList<>();
            for (Line line : lines) {
                if (line.status == status) out.add(line);
            }
            return out;
        }

        /** The finding in words, one line per word the bot relies on. */
        public String describe() {
            StringBuilder out = new StringBuilder();
            if (screen == null) {
                return "The bot was not on a screen whose words I keep track of.\n";
            }
            out.append("Expected screen: ").append(screen.title)
                    .append(recognised ? " - recognised by its other words\n"
                            : " - NOT recognised: too few of its usual words are here\n");
            for (Line line : lines) {
                String name = "\"" + line.slot.primary() + "\" (" + line.slot.says + ")";
                switch (line.status) {
                    case PRESENT:
                        out.append("  ok       ").append(name).append('\n');
                        break;
                    case LEARNED:
                        out.append("  LEARNED  ").append(name).append(" is now \"").append(line.shown)
                                .append("\" (").append(percent(line.score)).append(")\n");
                        break;
                    case ASK:
                        out.append("  ASKING   ").append(name).append(" looks like \"")
                                .append(line.shown).append("\" now (").append(percent(line.score))
                                .append(") - needs your OK\n");
                        break;
                    default:
                        out.append("  MISSING  ").append(name);
                        if (line.shown.isEmpty()) out.append(" - nothing on screen resembles it");
                        else {
                            out.append(" - closest is \"").append(line.shown).append("\" (")
                                    .append(percent(line.score)).append("), ").append(line.why);
                        }
                        out.append('\n');
                        break;
                }
            }
            return out.toString();
        }
    }

    private UiMolder() {
    }

    // ------------------------------------------------------------ the study

    /**
     * @param state  name of the bot state at its last look ({@code HOME}, {@code DETAILS}...)
     * @param labels everything printed on screen now
     */
    public static Finding study(String state, List<Label> labels) {
        UiVocabulary.Screen screen = UiVocabulary.screenForState(state);
        List<Line> lines = new ArrayList<>();
        if (screen == null || labels == null) return new Finding(screen, false, lines);

        // The screen as the bot reads it: lower case, with names already learned put back.
        StringBuilder joined = new StringBuilder();
        Map<String, Label> unique = new LinkedHashMap<>();
        for (Label label : labels) {
            String norm = LearnedUi.normalize(label.text);
            if (norm.isEmpty()) continue;
            joined.append(norm).append('\n');
            Label earlier = unique.get(norm);
            if (earlier == null || (label.pressable && !earlier.pressable)) unique.put(norm, label);
        }
        String text = LearnedUi.translate(joined.toString());
        boolean retailerRows = AccountId.retailerIds(text).size() >= 3;

        for (String other : screen.excludes) {
            if (has(text, other, retailerRows)) {
                // This is plainly a different screen; nothing on it is a rename of ours.
                for (UiVocabulary.Slot slot : UiVocabulary.slotsOf(screen.key)) {
                    lines.add(new Line(slot, present(slot, text, unique) ? Status.PRESENT : Status.MISSING,
                            "", 0, ""));
                }
                return new Finding(screen, false, lines);
            }
        }

        List<UiVocabulary.Slot> slots = UiVocabulary.slotsOf(screen.key);
        List<Line> found = new ArrayList<>();
        int confident = 0;
        for (UiVocabulary.Slot slot : slots) {
            if (present(slot, text, unique)) {
                found.add(new Line(slot, Status.PRESENT, "", 1, ""));
                continue;
            }
            Line best = closest(slot, unique);
            if (best.status != Status.MISSING) confident++;
            found.add(best);
        }

        // Two slots must not claim the same label.
        Map<String, Integer> claims = new HashMap<>();
        for (Line line : found) {
            if (line.status == Status.LEARNED || line.status == Status.ASK) {
                claims.merge(line.alias(), 1, Integer::sum);
            }
        }

        boolean anyRecognised = false;
        for (Line line : found) {
            if (line.status != Status.LEARNED && line.status != Status.ASK) {
                lines.add(line);
                continue;
            }
            if (claims.get(line.alias()) > 1) {
                lines.add(new Line(line.slot, Status.MISSING, line.shown, line.score,
                        "but the same label also fits another word, so neither is trusted"));
                confident--;
                continue;
            }
            lines.add(line);
        }
        // Is this really the expected screen? Count its usual words, leaving out the word
        // under question; failing that, two renames that agree with each other will do.
        List<Line> settled = new ArrayList<>();
        for (Line line : lines) {
            if (line.status != Status.LEARNED && line.status != Status.ASK) {
                settled.add(line);
                continue;
            }
            int anchors = 0;
            for (String anchor : screen.anchors) {
                if (line.slot.accepts(anchor)) continue;
                if (has(text, anchor, retailerRows)) anchors++;
            }
            boolean believed = anchors >= screen.needAnchors || confident >= 2;
            if (believed) {
                anyRecognised = true;
                settled.add(line);
            } else {
                settled.add(new Line(line.slot, Status.MISSING, line.shown, line.score,
                        "but too little else on this screen says it is the " + screen.title
                                + " screen, so it is not trusted"));
            }
        }
        if (!anyRecognised) {
            int anchors = 0;
            for (String anchor : screen.anchors) {
                if (has(text, anchor, retailerRows)) anchors++;
            }
            anyRecognised = anchors >= screen.needAnchors;
        }
        return new Finding(screen, anyRecognised, settled);
    }

    /**
     * As {@link #study}, for a stop where the bot's state does not say which screen it is
     * looking at - "unrecognised screen" happens while it is still IDLE, or walking back to
     * the list. Every screen is tried; a rename is believed only if exactly one screen
     * explains what is showing.
     */
    public static Finding studyAny(String state, List<Label> labels) {
        Finding expected = study(state, labels);
        if (expected.changed() || labels == null) return expected;
        Finding only = null;
        int explained = 0;
        for (UiVocabulary.Screen screen : UiVocabulary.SCREENS) {
            if (expected.screen == screen) continue;
            Finding other = study(screen.states[0], labels);
            if (other.recognised && other.changed()) {
                explained++;
                only = other;
            }
        }
        return explained == 1 ? only : expected;
    }

    private static boolean has(String text, String phrase, boolean retailerRows) {
        if (UiVocabulary.ANCHOR_RETAILER_ROWS.equals(phrase)) return retailerRows;
        return text.contains(phrase);
    }

    /** Whether the bot would find this word on the screen as it stands. */
    private static boolean present(UiVocabulary.Slot slot, String text, Map<String, Label> labels) {
        if (slot.role == UiVocabulary.Role.MARKER) {
            for (String phrase : slot.accepts) {
                if (text.contains(phrase)) return true;
            }
            return false;
        }
        for (Map.Entry<String, Label> entry : labels.entrySet()) {
            String norm = entry.getKey();
            for (String phrase : slot.accepts) {
                if (norm.equals(phrase) || LearnedUi.same(norm, phrase)) return true;
            }
        }
        // The bot's second try: a pressable label that contains the word as words of its
        // own ("Copy Order >").
        for (Map.Entry<String, Label> entry : labels.entrySet()) {
            if (!entry.getValue().pressable) continue;
            String padded = " " + LearnedUi.translate(entry.getKey()).replaceAll("[^a-z0-9]+", " ").trim() + " ";
            for (String phrase : slot.accepts) {
                if (phrase.length() >= 4 && padded.contains(" " + phrase + " ")
                        && words(padded).size() <= words(phrase).size() + 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Line closest(UiVocabulary.Slot slot, Map<String, Label> labels) {
        String bestShown = "";
        double best = 0;
        double second = 0;
        // "Order booked on 16-11" and "Order booked on 15-11" are one name seen twice.
        Set<String> names = new HashSet<>();
        for (Map.Entry<String, Label> entry : labels.entrySet()) {
            Label label = entry.getValue();
            String name = phraseOf(entry.getKey());
            if (!candidate(name)) continue;
            if (slot.role == UiVocabulary.Role.MARKER && !names.add(name)) continue;
            if (slot.role == UiVocabulary.Role.BUTTON && !label.pressable) continue;
            double score = 0;
            for (String phrase : slot.accepts) score = Math.max(score, similarity(name, phrase));
            if (score > best) {
                second = best;
                best = score;
                bestShown = label.text.trim();
            } else if (score > second) {
                second = score;
            }
        }
        if (best < CONFIDENT) {
            return new Line(slot, Status.MISSING, best >= 0.3 ? bestShown : "", best,
                    "not close enough to trust");
        }
        if (best - second < MARGIN) {
            return new Line(slot, Status.MISSING, bestShown, best,
                    "but another label is nearly as close, so neither is trusted");
        }
        return new Line(slot, slot.trust == UiVocabulary.Trust.AUTO ? Status.LEARNED : Status.ASK,
                bestShown, best, "");
    }

    /**
     * The naming part of a label: its words up to the first figure, so that
     * "Order placed on 17-11-2026" is "order placed on" and "Total Parties: 125" is
     * "total parties". A new name is always written down in this form - the figures change
     * from day to day and from retailer to retailer.
     */
    static String phraseOf(String norm) {
        StringBuilder out = new StringBuilder();
        for (String token : norm.split(" ")) {
            if (token.matches(".*[0-9].*")) break;
            String word = token.replaceAll("^[^a-z]+|[^a-z]+$", "");
            if (word.isEmpty()) {
                if (out.length() > 0) break;
                continue;
            }
            if (out.length() > 0) out.append(' ');
            out.append(word);
        }
        return out.length() == 0 ? norm : out.toString();
    }

    /** Whether a label's naming part could be a new name for anything at all. */
    static boolean candidate(String name) {
        if (name.length() < 2 || name.length() > 40) return false;
        if (UiVocabulary.isKnown(name) || LearnedUi.knows(name)) return false;
        List<String> words = words(name);
        if (words.isEmpty() || words.size() > 5) return false;
        for (String word : name.replaceAll("[^a-z0-9]+", " ").trim().split(" ")) {
            if (NEGATIVE.contains(word)) return false;
            // An account id is a row of the list, never the name of a button.
            if (AccountId.kindOf(word) != AccountId.Kind.OTHER) return false;
        }
        return true;
    }

    // ------------------------------------------------------------ similarity

    private static final Set<String> NEGATIVE = new HashSet<>(Arrays.asList(
            "cancel", "cancelled", "delete", "remove", "discard", "clear", "reset", "back", "close",
            "no", "not", "never", "reject", "logout", "exit", "fail", "failed", "error", "refund",
            "return", "edit", "without", "empty", "undo", "skip", "decline", "deny", "stop",
            // Money and "it worked" are never learned: the payment and success screens are
            // judged by the words they were verified with, and by nothing else.
            "pay", "paid", "payment", "payments", "amount", "cash", "upi", "proceed", "success",
            "successful", "successfully", "done", "ok", "okay", "yes", "thank", "thanks"));

    private static final Set<String> FILLER = new HashSet<>(Arrays.asList(
            "the", "a", "an", "of", "to", "for", "on", "in", "your", "my", "all", "please", "now",
            "here", "tap", "click", "this", "and", "by", "is"));

    private static final String[][] SAME_MEANING = {
            {"customer", "retailer", "party", "client", "outlet", "shop", "store", "buyer", "member"},
            {"order", "booking", "indent"},
            {"place", "submit", "confirm", "send", "book", "create", "finalize", "finalise", "generate"},
            {"show", "view", "display", "see", "expand"},
            {"copy", "repeat", "duplicate", "clone", "reorder"},
            {"detail", "info", "information", "profile"},
            {"milk", "dairy", "doodh", "dudh"},
            {"total", "count"},
            {"qty", "quantity"},
            {"created", "made", "booked", "dated", "raised"},
            {"distributor", "dealer", "agent", "stockist", "agency"},
            {"history", "previous", "past", "earlier"},
    };

    private static final Map<String, String> MEANING = new HashMap<>();

    static {
        for (String[] group : SAME_MEANING) {
            for (String word : group) MEANING.put(word, group[0]);
        }
    }

    /** The words of a label that carry meaning, each reduced to the head of its family. */
    static List<String> words(String norm) {
        Set<String> out = new LinkedHashSet<>();
        for (String raw : norm.replaceAll("[^a-z0-9]+", " ").trim().split(" ")) {
            if (raw.isEmpty() || raw.matches("[0-9]+") || FILLER.contains(raw)) continue;
            String word = raw;
            if (word.endsWith("ies") && word.length() > 4) {
                word = word.substring(0, word.length() - 3) + "y";
            } else if (word.endsWith("s") && !word.endsWith("ss") && word.length() > 3) {
                word = word.substring(0, word.length() - 1);
            }
            String head = MEANING.get(word);
            out.add(head == null ? word : head);
        }
        return new ArrayList<>(out);
    }

    /**
     * 0 (nothing in common) to 1 (the same thing): the share of meaningful words two labels
     * have in common, or, when they are nearly the same spelling, how nearly.
     */
    static double similarity(String a, String b) {
        List<String> left = words(a);
        List<String> right = words(b);
        double shared = 0;
        if (!left.isEmpty() && !right.isEmpty()) {
            Set<String> union = new HashSet<>(left);
            union.addAll(right);
            int common = 0;
            for (String word : left) {
                if (right.contains(word)) common++;
            }
            shared = (double) common / union.size();
        }
        String compactA = a.replaceAll("[^a-z0-9]+", "");
        String compactB = b.replaceAll("[^a-z0-9]+", "");
        double spelling = 0;
        int longest = Math.max(compactA.length(), compactB.length());
        // Spelling distance means nothing for very short words ("milk" / "silk").
        if (longest >= 6) {
            spelling = 1.0 - (double) distance(compactA, compactB) / longest;
            if (spelling < 0.82) spelling = 0;
        }
        return Math.max(shared, spelling);
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int swap = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(swap, Math.min(previous[j], current[j - 1]) + 1);
            }
            int[] keep = previous;
            previous = current;
            current = keep;
        }
        return previous[b.length()];
    }

    static String percent(double score) {
        return Math.round(score * 100) + "% alike";
    }

    // ------------------------------------------------------------- the stops

    /**
     * The bot's stops that mean "I could not find, or did not recognise, something on
     * screen" - the kind a renamed label causes. Only after one of these, and only when a
     * new name was actually learned, is the run started again by itself. The wording is the
     * service's own; a self-test fails if the two drift apart.
     */
    static final String[] LOOKUP_STOPS = {
            "Unrecognised screen in ",
            "Could not find \"Customers\" on the Sudha home screen",
            "Could not find the \"Show Retailers\" control on the list",
            "Could not open the Milk module for ",
    };

    public static boolean isLookupStop(String reason) {
        if (reason == null) return false;
        for (String stop : LOOKUP_STOPS) {
            if (reason.startsWith(stop)) return true;
        }
        return false;
    }
}
