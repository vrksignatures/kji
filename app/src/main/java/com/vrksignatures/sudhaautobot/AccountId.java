package com.vrksignatures.sudhaautobot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells a distributor's account from a retailer's by the shape of its id.
 *
 * <ul>
 *   <li>id contains {@code SMR} - <b>retailer</b> ({@code SMR2196});</li>
 *   <li>id contains {@code SM} (but not {@code SMR}), or is digits only - <b>distributor</b>
 *       ({@code SM65}, {@code SM41}, {@code 65}).</li>
 * </ul>
 *
 * <p>{@code SMR} is tested first, because every {@code SMR} id also contains {@code SM}.</p>
 *
 * <p>This is how the roster is <em>read</em> (who is the distributor, how many retailers
 * are there). Which rows the bot may <em>open</em> is still decided by
 * {@link RetailerPolicy#isRetailerId}, which is stricter (exactly {@code SMR} + digits);
 * anything this class calls a distributor is never opened.</p>
 */
public final class AccountId {

    public enum Kind { RETAILER, DISTRIBUTOR, OTHER }

    /** A distributor read off the screen. */
    public static final class Distributor {
        public final String id;
        /** Name as printed, or "" when the screen did not give one that could be trusted. */
        public final String name;

        Distributor(String id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public String toString() {
            return name.isEmpty() ? id : name + " (" + id + ")";
        }
    }

    private static final Pattern RETAILER_IN_TEXT =
            Pattern.compile("(?<![a-z0-9])smr[0-9]{1,6}(?![a-z0-9])");
    /** "Distributor Name ( SM65 )", "Distributor: (65)", "Distributor - SM41". */
    private static final Pattern DISTRIBUTOR_IN_TEXT = Pattern.compile(
            "distributor(?:[ \\t]+name)?[ \\t]*[:\\-]?[ \\t]*\\(?[ \\t]*([a-z]{0,4}[0-9]{1,8})[ \\t]*\\)?");
    private static final Pattern NAME = Pattern.compile("[a-z][a-z .'&-]{1,38}");
    /** Words that sit under the distributor line on the details screen and are not a name. */
    private static final String[] NOT_A_NAME = {"view orders", "transactions", "mobile", "address",
            "milk", "prod", "pay", "name", "customer details", "distributor", "route", "zone"};

    private AccountId() {
    }

    /**
     * @param raw one id token as printed, e.g. "SM65", " smr2196 ", "65"
     */
    public static Kind kindOf(String raw) {
        if (raw == null) return Kind.OTHER;
        String id = raw.trim().toUpperCase(Locale.ROOT);
        // An id is one token of letters and digits with at least one digit in it. A person's
        // name that happens to contain "sm" ("Asmita") is not an id.
        if (id.isEmpty() || id.length() > 12 || !id.matches("[A-Z0-9]+") || !id.matches(".*[0-9].*")) {
            return Kind.OTHER;
        }
        if (id.contains("SMR")) return Kind.RETAILER;
        if (id.contains("SM")) return Kind.DISTRIBUTOR;
        if (id.matches("[0-9]+")) return Kind.DISTRIBUTOR;
        return Kind.OTHER;
    }

    public static boolean isDistributor(String raw) {
        return kindOf(raw) == Kind.DISTRIBUTOR;
    }

    public static boolean isRetailer(String raw) {
        return kindOf(raw) == Kind.RETAILER;
    }

    /** Every retailer id printed in a screen's text (lower-cased), in order, without repeats. */
    public static List<String> retailerIds(String textLower) {
        List<String> out = new ArrayList<>();
        if (textLower == null) return out;
        Matcher matcher = RETAILER_IN_TEXT.matcher(textLower);
        while (matcher.find()) {
            String id = matcher.group().toUpperCase(Locale.ROOT);
            if (!out.contains(id)) out.add(id);
        }
        return out;
    }

    /**
     * The distributor a screen names, or null. Reads the "Distributor Name ( SM65 )" line of
     * the details screen; the line after it is taken as the name when it looks like one.
     */
    public static Distributor distributorIn(String textLower) {
        if (textLower == null) return null;
        Matcher matcher = DISTRIBUTOR_IN_TEXT.matcher(textLower);
        while (matcher.find()) {
            String id = matcher.group(1).toUpperCase(Locale.ROOT);
            if (kindOf(id) != Kind.DISTRIBUTOR) continue;
            return new Distributor(id, nameAfter(textLower, matcher.end()));
        }
        return null;
    }

    private static String nameAfter(String text, int from) {
        int lineEnd = text.indexOf('\n', from);
        if (lineEnd < 0) return "";
        // The rest of the same line first ("Distributor Name ( SM65 ) Tuntun"), then the next.
        String[] tries = {text.substring(from, lineEnd), nextLine(text, lineEnd + 1)};
        for (String candidate : tries) {
            String name = candidate.trim().replaceAll("^[:\\-\\s]+", "");
            if (!NAME.matcher(name).matches()) continue;
            boolean label = false;
            for (String word : NOT_A_NAME) {
                if (name.equals(word) || name.startsWith(word + " ")) label = true;
            }
            if (!label) return titleCase(name);
        }
        return "";
    }

    private static String nextLine(String text, int from) {
        if (from >= text.length()) return "";
        int end = text.indexOf('\n', from);
        return end < 0 ? text.substring(from) : text.substring(from, end);
    }

    static String titleCase(String lower) {
        StringBuilder out = new StringBuilder(lower.length());
        boolean start = true;
        for (char c : lower.toCharArray()) {
            out.append(start ? Character.toUpperCase(c) : c);
            start = c == ' ' || c == '.' || c == '-';
        }
        return out.toString();
    }
}
