package com.vrksignatures.sudhaautobot;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the Sudha app itself says about today's list: how many retailers there are and who
 * the distributor is. Nothing here is typed in by a person or fixed in the code.
 *
 * <p>It is filled by reading the text of each screen the bot already looks at. It presses
 * nothing and asks the app for nothing.</p>
 *
 * <h3>How many retailers</h3>
 * The list prints "Total Customers: N". That N is used when
 * <ul>
 *   <li>it was read while the list was open with retailers showing (three or more ids on
 *       screen - the same line on a collapsed list counts something else), and</li>
 *   <li>it is not smaller than the number of different retailers actually seen today (if it
 *       is, it is counting something else and is ignored).</li>
 * </ul>
 * When there is no such number the caller's own figure is kept, raised if more retailers
 * than that have already been seen. So with the app as it is today (header says 125) the
 * answer is 125, exactly as before; with 300 it is 300; with 80 it is 80.
 */
public final class Roster {

    private static final Pattern TOTAL = Pattern.compile(
            "total[ \\t]+(?:customers?|retailers?)[ \\t]*[:\\-]?[ \\t]*([0-9]{1,5})");
    private static final int MAX_TOTAL = 5000;
    private static final int MAX_REMEMBERED_IDS = 6000;

    private String day = "";
    private final Set<String> seen = new LinkedHashSet<>();
    private int header;
    private int lastTotal;
    private String distributorId = "";
    private String distributorName = "";
    private String distributorBefore = "";
    private boolean dirty;

    /**
     * @param textLower every label on the screen, lower-cased
     * @param today     the local date, any fixed format; a new value starts a new day
     */
    public synchronized void see(String textLower, String today) {
        if (textLower == null || today == null) return;
        if (!today.equals(day)) {
            int yesterday = total();
            if (yesterday > 0) lastTotal = yesterday;
            seen.clear();
            header = 0;
            day = today;
            dirty = true;
        }
        List<String> ids = AccountId.retailerIds(textLower);
        for (String id : ids) {
            if (seen.size() < MAX_REMEMBERED_IDS && seen.add(id)) dirty = true;
        }
        if (ids.size() >= 3) {
            Matcher matcher = TOTAL.matcher(textLower);
            while (matcher.find()) {
                int value = Integer.parseInt(matcher.group(1));
                if (value >= 1 && value <= MAX_TOTAL && value > header) {
                    header = value;
                    dirty = true;
                }
            }
        }
        AccountId.Distributor found = AccountId.distributorIn(textLower);
        if (found != null) {
            if (!found.id.equals(distributorId)) {
                if (!distributorId.isEmpty()) distributorBefore = distributorLabel();
                distributorId = found.id;
                distributorName = found.name;
                dirty = true;
            } else if (!found.name.isEmpty() && !found.name.equals(distributorName)) {
                distributorName = found.name;
                dirty = true;
            }
        }
    }

    /** True when the app's own "Total Customers" figure can be relied on today. */
    public synchronized boolean headerTrusted() {
        return header > 0 && header >= seen.size();
    }

    /**
     * How many retailers the list holds today.
     *
     * @param fallback the figure to use when the app has not printed a usable one
     */
    public synchronized int expected(int fallback) {
        if (headerTrusted()) return header;
        return Math.max(fallback, seen.size());
    }

    /** Best figure without a fallback: the header, else the count seen, else the last full day. */
    public synchronized int total() {
        if (headerTrusted()) return header;
        return seen.size();
    }

    public synchronized int header() {
        return header;
    }

    public synchronized int seenToday() {
        return seen.size();
    }

    public synchronized int lastTotal() {
        return lastTotal;
    }

    public synchronized String day() {
        return day;
    }

    public synchronized String distributorId() {
        return distributorId;
    }

    public synchronized String distributorName() {
        return distributorName;
    }

    /** "Tuntun (SM41)", "SM41", or "" when no distributor has been read yet. */
    public synchronized String distributorLabel() {
        if (distributorId.isEmpty()) return "";
        return distributorName.isEmpty() ? distributorId : distributorName + " (" + distributorId + ")";
    }

    /** The distributor that was there before the present one, or "". */
    public synchronized String distributorBefore() {
        return distributorBefore;
    }

    /** One line for a person: "300 retailers (the app's own count) · distributor Tuntun (SM41)". */
    public synchronized String describe() {
        StringBuilder out = new StringBuilder();
        if (headerTrusted()) {
            out.append(header).append(" retailers (the app's own count)");
        } else if (!seen.isEmpty()) {
            out.append(seen.size()).append(" retailers seen so far today");
        } else if (lastTotal > 0) {
            out.append(lastTotal).append(" retailers last time");
        } else {
            out.append("list not read yet");
        }
        if (!distributorId.isEmpty()) out.append("  ·  distributor ").append(distributorLabel());
        return out.toString();
    }

    /** True once since the last call if anything worth saving changed. */
    public synchronized boolean takeDirty() {
        boolean was = dirty;
        dirty = false;
        return was;
    }

    // ------------------------------------------------------------ keeping it

    public synchronized String encode() {
        StringBuilder out = new StringBuilder();
        out.append("day=").append(day).append('\n');
        out.append("header=").append(header).append('\n');
        out.append("lastTotal=").append(lastTotal).append('\n');
        out.append("distributorId=").append(distributorId).append('\n');
        out.append("distributorName=").append(distributorName).append('\n');
        out.append("distributorBefore=").append(distributorBefore).append('\n');
        out.append("seen=").append(String.join(",", seen)).append('\n');
        return out.toString();
    }

    public static Roster decode(String saved) {
        Roster roster = new Roster();
        if (saved == null) return roster;
        for (String line : saved.split("\n")) {
            int cut = line.indexOf('=');
            if (cut <= 0) continue;
            String key = line.substring(0, cut);
            String value = line.substring(cut + 1).trim();
            try {
                switch (key) {
                    case "day":
                        roster.day = value;
                        break;
                    case "header":
                        roster.header = clamp(Integer.parseInt(value));
                        break;
                    case "lastTotal":
                        roster.lastTotal = clamp(Integer.parseInt(value));
                        break;
                    case "distributorId":
                        roster.distributorId = AccountId.isDistributor(value) ? value : "";
                        break;
                    case "distributorName":
                        roster.distributorName = value;
                        break;
                    case "distributorBefore":
                        roster.distributorBefore = value;
                        break;
                    case "seen":
                        for (String id : value.split(",")) {
                            if (RetailerPolicy.isRetailerId(id)) roster.seen.add(id);
                        }
                        break;
                    default:
                        break;
                }
            } catch (NumberFormatException ignored) {
                // A damaged line is simply not restored.
            }
        }
        if (roster.distributorId.isEmpty()) roster.distributorName = "";
        return roster;
    }

    private static int clamp(int value) {
        return value < 0 || value > MAX_TOTAL ? 0 : value;
    }
}
