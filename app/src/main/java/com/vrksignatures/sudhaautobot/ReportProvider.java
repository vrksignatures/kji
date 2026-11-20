package com.vrksignatures.sudhaautobot;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lets another app (WhatsApp, mail...) read one of Aakriti's report files - and nothing
 * else - when the person shares it. Read-only, not exported; a reader is let in for one
 * file at a time by the share itself.
 */
public final class ReportProvider extends ContentProvider {

    static Uri uriFor(Context context, File file) {
        return new Uri.Builder().scheme("content")
                .authority(context.getPackageName() + ".reports")
                .appendPath(file.getName()).build();
    }

    /** The report file a uri names, or null. Only plain file names inside the reports folder. */
    private File resolve(Uri uri) {
        Context context = getContext();
        String name = uri == null ? null : uri.getLastPathSegment();
        if (context == null || name == null || !name.matches("report-[0-9]{8}-[0-9]{6}\\.(jpg|txt)")) {
            return null;
        }
        File file = new File(Aakriti.reportsFolder(context), name);
        return file.isFile() ? file : null;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolve(uri);
        if (file == null || mode == null || !mode.equals("r")) throw new FileNotFoundException("no such report");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        File file = resolve(uri);
        String[] columns = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        if (file == null) return cursor;
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        String name = uri == null ? "" : String.valueOf(uri.getLastPathSegment());
        return name.endsWith(".jpg") ? "image/jpeg" : "text/plain";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
