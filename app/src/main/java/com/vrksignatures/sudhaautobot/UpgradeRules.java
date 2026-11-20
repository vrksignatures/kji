package com.vrksignatures.sudhaautobot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Pure-Java (zero Android SDK dependency) core rules for:
 * 1. Section 5 Zero-Hardcoding Distributor vs Retailer bidirectional adjacency classification.
 * 2. Section 10 Customer Demand smart pack math & Employee's Chance anti-bot scatter.
 *
 * Kept Android-free so tools/selftest/run.sh compiles and verifies every rule on a plain JVM.
 */
public final class UpgradeRules {

    private UpgradeRules() {
    }

    public static final class Card {
        public final String customerId;
        public final String boldOrangeName;
        public final boolean isDistributor;
        public final boolean isRetailer;

        public Card(String customerId, String boldOrangeName, boolean isDistributor, boolean isRetailer) {
            this.customerId = customerId == null ? "" : customerId.trim().toUpperCase(Locale.ROOT);
            this.boldOrangeName = boldOrangeName == null ? "" : boldOrangeName.trim();
            this.isDistributor = isDistributor;
            this.isRetailer = isRetailer;
        }

        public String normalizedName() {
            return boldOrangeName.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        }
    }

    public static final class Classification {
        public final Set<String> distributorIds;
        public final Set<String> distributorOwnedRetailerIds;
        public final Set<String> allBlacklistedIds;
        public final List<Card> genuineRetailers;

        public Classification(Set<String> distributorIds, Set<String> distributorOwnedRetailerIds,
                              Set<String> allBlacklistedIds, List<Card> genuineRetailers) {
            this.distributorIds = distributorIds;
            this.distributorOwnedRetailerIds = distributorOwnedRetailerIds;
            this.allBlacklistedIds = allBlacklistedIds;
            this.genuineRetailers = genuineRetailers;
        }
    }

    public static Classification classifyCards(List<Card> cards) {
        Set<String> distIds = new LinkedHashSet<>();
        Set<String> distOwned = new LinkedHashSet<>();
        Set<String> allBlacklist = new LinkedHashSet<>();
        Set<Integer> excluded = new LinkedHashSet<>();

        if (cards == null || cards.isEmpty()) {
            return new Classification(distIds, distOwned, allBlacklist, Collections.emptyList());
        }

        for (int i = 0; i < cards.size(); i++) {
            Card c = cards.get(i);
            if (!c.isDistributor) continue;
            excluded.add(i);
            if (!c.customerId.isEmpty()) {
                distIds.add(c.customerId);
                allBlacklist.add(c.customerId);
            }
            String dName = c.normalizedName();
            if (dName.isEmpty()) continue;

            for (int adjIdx : new int[]{i - 1, i + 1}) {
                if (adjIdx < 0 || adjIdx >= cards.size()) continue;
                Card adj = cards.get(adjIdx);
                if (adj.isRetailer && dName.equals(adj.normalizedName())) {
                    excluded.add(adjIdx);
                    if (!adj.customerId.isEmpty()) {
                        distOwned.add(adj.customerId);
                        allBlacklist.add(adj.customerId);
                    }
                }
            }
        }

        List<Card> genuine = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            if (!excluded.contains(i) && cards.get(i).isRetailer) {
                genuine.add(cards.get(i));
            }
        }
        return new Classification(distIds, distOwned, allBlacklist, genuine);
    }

    public static Map<Integer, Integer> generateScatteredPlan(String dateStr, int totalExtraMl,
                                                              int targetRetailers, int expectedRetailers) {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        int totalHalfLiters = totalExtraMl / 500;
        if (totalHalfLiters <= 0 || targetRetailers <= 0 || expectedRetailers <= 0) return result;

        int chosenCount = Math.max(1, Math.min(targetRetailers, Math.min(expectedRetailers, totalHalfLiters)));
        long seed = (dateStr + "|" + totalExtraMl + "|" + chosenCount + "|" + expectedRetailers).hashCode();
        Random rng = new Random(seed);

        List<Integer> pool = new ArrayList<>(expectedRetailers);
        for (int i = 1; i <= expectedRetailers; i++) pool.add(i);
        Collections.shuffle(pool, rng);

        List<Integer> chosen = new ArrayList<>(pool.subList(0, chosenCount));
        Collections.sort(chosen);

        Map<Integer, Integer> units = new LinkedHashMap<>();
        for (int idx : chosen) units.put(idx, 1);
        int remaining = totalHalfLiters - chosenCount;

        List<Integer> round = new ArrayList<>(chosen);
        while (remaining > 0) {
            Collections.shuffle(round, rng);
            for (int idx : round) {
                if (remaining <= 0) break;
                int add = (remaining >= 2 && rng.nextBoolean()) ? 2 : 1;
                units.put(idx, units.get(idx) + add);
                remaining -= add;
            }
        }
        for (Map.Entry<Integer, Integer> e : units.entrySet()) {
            result.put(e.getKey(), e.getValue() * 500);
        }
        return result;
    }

    /** Returns [packSizeMl (1000 or 500), newQty] or null if invalid. */
    public static int[] resolvePackDelta(int deltaMl, int qty1000, int qty500) {
        if (deltaMl == 0) return null;
        if (deltaMl % 1000 == 0 && qty1000 > 0 && (qty1000 + deltaMl / 1000) >= 0) {
            return new int[]{1000, qty1000 + deltaMl / 1000};
        }
        if (deltaMl % 500 == 0 && qty500 > 0 && (qty500 + deltaMl / 500) >= 0) {
            return new int[]{500, qty500 + deltaMl / 500};
        }
        if (deltaMl % 1000 == 0 && deltaMl > 0) {
            return new int[]{1000, qty1000 + deltaMl / 1000};
        }
        if (deltaMl % 500 == 0) {
            return new int[]{500, Math.max(0, qty500 + deltaMl / 500)};
        }
        return null;
    }
}
