package com.vrksignatures.sudhaautobot;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The list of retailers the bot gave up on, kept so that it can be read later.
 *
 * <p>A plain text file, {@code files/skipped-smr.txt}, one line per retailer:</p>
 * <pre>
 *   2026-11-18 14:05  SMR2235  EMPTY LIST   The app said: Orders list is empty. Retry?
 *   2026-11-18 14:09  SMR2231  NO ORDER T-1..T-5   No order to copy for T-1 to T-5
 * </pre>
 *
 * <p>Unlike the day's ledger it is not cleared by Reset or by a new day; only "Clear" on
 * the app's screen empties it. It is kept to the newest {@link #MAX_LINES} lines. The bot
 * never reads it: it is for the person.</p>
 */
final class SkipLog {

    static final String FILE = "skipped-smr.txt";
    static final int MAX_LINES = 2000;
    private static final long TRIM_ABOVE_BYTES = 300 * 1024;

    private SkipLog() {
    }

    static File file(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE);
    }

    /** Adds a retailer to the file. Never throws: a log that cannot be written must not stop a run. */
    static synchronized void add(Context context, String id, String kind, String note) {
        try {
            File file = file(context);
            try (Writer out = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                out.write(SkipLine.of(System.currentTimeMillis(), id, kind, note));
                out.write('\n');
            }
            if (file.length() > TRIM_ABOVE_BYTES) trim(file);
        } catch (Throwable e) {
            android.util.Log.w("SudhaAutoBot", "Skipped-retailer log not written", e);
        }
    }

    /** The newest lines, oldest of them first. */
    static synchronized List<String> read(Context context, int most) {
        List<String> lines = new ArrayList<>();
        File file = file(context);
        if (!file.isFile()) return lines;
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                lines.add(line);
                if (lines.size() > Math.max(1, most)) lines.remove(0);
            }
        } catch (Throwable e) {
            android.util.Log.w("SudhaAutoBot", "Skipped-retailer log not read", e);
        }
        return lines;
    }

    static synchronized int count(Context context) {
        return read(context, MAX_LINES).size();
    }

    static synchronized void clear(Context context) {
        File file = file(context);
        if (file.exists() && !file.delete()) {
            android.util.Log.w("SudhaAutoBot", "Skipped-retailer log not cleared");
        }
    }

    private static void trim(File file) throws Exception {
        List<String> keep = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                keep.add(line);
                if (keep.size() > MAX_LINES) keep.remove(0);
            }
        }
        try (Writer out = new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8)) {
            for (String line : keep) {
                out.write(line);
                out.write('\n');
            }
        }
    }
}
