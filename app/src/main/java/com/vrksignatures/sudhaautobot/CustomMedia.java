package com.vrksignatures.sudhaautobot;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Brings the person's own picture for the end-of-run card into the app, from a file on
 * the phone. Pictures only: the app plays no sounds of its own (see {@link MusicEngine}
 * for the songs). The automation never calls this class and it opens no connection.
 */
final class CustomMedia {

    static final long MAX_PICTURE_BYTES = 6L * 1024 * 1024;

    private CustomMedia() {
    }

    /**
     * Copies a picture the person picked on the phone into the app.
     *
     * @return null when saved, otherwise what to tell the person
     */
    static String importFile(Context context, Uri picked, File target, long limit) {
        if (picked == null) return "Nothing was chosen.";
        File part = new File(target.getParentFile(), target.getName() + ".part");
        try (InputStream in = context.getContentResolver().openInputStream(picked)) {
            if (in == null) return "That file could not be opened.";
            String problem = copy(in, part, limit);
            if (problem != null) return problem;
            if (target.exists() && !target.delete()) return "Could not replace the old file.";
            if (!part.renameTo(target)) return "Could not save the file.";
            return null;
        } catch (Exception e) {
            return "That file could not be read.";
        } finally {
            if (part.exists()) part.delete();
        }
    }

    private static String copy(InputStream in, File to, long limit) throws IOException {
        File parent = to.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return "The app's storage is not available.";
        }
        long total = 0;
        try (OutputStream out = new FileOutputStream(to)) {
            byte[] chunk = new byte[16 * 1024];
            int read;
            while ((read = in.read(chunk)) > 0) {
                total += read;
                if (total > limit) {
                    return "That file is too big. The limit is " + megabytes(limit) + ".";
                }
                out.write(chunk, 0, read);
            }
        }
        return total == 0 ? "That file is empty." : null;
    }

    static String megabytes(long bytes) {
        if (bytes < 1024 * 1024) return Math.max(1, bytes / 1024) + " KB";
        return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
    }
}
