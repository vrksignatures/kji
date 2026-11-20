package com.vrksignatures.sudhaautobot;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TIER 1 PERMANENT MEMORY ("Distributor Blueprint") + Section 5 Zero-Hardcoding Entity Engine.
 *
 * <p>Strictly isolated from Tier 2 DailyLedger / RunHistory in its own SharedPreferences file
 * ("sudha_tier1_permanent_blueprint"). NEVER auto-resets at midnight; clears ONLY when the user
 * explicitly confirms "Reset Distributor History".</p>
 */
public final class DistributorBlueprint {

    public static final String PREFS_NAME = "sudha_tier1_permanent_blueprint";
    private static final String KEY_LEARNED = "blueprint_learned";
    private static final String KEY_BLACKLIST_IDS = "permanent_blacklist_ids";
    private static final String KEY_DIST_COUNT = "distributor_count";
    private static final String KEY_PERM_SCROLLS = "perm_distributor_scrolls";

    private static final Pattern CUSTOMER_ID_INLINE =
            Pattern.compile("(?i)customer\\s*id\\s*:?\\s*([A-Za-z0-9_-]+)");
    private static final Pattern PHONE_LIKE = Pattern.compile("^\\+?\\d{10,13}$");

    private DistributorBlueprint() {
    }

    public static final class CardInfo {
        public final String customerId;
        public final String boldOrangeName;
        public final boolean isDistributor;
        public final boolean isRetailer;
        public final Rect bounds;

        public CardInfo(String customerId, String boldOrangeName, boolean isDistributor,
                        boolean isRetailer, Rect bounds) {
            this.customerId = customerId == null ? "" : customerId.trim().toUpperCase(Locale.ROOT);
            this.boldOrangeName = boldOrangeName == null ? "" : boldOrangeName.trim();
            this.isDistributor = isDistributor;
            this.isRetailer = isRetailer;
            this.bounds = bounds == null ? new Rect() : bounds;
        }

        public String normalizedName() {
            return boldOrangeName.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        }
    }

    public static final class Classification {
        public final Set<String> distributorIds;
        public final Set<String> distributorOwnedRetailerIds;
        public final Set<String> allBlacklistedIds;
        public final List<CardInfo> genuineRetailers;

        public Classification(Set<String> distributorIds, Set<String> distributorOwnedRetailerIds,
                              Set<String> allBlacklistedIds, List<CardInfo> genuineRetailers) {
            this.distributorIds = distributorIds;
            this.distributorOwnedRetailerIds = distributorOwnedRetailerIds;
            this.allBlacklistedIds = allBlacklistedIds;
            this.genuineRetailers = genuineRetailers;
        }
    }

    /**
     * Pure Section 5 structural + bidirectional adjacency classification (zero ID prefix hardcoding):
     * - Every Delivery Point (Dist) card is always excluded.
     * - Any Delivery Point (Retailer) card immediately ABOVE (i - 1) or immediately BELOW (i + 1)
     *   a (Dist) card with the exact same normalized Bold Orange Name is classified as a
     *   Distributor-Owned Retailer ID and boycotted.
     * - Edge Case A: A same-name (Retailer) card appearing later (NOT adjacent to a Dist card)
     *   is NOT excluded.
     * - Edge Case B: Zero-distributor list treats Card #1 as True Retailer #1 immediately.
     */
    public static Classification classifyOrderedCards(List<CardInfo> cards) {
        Set<String> distIds = new LinkedHashSet<>();
        Set<String> distOwnedRetailerIds = new LinkedHashSet<>();
        Set<String> allBlacklist = new LinkedHashSet<>();
        Set<Integer> excludedIndices = new LinkedHashSet<>();

        if (cards == null || cards.isEmpty()) {
            return new Classification(distIds, distOwnedRetailerIds, allBlacklist, Collections.emptyList());
        }

        for (int i = 0; i < cards.size(); i++) {
            CardInfo card = cards.get(i);
            if (!card.isDistributor) continue;

            excludedIndices.add(i);
            if (!card.customerId.isEmpty()) {
                distIds.add(card.customerId);
                allBlacklist.add(card.customerId);
            }

            String distName = card.normalizedName();
            if (distName.isEmpty()) continue;

            int[] neighbors = new int[]{i - 1, i + 1};
            for (int nIdx : neighbors) {
                if (nIdx < 0 || nIdx >= cards.size()) continue;
                CardInfo adj = cards.get(nIdx);
                if (adj.isRetailer && distName.equals(adj.normalizedName())) {
                    excludedIndices.add(nIdx);
                    if (!adj.customerId.isEmpty()) {
                        distOwnedRetailerIds.add(adj.customerId);
                        allBlacklist.add(adj.customerId);
                    }
                }
            }
        }

        List<CardInfo> genuine = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            CardInfo card = cards.get(i);
            if (!excludedIndices.contains(i) && card.isRetailer) {
                genuine.add(card);
            }
        }
        return new Classification(distIds, distOwnedRetailerIds, allBlacklist, genuine);
    }

    /** Parses visible Customer cards in top-to-bottom order from the Accessibility tree. */
    public static List<CardInfo> parseVisibleCards(AccessibilityNodeInfo root) {
        if (root == null) return Collections.emptyList();
        List<AccessibilityNodeInfo> anchors = new ArrayList<>();
        collectDeliveryPointNodes(root, anchors, 0);

        List<CardInfo> cards = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AccessibilityNodeInfo anchor : anchors) {
            AccessibilityNodeInfo container = findCardContainer(anchor);
            if (container == null) continue;
            Rect rect = new Rect();
            container.getBoundsInScreen(rect);
            if (rect.width() < 80 || rect.height() < 30) continue;

            List<String> texts = new ArrayList<>();
            collectTexts(container, texts, 0);
            boolean isDist = false;
            boolean isRet = false;
            for (String t : texts) {
                String lower = t.toLowerCase(Locale.ROOT);
                if (lower.contains("delivery point (dist)")) isDist = true;
                if (lower.contains("delivery point (retailer)")) isRet = true;
            }
            String id = extractCustomerId(texts);
            if (id.isEmpty()) continue;
            String key = id + "|" + (isDist ? "D" : "R");
            if (!seen.add(key)) continue;

            String name = extractBoldOrangeName(texts);
            cards.add(new CardInfo(id, name, isDist, isRet, rect));
        }
        Collections.sort(cards, Comparator.comparingInt(c -> c.bounds.top));
        return cards;
    }

    /**
     * Section 5 Rule 2: Finds the filter dropdown to the left of "Show Retailers".
     * Returns null when not visible; otherwise [node, currentLabel].
     */
    public static Object[] findFilterDropdown(AccessibilityNodeInfo root) {
        if (root == null) return null;
        AccessibilityNodeInfo allDist = AccessibilityTree.findExact(root, "All Distributors");
        if (allDist != null) return new Object[]{allDist, "All Distributors"};

        AccessibilityNodeInfo showRetailers = AccessibilityTree.findContaining(root, "show retailers");
        if (showRetailers == null) return null;
        Rect srRect = new Rect();
        showRetailers.getBoundsInScreen(srRect);
        if (srRect.width() <= 0 || srRect.height() <= 0) return null;

        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collectNodes(root, all, 0);
        AccessibilityNodeInfo best = null;
        for (AccessibilityNodeInfo n : all) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() < 60 || r.height() <= 0) continue;
            boolean sameRow = Math.abs(r.centerY() - srRect.centerY()) < 120;
            boolean leftOf = r.right <= srRect.left + 40;
            if (!sameRow || !leftOf) continue;
            String t = AccessibilityTree.textOf(n);
            if (n.isClickable() || !t.isEmpty()) {
                if (best == null || (n.isClickable() && !best.isClickable())) {
                    best = n;
                }
            }
        }
        if (best == null) return null;
        List<String> texts = new ArrayList<>();
        collectTexts(best, texts, 0);
        String label = texts.isEmpty() ? AccessibilityTree.textOf(best) : texts.get(0);
        return new Object[]{best, label == null ? "" : label.trim()};
    }

    // --------------------------------------------------- Tier 1 Persistence

    public static boolean isLearned(Context context) {
        return prefs(context).getBoolean(KEY_LEARNED, false);
    }

    public static Set<String> permanentBlacklist(Context context) {
        Set<String> stored = prefs(context).getStringSet(KEY_BLACKLIST_IDS, Collections.emptySet());
        return stored == null ? Collections.emptySet() : new LinkedHashSet<>(stored);
    }

    public static int permDistributorScrolls(Context context) {
        return Math.max(0, prefs(context).getInt(KEY_PERM_SCROLLS, 0));
    }

    public static boolean isBlacklisted(Context context, String customerId) {
        if (customerId == null || customerId.trim().isEmpty()) return false;
        return permanentBlacklist(context).contains(customerId.trim().toUpperCase(Locale.ROOT));
    }

    public static void saveLearnedBlueprint(Context context, Classification c, int permScrolls) {
        Set<String> merged = new LinkedHashSet<>(permanentBlacklist(context));
        merged.addAll(c.allBlacklistedIds);
        prefs(context).edit()
                .putBoolean(KEY_LEARNED, true)
                .putStringSet(KEY_BLACKLIST_IDS, merged)
                .putInt(KEY_DIST_COUNT, c.distributorIds.size())
                .putInt(KEY_PERM_SCROLLS, Math.max(0, permScrolls))
                .apply();
    }

    /** Clears ONLY Tier 1 Permanent Distributor Blueprint (never touches Tier 2 daily ledger). */
    public static void resetDistributorHistory(Context context) {
        prefs(context).edit().clear().apply();
    }

    public static String statusSummary(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.getBoolean(KEY_LEARNED, false)) {
            return "Tier 1 Permanent Blueprint: Not learned yet (auto-learns on next START)";
        }
        int dists = p.getInt(KEY_DIST_COUNT, 0);
        int blacklisted = permanentBlacklist(context).size();
        int scrolls = permDistributorScrolls(context);
        return "Tier 1 Permanent Blueprint: LEARNED · Distributors: " + dists
                + " · Blacklisted IDs: " + blacklisted + " · Top scrolls: " + scrolls;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // --------------------------------------------------------- Tree helpers

    private static void collectDeliveryPointNodes(AccessibilityNodeInfo node,
                                                  List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 50) return;
        String t = AccessibilityTree.textOf(node).toLowerCase(Locale.ROOT);
        if (t.contains("delivery point (dist)") || t.contains("delivery point (retailer)")) {
            out.add(node);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) collectDeliveryPointNodes(node.getChild(i), out, depth + 1);
    }

    private static AccessibilityNodeInfo findCardContainer(AccessibilityNodeInfo anchor) {
        AccessibilityNodeInfo cur = anchor;
        AccessibilityNodeInfo best = null;
        for (int hop = 0; cur != null && hop < 6; hop++) {
            List<String> texts = new ArrayList<>();
            collectTexts(cur, texts, 0);
            int dpCount = 0;
            boolean hasId = false;
            for (String s : texts) {
                String low = s.toLowerCase(Locale.ROOT);
                if (low.contains("delivery point (")) dpCount++;
                if (low.contains("customer id")) hasId = true;
            }
            if (dpCount > 1) break;
            if (dpCount == 1 && hasId) {
                best = cur;
                if (cur.isClickable()) return cur;
            }
            cur = cur.getParent();
        }
        return best;
    }

    private static String extractCustomerId(List<String> texts) {
        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i).trim();
            Matcher m = CUSTOMER_ID_INLINE.matcher(t);
            if (m.find() && m.group(1) != null && !m.group(1).trim().isEmpty()) {
                return m.group(1).trim().toUpperCase(Locale.ROOT);
            }
            if (t.equalsIgnoreCase("Customer Id:") || t.equalsIgnoreCase("Customer Id")) {
                if (i + 1 < texts.size() && !texts.get(i + 1).trim().isEmpty()) {
                    return texts.get(i + 1).trim().toUpperCase(Locale.ROOT);
                }
            }
        }
        return "";
    }

    private static String extractBoldOrangeName(List<String> texts) {
        String fallback = "";
        for (String raw : texts) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            String low = t.toLowerCase(Locale.ROOT);
            if (low.contains("delivery point") || low.contains("customer id")
                    || low.startsWith("total customers") || PHONE_LIKE.matcher(t).matches()) {
                continue;
            }
            if (fallback.isEmpty()) fallback = t;
            if (t.equals(t.toUpperCase(Locale.ROOT)) && t.matches(".*[A-Z].*")) {
                return t;
            }
        }
        return fallback;
    }

    private static void collectTexts(AccessibilityNodeInfo node, List<String> out, int depth) {
        if (node == null || depth > 12 || out.size() > 40) return;
        String t = AccessibilityTree.textOf(node);
        if (!t.isEmpty()) out.add(t);
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) collectTexts(node.getChild(i), out, depth + 1);
    }

    private static void collectNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 40 || out.size() > 400) return;
        out.add(node);
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) collectNodes(node.getChild(i), out, depth + 1);
    }
}
