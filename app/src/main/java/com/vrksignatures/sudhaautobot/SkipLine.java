package com.vrksignatures.sudhaautobot;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** How one line of the skipped-retailer file ({@link SkipLog}) is written. Plain Java. */
public final class SkipLine {

    /** Why a retailer was left. */
    public static final String EMPTY_LIST = "EMPTY LIST";
    public static final String NO_ORDER_5_DATES = "NO ORDER T-1..T-5";
    public static final String COPY_PROBLEM = "COPY PROBLEM";

    private SkipLine() {
    }

    /** "2026-11-18 14:05  SMR2235  EMPTY LIST   what the app said". Always one line. */
    public static String of(long at, String id, String kind, String note) {
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(at));
        String said = note == null ? "" : note.replaceAll("\\s+", " ").trim();
        if (said.length() > 160) said = said.substring(0, 160) + "…";
        String who = id == null || id.trim().isEmpty() ? "?" : id.trim();
        String why = kind == null ? "" : kind.replaceAll("\\s+", " ").trim();
        return when + "  " + who + "  " + why + (said.isEmpty() ? "" : "   " + said);
    }
}
