package com.vrksignatures.sudhaautobot;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Aakriti's notebook: new names the Sudha app has given to things the bot knows.
 *
 * <p>Each line says "what the app now calls X". The bot's own code is not rewritten; when
 * it asks "is this label X?" or "does this screen say X?", the notebook lets the new name
 * answer to the old one. <b>With an empty notebook every answer is exactly what it was
 * before this class existed</b> - {@link #translate} hands back the very same string and
 * {@link #same} is always false.</p>
 *
 * <p>The notebook is kept in its own file, away from the day's ledger, and nothing that
 * resets a session touches it.</p>
 */
public final class LearnedUi {

    /** One new name. */
    public static final class Entry {
        /** Key of the {@link UiVocabulary.Slot} this is a new name for. */
        public final String slot;
        /** The new name: lower case, single spaces. */
        public final String alias;
        /** The new name as the app prints it. */
        public final String shown;
        /** When it was learned, {@code yyyy-MM-dd}. */
        public final String when;
        /** Version of the Sudha app it was learned from, or "". */
        public final String appVersion;
        /** False while it waits for a person to allow it; only live entries are used. */
        public final boolean live;

        public Entry(String slot, String alias, String shown, String when, String appVersion,
                     boolean live) {
            this.slot = slot;
            this.alias = normalize(alias);
            this.shown = clean(shown);
            this.when = clean(when);
            this.appVersion = clean(appVersion);
            this.live = live;
        }

        Entry allowed() {
            return new Entry(slot, alias, shown, when, appVersion, true);
        }
    }

    /** An immutable picture of the notebook; swapped whole, so readers never lock. */
    private static final class Book {
        final List<Entry> entries;
        final Entry[] live;
        final Pattern[] inText;
        final String[] replacement;

        Book(List<Entry> all) {
            entries = Collections.unmodifiableList(new ArrayList<>(all));
            List<Entry> usable = new ArrayList<>();
            List<Pattern> patterns = new ArrayList<>();
            List<String> replacements = new ArrayList<>();
            for (Entry entry : all) {
                UiVocabulary.Slot slot = UiVocabulary.slot(entry.slot);
                if (!entry.live || slot == null || entry.alias.isEmpty()) continue;
                usable.add(entry);
                // A single short word ("dairy") is far too common to rewrite across a whole
                // screen of text; such a name is honoured only as a label in its own right.
                if (!rewritable(entry.alias)) continue;
                StringBuilder regex = new StringBuilder("(?<![a-z0-9])");
                String[] words = entry.alias.split(" ");
                for (int i = 0; i < words.length; i++) {
                    if (i > 0) regex.append("[ \\t]+");
                    regex.append(Pattern.quote(words[i]));
                }
                regex.append("(?![a-z0-9])");
                patterns.add(Pattern.compile(regex.toString()));
                replacements.add(java.util.regex.Matcher.quoteReplacement(slot.primary()));
            }
            live = usable.toArray(new Entry[0]);
            inText = patterns.toArray(new Pattern[0]);
            replacement = replacements.toArray(new String[0]);
        }
    }

    private static volatile Book book = new Book(new ArrayList<Entry>());

    private LearnedUi() {
    }

    // ------------------------------------------------------- used by the bot

    /**
     * The screen's text with any learned name put back to the name the bot knows.
     *
     * @param textLower every label on the screen, lower-cased
     * @return the same string object when nothing was changed
     */
    public static String translate(String textLower) {
        Book now = book;
        if (now.inText.length == 0 || textLower == null) return textLower;
        String out = textLower;
        for (int i = 0; i < now.inText.length; i++) {
            java.util.regex.Matcher matcher = now.inText[i].matcher(out);
            if (matcher.find()) out = matcher.replaceAll(now.replacement[i]);
        }
        return out;
    }

    /**
     * The text the bot should judge a screen by, given the state it is in.
     *
     * <p>Learned names are put back everywhere except where money is involved: while the
     * bot is paying or has just succeeded, and on any screen that mentions payment or
     * success, the text is returned exactly as printed. Those screens are judged by the
     * words they were verified with and nothing Aakriti learned can colour them.</p>
     */
    public static String forBot(String textLower, String state) {
        if (textLower == null || book.inText.length == 0) return textLower;
        if ("PAYMENT".equals(state) || "SUCCESS".equals(state)) return textLower;
        if (textLower.contains("payment") || textLower.contains("success")) return textLower;
        return translate(textLower);
    }

    /**
     * True when {@code actual} is a learned new name for the thing the bot calls
     * {@code expected}. Both lower case with single spaces.
     */
    public static boolean same(String actual, String expected) {
        Book now = book;
        if (now.live.length == 0 || actual == null || expected == null) return false;
        for (Entry entry : now.live) {
            if (!isNameOf(entry.alias, actual)) continue;
            UiVocabulary.Slot slot = UiVocabulary.slot(entry.slot);
            if (slot != null && slot.accepts(expected)) return true;
        }
        return false;
    }

    /** A label is its name, possibly followed by figures ("total parties: 125"). */
    private static boolean isNameOf(String alias, String label) {
        if (label.equals(alias)) return true;
        return label.startsWith(alias) && label.substring(alias.length()).matches("[^a-z]+");
    }

    /** True when nothing has been learned (the bot then behaves exactly as it always has). */
    public static boolean isEmpty() {
        return book.live.length == 0;
    }

    // ----------------------------------------------------- used by Aakriti

    public static List<Entry> entries() {
        return book.entries;
    }

    public static boolean knows(String alias) {
        String wanted = normalize(alias);
        for (Entry entry : book.entries) {
            if (entry.alias.equals(wanted)) return true;
        }
        return false;
    }

    /**
     * Writes a new name down.
     *
     * @return true when it is new and usable from now on (that is, live)
     */
    public static synchronized boolean add(Entry entry) {
        if (entry == null || entry.alias.isEmpty() || UiVocabulary.slot(entry.slot) == null) return false;
        // A name the bot already knows for something is never given a second meaning.
        if (UiVocabulary.isKnown(entry.alias) || knows(entry.alias)) return false;
        List<Entry> next = new ArrayList<>(book.entries);
        next.add(entry);
        book = new Book(next);
        return entry.live;
    }

    /** A person allowed a proposed name. Returns true when something changed. */
    public static synchronized boolean allow(String alias) {
        String wanted = normalize(alias);
        List<Entry> next = new ArrayList<>();
        boolean changed = false;
        for (Entry entry : book.entries) {
            if (entry.alias.equals(wanted) && !entry.live) {
                next.add(entry.allowed());
                changed = true;
            } else {
                next.add(entry);
            }
        }
        if (changed) book = new Book(next);
        return changed;
    }

    public static synchronized boolean forget(String alias) {
        String wanted = normalize(alias);
        List<Entry> next = new ArrayList<>();
        for (Entry entry : book.entries) {
            if (!entry.alias.equals(wanted)) next.add(entry);
        }
        boolean changed = next.size() != book.entries.size();
        if (changed) book = new Book(next);
        return changed;
    }

    public static synchronized void clear() {
        book = new Book(new ArrayList<Entry>());
    }

    // ------------------------------------------------------------ keeping it

    public static String encode() {
        StringBuilder out = new StringBuilder("# Aakriti's notebook: slot | new name | as printed | when | app version | live\n");
        for (Entry entry : book.entries) {
            out.append(entry.slot).append('\t').append(entry.alias).append('\t')
                    .append(entry.shown).append('\t').append(entry.when).append('\t')
                    .append(entry.appVersion).append('\t').append(entry.live ? "live" : "ask")
                    .append('\n');
        }
        return out.toString();
    }

    public static synchronized void decode(String saved) {
        List<Entry> next = new ArrayList<>();
        if (saved != null) {
            for (String line : saved.split("\n")) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\t", -1);
                if (parts.length < 6 || UiVocabulary.slot(parts[0]) == null) continue;
                Entry entry = new Entry(parts[0], parts[1], parts[2], parts[3], parts[4],
                        "live".equals(parts[5]));
                if (entry.alias.isEmpty() || UiVocabulary.isKnown(entry.alias)) continue;
                boolean repeat = false;
                for (Entry earlier : next) {
                    if (earlier.alias.equals(entry.alias)) repeat = true;
                }
                if (!repeat) next.add(entry);
            }
        }
        book = new Book(next);
    }

    /** Reads the notebook from a file. A missing or unreadable file is an empty notebook. */
    public static boolean load(File file) {
        try {
            decode(read(file));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Writes the notebook; the old file is replaced only once the new one is complete. */
    public static boolean save(File file) {
        return write(file, encode());
    }

    static String read(File file) {
        if (file == null || !file.isFile()) return "";
        try (FileInputStream in = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int got;
            while ((got = in.read(chunk)) > 0) buffer.write(chunk, 0, got);
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    static boolean write(File file, String content) {
        if (file == null) return false;
        File folder = file.getParentFile();
        if (folder != null && !folder.exists() && !folder.mkdirs()) return false;
        File draft = new File(file.getPath() + ".new");
        try (FileOutputStream out = new FileOutputStream(draft)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception e) {
            return false;
        }
        if (draft.renameTo(file)) return true;
        // Some file systems will not rename over an existing file.
        return file.delete() && draft.renameTo(file);
    }

    // ------------------------------------------------------------------ text

    /** Lower case, single spaces, trimmed - the same form the tree search compares in. */
    public static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("[\\t\\n\\r]+", " ").trim();
    }

    /** Whether a name is distinctive enough to be rewritten wherever it appears in text. */
    static boolean rewritable(String alias) {
        return alias.indexOf(' ') > 0 || alias.length() >= 8;
    }
}
