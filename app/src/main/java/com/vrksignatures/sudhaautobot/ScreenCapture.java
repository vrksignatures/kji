package com.vrksignatures.sudhaautobot;

import android.content.Context;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * Dumps what the accessibility service actually sees into a text file.
 *
 * <p>This exists because the bot's screen recognition is text based: if the target app
 * uses different wording than the default predicates expect, the bot pauses instead of
 * guessing. A capture shows the real package name, the real labels and the real view
 * ids, which is everything needed to correct those predicates.</p>
 *
 * <p>Captures are read-only: nothing is tapped and no state machine field is touched,
 * so capturing is safe at any time, including mid-run.</p>
 */
public final class ScreenCapture {
    private static final int MAX_DEPTH = 40;
    /** Only the newest N dumps are kept so the app never grows without bound. */
    private static final int MAX_FILES = 12;
    private static final String PREFIX = "screen-";

    private ScreenCapture() {
    }

    /** Writes one dump and returns the file, or null when it could not be written. */
    public static File save(Context context, AccessibilityNodeInfo root) {
        Date now = new Date();
        SimpleDateFormat stamp = new SimpleDateFormat("HHmmss", Locale.US);
        SimpleDateFormat full = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        File file = new File(context.getFilesDir(), PREFIX + stamp.format(now) + ".txt");

        StringBuilder out = new StringBuilder(4096);
        out.append("# SudhaAutoBot screen capture\n");
        out.append("time    : ").append(full.format(now)).append('\n');

        if (root == null) {
            out.append("package : <none>\n");
            out.append("root    : null — no window was readable at this moment\n");
            out.append("\nTry again with the Sudha app in the foreground.\n");
        } else {
            CharSequence packageName = root.getPackageName();
            CharSequence className = root.getClassName();
            out.append("package : ").append(packageName == null ? "?" : packageName).append('\n');
            out.append("rootClass: ").append(className == null ? "?" : className).append('\n');
            out.append('\n').append("## every label on this screen\n");
            out.append(AccessibilityTree.allText(root)).append('\n');
            out.append("## FAB / add-button detection\n");
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            AccessibilityTree.AddButtonTarget fab = AccessibilityTree.findAddButton(
                    root, metrics.widthPixels, metrics.heightPixels);
            if (fab == null) {
                out.append("no FAB candidate; the bot would tap the corner fallback at ")
                        .append(Math.round(metrics.widthPixels * 0.867f)).append(',')
                        .append(Math.round(metrics.heightPixels * 0.902f)).append('\n');
            } else {
                out.append("chosen : ").append(fab).append('\n');
                out.append("weak   : ").append(fab.weak).append('\n');
            }
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                out.append("screenshot : unavailable before Android 11\n");
            }
            out.append("## node tree\n");
            dump(root, out, 0);
        }

        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
        prune(context);
        return file;
    }

    private static void dump(AccessibilityNodeInfo node, StringBuilder out, int depth) {
        if (node == null || depth > MAX_DEPTH) return;

        StringBuilder indent = new StringBuilder(depth * 2);
        for (int i = 0; i < depth; i++) indent.append(' ');

        CharSequence className = node.getClassName();
        CharSequence viewId = node.getViewIdResourceName();
        String label = AccessibilityTree.textOf(node);
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        out.append(indent).append("- ")
                .append(simpleName(className)).append(' ');
        if (viewId != null) out.append("id=").append(viewId).append(' ');
        if (!label.isEmpty()) out.append("label=\"").append(oneLine(label)).append("\" ");
        out.append("bounds=").append(bounds.left).append(',').append(bounds.top)
                .append('-').append(bounds.right).append(',').append(bounds.bottom);
        if (node.isClickable()) out.append(" clickable");
        if (node.isCheckable()) out.append(" checkable");
        if (node.isChecked()) out.append(" checked");
        if (node.isScrollable()) out.append(" scrollable");
        if (node.isSelected()) out.append(" selected");
        out.append('\n');

        int children = node.getChildCount();
        for (int i = 0; i < children; i++) dump(node.getChild(i), out, depth + 1);
    }

    private static String simpleName(CharSequence className) {
        if (className == null) return "?";
        String value = className.toString();
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(dot + 1);
    }

    private static String oneLine(String value) {
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    /** Newest first. */
    public static File[] list(Context context) {
        File[] files = context.getFilesDir().listFiles((dir, name) -> name.startsWith(PREFIX)
                && name.endsWith(".txt"));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        return files;
    }

    public static boolean clear(Context context) {
        boolean allDeleted = true;
        for (File file : list(context)) {
            if (!file.delete()) allDeleted = false;
        }
        return allDeleted;
    }

    private static void prune(Context context) {
        File[] files = list(context);
        for (int i = MAX_FILES; i < files.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }
}
