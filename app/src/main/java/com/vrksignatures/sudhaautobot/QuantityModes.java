package com.vrksignatures.sudhaautobot;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * SECTION 10: Pre-Run Quantity Manipulation Modes.
 *
 * <ul>
 *   <li><b>Mode A — Customer Demand:</b> Exact Pack Override (e.g. SM 500 ML = 6) or Volume Delta
 *       (e.g. COW +1000 ml, SM -500 ml) with smart existing-pack math.</li>
 *   <li><b>Mode B — Employee's Chance:</b> Deterministic-for-today non-consecutive anti-bot
 *       randomized scatter across [1 .. expectedRetailers] summing to the exact user-supplied ml.</li>
 * </ul>
 */
public final class QuantityModes {

    private static final String TAG = "QuantityModes";
    private static final String PREFS_CONFIG = "sudha_quantity_modes_config";
    private static final String PREFS_DAILY_PLAN = "sudha_quantity_modes_daily_plan";

    private static final String KEY_DEMAND_RULES = "demand_rules_json";
    private static final String KEY_EC_ENABLED = "ec_enabled";
    private static final String KEY_EC_EXTRA_ML = "ec_extra_ml";
    private static final String KEY_EC_TARGET_RETAILERS = "ec_target_retailers";

    private static final String KEY_PLAN_DATE = "plan_date";
    private static final String KEY_PLAN_JSON = "plan_json";
    private static final String KEY_APPLIED_INDICES = "applied_indices";

    public enum MilkSku {
        DTM_180("DTM 180 ML", "DTM", 180),
        TM_500("TM 500 ML", "TM", 500),
        TM_1000("TM 1000 ML", "TM", 1000),
        COW_500("COW 500 ML", "COW", 500),
        COW_1000("COW 1000 ML", "COW", 1000),
        COW_6000("COW 6000 ML", "COW", 6000),
        SM_500("SM 500 ML", "SM", 500),
        SM_1000("SM 1000 ML", "SM", 1000),
        SM_6000("SM 6000 ML", "SM", 6000),
        FCM_500("FCM 500 ML", "FCM", 500),
        FCM_1000("FCM 1000 ML", "FCM", 1000),
        FCM_6000("FCM 6000 ML", "FCM", 6000),
        SUDHA_DELIGHT_1000("SUDHA DELIGHT 1000 ML", "SUDHA DELIGHT", 1000);

        public final String label;
        public final String family;
        public final int packMl;

        MilkSku(String label, String family, int packMl) {
            this.label = label;
            this.family = family;
            this.packMl = packMl;
        }

        public static MilkSku match(String raw) {
            if (raw == null) return null;
            String norm = raw.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            for (MilkSku sku : values()) {
                if (sku.label.equals(norm)) return sku;
            }
            for (MilkSku sku : values()) {
                if (norm.contains(sku.label)) return sku;
            }
            return null;
        }
    }

    public static final class DemandRule {
        public final String customerId;
        public final boolean isExactPack; // true = exact SKU pack count; false = +/- ml delta on family
        public final String target;       // MilkSku.label OR family ("COW", "SM", "TM", "FCM")
        public final int value;           // exact pack count OR signed ml (+1000, -500)

        public DemandRule(String customerId, boolean isExactPack, String target, int value) {
            this.customerId = customerId == null ? "" : customerId.trim().toUpperCase(Locale.ROOT);
            this.isExactPack = isExactPack;
            this.target = target == null ? "" : target.trim().toUpperCase(Locale.ROOT);
            this.value = value;
        }

        public String summary() {
            if (isExactPack) {
                return customerId + ": " + target + " = " + value + " packs";
            }
            String sign = value >= 0 ? "+" : "";
            return customerId + ": " + target + " " + sign + value + " ml";
        }
    }

    public static final class EditPlan {
        public final MilkSku sku;
        public final int oldQty;
        public final int newQty;
        public final String reason;

        public EditPlan(MilkSku sku, int oldQty, int newQty, String reason) {
            this.sku = sku;
            this.oldQty = oldQty;
            this.newQty = newQty;
            this.reason = reason;
        }
    }

    private QuantityModes() {
    }

    // --------------------------------------------------- Anti-Bot Scatter Plan

    /**
     * Generates a deterministic-for-today randomly scattered allocation across [1 .. expectedRetailers].
     * NEVER allocates to the first N consecutive retailers (1..N).
     * Sums to exact totalExtraMl (in 500 ml pack-aligned increments).
     */
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

    /**
     * Resolves a +/- ml delta onto a milk family using Section 10 smart existing-pack math:
     * - If 1000 ML pack has MQty > 0 and delta is multiple of 1000 ml -> adjust 1000 ML pack.
     * - Else if 500 ML pack has MQty > 0 and delta is multiple of 500 ml -> adjust 500 ML pack.
     * - Else default to 1000 ML (if multiple of 1000) or 500 ML pack.
     */
    public static EditPlan resolveVolumeDelta(String family, int deltaMl,
                                              Map<MilkSku, Integer> currentQty, String reasonPrefix) {
        if (deltaMl == 0 || family == null) return null;
        String fam = family.trim().toUpperCase(Locale.ROOT);
        MilkSku sku1000 = null;
        MilkSku sku500 = null;
        for (MilkSku s : MilkSku.values()) {
            if (s.family.equals(fam)) {
                if (s.packMl == 1000) sku1000 = s;
                else if (s.packMl == 500) sku500 = s;
            }
        }
        int q1000 = sku1000 != null && currentQty.containsKey(sku1000) ? currentQty.get(sku1000) : 0;
        int q500 = sku500 != null && currentQty.containsKey(sku500) ? currentQty.get(sku500) : 0;

        MilkSku chosen = null;
        if (sku1000 != null && deltaMl % 1000 == 0 && q1000 > 0 && (q1000 + deltaMl / 1000) >= 0) {
            chosen = sku1000;
        } else if (sku500 != null && deltaMl % 500 == 0 && q500 > 0 && (q500 + deltaMl / 500) >= 0) {
            chosen = sku500;
        } else if (sku1000 != null && deltaMl % 1000 == 0 && deltaMl > 0) {
            chosen = sku1000;
        } else if (sku500 != null && deltaMl % 500 == 0) {
            chosen = sku500;
        }
        if (chosen == null) return null;

        int oldQty = currentQty.containsKey(chosen) ? currentQty.get(chosen) : 0;
        int newQty = Math.max(0, oldQty + (deltaMl / chosen.packMl));
        if (newQty == oldQty) return null;
        String sign = deltaMl >= 0 ? "+" : "";
        return new EditPlan(chosen, oldQty, newQty,
                reasonPrefix + ": " + fam + " " + sign + deltaMl + "ml -> " + chosen.label
                        + " (" + oldQty + " -> " + newQty + ")");
    }

    public static List<EditPlan> computeEdits(String customerId, int genuineIndex,
                                              Map<MilkSku, Integer> tableQty,
                                              List<DemandRule> demandRules,
                                              boolean employeeChanceOn,
                                              Map<Integer, Integer> scatteredPlan,
                                              Set<Integer> alreadyAppliedIndices) {
        List<EditPlan> edits = new ArrayList<>();
        String normId = customerId == null ? "" : customerId.trim().toUpperCase(Locale.ROOT);
        Map<MilkSku, Integer> working = new LinkedHashMap<>(tableQty);

        boolean matchedDemand = false;
        if (demandRules != null) {
            for (DemandRule rule : demandRules) {
                if (!rule.customerId.equals(normId)) continue;
                matchedDemand = true;
                if (rule.isExactPack) {
                    MilkSku sku = MilkSku.match(rule.target);
                    if (sku != null) {
                        int oldQ = working.containsKey(sku) ? working.get(sku) : 0;
                        int newQ = Math.max(0, rule.value);
                        if (newQ != oldQ) {
                            working.put(sku, newQ);
                            edits.add(new EditPlan(sku, oldQ, newQ,
                                    "Customer Demand exact: " + sku.label + " = " + newQ));
                        }
                    }
                } else {
                    EditPlan plan = resolveVolumeDelta(rule.target, rule.value, working, "Customer Demand");
                    if (plan != null) {
                        working.put(plan.sku, plan.newQty);
                        edits.add(plan);
                    }
                }
            }
        }
        if (matchedDemand) return edits;

        if (employeeChanceOn && scatteredPlan != null
                && (alreadyAppliedIndices == null || !alreadyAppliedIndices.contains(genuineIndex))) {
            Integer extraMl = scatteredPlan.get(genuineIndex);
            if (extraMl != null && extraMl >= 500) {
                // Prefer COW or SM family that already has active MQty > 0
                String family = "COW";
                boolean cowActive = working.getOrDefault(MilkSku.COW_500, 0) > 0
                        || working.getOrDefault(MilkSku.COW_1000, 0) > 0;
                boolean smActive = working.getOrDefault(MilkSku.SM_500, 0) > 0
                        || working.getOrDefault(MilkSku.SM_1000, 0) > 0;
                if (!cowActive && smActive) family = "SM";

                EditPlan plan = resolveVolumeDelta(family, extraMl, working,
                        "Employee's Chance (#" + genuineIndex + ")");
                if (plan != null) edits.add(plan);
            }
        }
        return edits;
    }

    // --------------------------------------------------- Live Order Form Editor

    /**
     * Applies Customer Demand or Employee's Chance MQty edits directly to the open New Order Milk
     * table BEFORE PLACE ORDER is tapped. Safe no-op when neither mode applies to this retailer.
     */
    public static int applyEditsToOrderForm(AccessibilityNodeInfo root, Context context,
                                            String customerId, int genuineIndex,
                                            int expectedRetailers,
                                            AccessibilityTree.Gestures gestures) {
        if (root == null || context == null) return 0;
        List<DemandRule> rules = loadDemandRules(context);
        boolean ecOn = isEmployeeChanceEnabled(context);
        if (rules.isEmpty() && !ecOn) return 0;

        Map<MilkSku, AccessibilityNodeInfo> mQtyNodes = new LinkedHashMap<>();
        Map<MilkSku, Integer> tableQty = new LinkedHashMap<>();
        collectVisibleMilkRows(root, mQtyNodes, tableQty);
        if (tableQty.isEmpty()) return 0;

        Map<Integer, Integer> plan = ecOn ? loadOrGenerateDailyPlan(context, expectedRetailers) : null;
        Set<Integer> applied = ecOn ? loadAppliedIndices(context) : Collections.emptySet();

        List<EditPlan> edits = computeEdits(customerId, genuineIndex, tableQty, rules, ecOn, plan, applied);
        if (edits.isEmpty()) return 0;

        int appliedCount = 0;
        boolean usedEmployeeChance = false;
        for (EditPlan edit : edits) {
            AccessibilityNodeInfo field = mQtyNodes.get(edit.sku);
            if (field == null) {
                Log.w(TAG, "SKU row not currently visible for edit: " + edit.reason);
                continue;
            }
            AccessibilityTree.click(field, gestures);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    String.valueOf(edit.newQty));
            boolean ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            if (ok) {
                appliedCount++;
                Log.i(TAG, "Applied MQty edit for " + customerId + ": " + edit.reason);
                if (edit.reason.startsWith("Employee's Chance")) usedEmployeeChance = true;
            }
        }
        if (usedEmployeeChance) {
            markIndexApplied(context, genuineIndex);
        }
        return appliedCount;
    }

    private static void collectVisibleMilkRows(AccessibilityNodeInfo root,
                                               Map<MilkSku, AccessibilityNodeInfo> mQtyNodes,
                                               Map<MilkSku, Integer> tableQty) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collectAll(root, all, 0);
        for (AccessibilityNodeInfo node : all) {
            String label = AccessibilityTree.textOf(node);
            MilkSku sku = MilkSku.match(label);
            if (sku == null || tableQty.containsKey(sku)) continue;

            AccessibilityNodeInfo row = findRowContainer(node);
            if (row == null) continue;
            List<AccessibilityNodeInfo> edits = new ArrayList<>();
            collectEditTexts(row, edits, 0);
            if (edits.isEmpty()) continue;

            Collections.sort(edits, Comparator.comparingInt(n -> {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                return r.left;
            }));
            // Table columns: Item | Rate | MQty | EQty | Total -> index 0 is MQty
            AccessibilityNodeInfo mQtyField = edits.get(0);
            int val = 0;
            try {
                String raw = AccessibilityTree.textOf(mQtyField).trim();
                if (!raw.isEmpty()) val = Integer.parseInt(raw);
            } catch (Exception ignored) {
            }
            mQtyNodes.put(sku, mQtyField);
            tableQty.put(sku, val);
        }
    }

    private static AccessibilityNodeInfo findRowContainer(AccessibilityNodeInfo skuNode) {
        AccessibilityNodeInfo cur = skuNode.getParent();
        for (int hop = 0; cur != null && hop < 5; hop++) {
            List<AccessibilityNodeInfo> edits = new ArrayList<>();
            collectEditTexts(cur, edits, 0);
            if (!edits.isEmpty() && edits.size() <= 3) return cur;
            cur = cur.getParent();
        }
        return skuNode.getParent();
    }

    private static void collectEditTexts(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 8) return;
        CharSequence cls = node.getClassName();
        if (node.isEditable() || (cls != null && cls.toString().toLowerCase(Locale.ROOT).contains("edittext"))) {
            out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) collectEditTexts(node.getChild(i), out, depth + 1);
    }

    private static void collectAll(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 40 || out.size() > 500) return;
        out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collectAll(node.getChild(i), out, depth + 1);
    }

    // --------------------------------------------------- Persistence Helpers

    public static List<DemandRule> loadDemandRules(Context context) {
        List<DemandRule> out = new ArrayList<>();
        String raw = configPrefs(context).getString(KEY_DEMAND_RULES, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new DemandRule(
                        o.optString("cid", ""),
                        o.optBoolean("exact", true),
                        o.optString("target", ""),
                        o.optInt("val", 0)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void saveDemandRules(Context context, List<DemandRule> rules) {
        JSONArray arr = new JSONArray();
        try {
            for (DemandRule r : rules) {
                JSONObject o = new JSONObject();
                o.put("cid", r.customerId);
                o.put("exact", r.isExactPack);
                o.put("target", r.target);
                o.put("val", r.value);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        configPrefs(context).edit().putString(KEY_DEMAND_RULES, arr.toString()).apply();
    }

    public static boolean isEmployeeChanceEnabled(Context context) {
        return configPrefs(context).getBoolean(KEY_EC_ENABLED, false);
    }

    public static int employeeChanceExtraMl(Context context) {
        return configPrefs(context).getInt(KEY_EC_EXTRA_ML, 0);
    }

    public static int employeeChanceTargetRetailers(Context context) {
        return configPrefs(context).getInt(KEY_EC_TARGET_RETAILERS, 0);
    }

    public static void saveEmployeeChance(Context context, boolean enabled, int extraMl, int targetRetailers) {
        configPrefs(context).edit()
                .putBoolean(KEY_EC_ENABLED, enabled)
                .putInt(KEY_EC_EXTRA_ML, Math.max(0, extraMl))
                .putInt(KEY_EC_TARGET_RETAILERS, Math.max(0, targetRetailers))
                .apply();
        // Force fresh daily plan regeneration with new parameters
        dailyPrefs(context).edit().clear().apply();
    }

    private static Map<Integer, Integer> loadOrGenerateDailyPlan(Context context, int expectedRetailers) {
        String today = LocalDate.now().toString();
        SharedPreferences p = dailyPrefs(context);
        if (today.equals(p.getString(KEY_PLAN_DATE, ""))) {
            String raw = p.getString(KEY_PLAN_JSON, null);
            if (raw != null) {
                try {
                    JSONObject obj = new JSONObject(raw);
                    Map<Integer, Integer> map = new LinkedHashMap<>();
                    for (java.util.Iterator<String> it = obj.keys(); it.hasNext(); ) {
                        String k = it.next();
                        map.put(Integer.parseInt(k), obj.getInt(k));
                    }
                    return map;
                } catch (Exception ignored) {
                }
            }
        }
        Map<Integer, Integer> fresh = generateScatteredPlan(
                today, employeeChanceExtraMl(context), employeeChanceTargetRetailers(context), expectedRetailers);
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<Integer, Integer> e : fresh.entrySet()) {
                obj.put(String.valueOf(e.getKey()), e.getValue());
            }
            p.edit().clear()
                    .putString(KEY_PLAN_DATE, today)
                    .putString(KEY_PLAN_JSON, obj.toString())
                    .apply();
        } catch (Exception ignored) {
        }
        return fresh;
    }

    private static Set<Integer> loadAppliedIndices(Context context) {
        Set<Integer> out = new LinkedHashSet<>();
        String today = LocalDate.now().toString();
        SharedPreferences p = dailyPrefs(context);
        if (!today.equals(p.getString(KEY_PLAN_DATE, ""))) return out;
        Set<String> raw = p.getStringSet(KEY_APPLIED_INDICES, Collections.emptySet());
        if (raw != null) {
            for (String s : raw) {
                try {
                    out.add(Integer.parseInt(s));
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    private static void markIndexApplied(Context context, int index) {
        SharedPreferences p = dailyPrefs(context);
        Set<String> updated = new LinkedHashSet<>(p.getStringSet(KEY_APPLIED_INDICES, Collections.emptySet()));
        updated.add(String.valueOf(index));
        p.edit().putStringSet(KEY_APPLIED_INDICES, updated).apply();
    }

    private static SharedPreferences configPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_CONFIG, Context.MODE_PRIVATE);
    }

    private static SharedPreferences dailyPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_DAILY_PLAN, Context.MODE_PRIVATE);
    }
}
