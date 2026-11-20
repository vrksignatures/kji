package com.vrksignatures.sudhaautobot;

/**
 * Single source of truth for what an eligible retailer identifier looks like.
 *
 * <p>The target app lists three kinds of identifiers and only one of them is a
 * bottable retailer:</p>
 * <ul>
 *   <li>{@code SMR123} — retailer. Eligible.</li>
 *   <li>{@code SM65} — distributor / dealer. Never eligible.</li>
 *   <li>{@code 65} — plain number (row index, quantity, route code). Never eligible.</li>
 * </ul>
 *
 * <p>The pattern is anchored and case sensitive on purpose: a token must be
 * exactly {@code SMR} followed by digits, so {@code SMR12A}, {@code ASMR12} and
 * {@code XSMR12} are all rejected.</p>
 */
public final class RetailerPolicy {
    /** Number of retailers expected per day; used for the panel progress bar. */
    public static final int EXPECTED_RETAILER_COUNT = 125;

    /** Anchored: the whole token must be SMR + digits. */
    private static final java.util.regex.Pattern EXACT_ID =
            java.util.regex.Pattern.compile("SMR[0-9]{1,6}");

    private RetailerPolicy() {
    }

    /** True only for an exact {@code SMR<digits>} token. */
    public static boolean isRetailerId(String value) {
        if (value == null) return false;
        return EXACT_ID.matcher(value.trim()).matches();
    }

    /**
     * Regex used to scan free text / node labels for embedded retailer tokens.
     * The guards reject tokens glued to other letters or digits, so {@code SMR12}
     * inside {@code SMRSMR12} or {@code ESMR12} is not picked up.
     */
    public static java.util.regex.Pattern embeddedPattern() {
        return java.util.regex.Pattern.compile("(?<![A-Z0-9])SMR[0-9]{1,6}(?![A-Z0-9])");
    }

    /** Distributor tokens, matched only to make sure we steer clear of them. */
    public static java.util.regex.Pattern distributorPattern() {
        return java.util.regex.Pattern.compile("(?<![A-Z0-9])SM[0-9]{1,6}(?![A-Z0-9])");
    }

    /** Normalises user input such as " smr012 " into "SMR012"; null when invalid. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return isRetailerId(trimmed) ? trimmed : null;
    }

    /** Extracts the numeric part of a valid id, or -1. */
    public static int numericPart(String id) {
        if (!isRetailerId(id)) return -1;
        try {
            return Integer.parseInt(id.substring(3));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
