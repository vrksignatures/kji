package com.vrksignatures.sudhaautobot;

import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/** Reads the labels off a screen for {@link UiMolder}. Looks only; presses nothing. */
public final class ScreenLabels {
    private static final int MAX_DEPTH = 40;
    private static final int MAX_LABELS = 400;

    private ScreenLabels() {
    }

    public static List<UiMolder.Label> of(AccessibilityNodeInfo root) {
        List<UiMolder.Label> out = new ArrayList<>();
        collect(root, out, 0);
        return out;
    }

    private static void collect(AccessibilityNodeInfo node, List<UiMolder.Label> out, int depth) {
        if (node == null || depth > MAX_DEPTH || out.size() >= MAX_LABELS) return;
        String text = AccessibilityTree.textOf(node);
        if (!text.isEmpty()) {
            boolean pressable = AccessibilityTree.clickableAncestor(node, 4) != null;
            out.add(new UiMolder.Label(text, pressable));
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) collect(node.getChild(i), out, depth + 1);
    }
}
