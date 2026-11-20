package com.vrksignatures.sudhaautobot;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * One JSON file per local day, e.g. {@code files/ledger-20251117.json}.
 *
 * <p>Structure:</p>
 * <pre>
 * {
 *   "version": 1,
 *   "sessionDate": "2025-11-17",
 *   "records": { "SMR101": { "status": "SUCCESS", "updatedAt": 1763..., "detail": "..." } },
 *   "pending": { "retailerId": "SMR101", "phase": "PLACE_TAP_PENDING", "createdAt": 1763... } | null
 * }
 * </pre>
 *
 * <p>Two properties matter for correctness:</p>
 * <ol>
 *   <li><b>Never process twice.</b> Any record at all means "handled today", so a
 *       pause/restart cannot reopen a retailer that already succeeded, was skipped
 *       or failed.</li>
 *   <li><b>Never place twice.</b> {@link #markPending(String)} is flushed to disk
 *       <em>before</em> PLACE ORDER is tapped. If the process dies mid-payment the
 *       marker survives, the service refuses to start a new retailer and refuses to
 *       re-tap Pay; a human must reconcile it.</li>
 * </ol>
 *
 * <p>Writes go to a temp file, are fsynced, then atomically renamed, so a crash can
 * never leave a half-written ledger.</p>
 */
public final class DailyLedger {
    private static final String TAG = "DailyLedger";
    private static final int VERSION = 1;
    private static final DateTimeFormatter FILE_DAY = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US);
    private static final DateTimeFormatter ISO_DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final File file;
    private JSONObject data;
    /** True when the ledger existed but could not be parsed; blocks unsafe actions. */
    private boolean corrupt;

    public DailyLedger(Context context) {
        this(context, LocalDate.now(ZoneId.systemDefault()));
    }

    DailyLedger(Context context, LocalDate day) {
        String fileDay = day.format(FILE_DAY);
        file = new File(context.getFilesDir(), "ledger-" + fileDay + ".json");
        data = load(day.format(ISO_DAY));
    }

    /** Absolute path of today's ledger; shown in MainActivity for support. */
    public File file() {
        return file;
    }

    public LocalDate sessionDate() {
        try {
            return LocalDate.parse(data.optString("sessionDate"), ISO_DAY);
        } catch (Exception ignored) {
            return LocalDate.now();
        }
    }

    private JSONObject load(String expectedDate) {
        if (file.isFile()) {
            try (FileInputStream in = new FileInputStream(file)) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int read;
                while ((read = in.read(chunk)) > 0) buffer.write(chunk, 0, read);
                JSONObject loaded = new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
                if (expectedDate.equals(loaded.optString("sessionDate"))) {
                    if (loaded.optJSONObject("records") == null) loaded.put("records", new JSONObject());
                    return loaded;
                }
                Log.w(TAG, "Ledger date mismatch, starting a fresh file for " + expectedDate);
            } catch (Exception e) {
                // A ledger we cannot read might describe an in-flight order, so stay locked.
                corrupt = true;
                Log.e(TAG, "Ledger unreadable; treating any prior order as in flight", e);
            }
        }
        JSONObject fresh = new JSONObject();
        try {
            fresh.put("version", VERSION);
            fresh.put("sessionDate", expectedDate);
            fresh.put("records", new JSONObject());
            fresh.put("pending", JSONObject.NULL);
        } catch (JSONException ignored) {
            // Keys are constants; a failure here is impossible.
        }
        if (!corrupt) write(fresh);
        return fresh;
    }

    // ---------------------------------------------------------------- queries

    /** Any record (success, skip or error) means "do not touch again today". */
    public synchronized boolean isProcessed(String id) {
        return records().optJSONObject(id) != null;
    }

    public synchronized LedgerStatus statusOf(String id) {
        JSONObject entry = records().optJSONObject(id);
        if (entry == null) return null;
        try {
            return LedgerStatus.valueOf(entry.optString("status"));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public synchronized String detailOf(String id) {
        JSONObject entry = records().optJSONObject(id);
        return entry == null ? null : entry.optString("detail", "");
    }

    /** Count of records for a status bucket: SUCCESS, SKIPPED or ERROR. */
    public synchronized int countBucket(String bucket) {
        int total = 0;
        JSONObject all = records();
        for (java.util.Iterator<String> keys = all.keys(); keys.hasNext(); ) {
            JSONObject entry = all.optJSONObject(keys.next());
            if (entry == null) continue;
            LedgerStatus status = parse(entry.optString("status"));
            if (status != null && status.bucket().equals(bucket)) total++;
        }
        return total;
    }

    public synchronized int count(LedgerStatus status) {
        int total = 0;
        JSONObject all = records();
        for (java.util.Iterator<String> keys = all.keys(); keys.hasNext(); ) {
            JSONObject entry = all.optJSONObject(keys.next());
            if (entry != null && status.name().equals(entry.optString("status"))) total++;
        }
        return total;
    }

    /** Every retailer handled today, whatever the outcome. */
    public synchronized int processedCount() {
        return records().length();
    }

    /** True when a retailer is recorded with an outcome that is neither success nor skip. */
    public synchronized boolean hasAnyError() {
        return countBucket("ERROR") > 0;
    }

    // ---------------------------------------------------------------- writes

    /**
     * Records a terminal outcome. Returns false when the write failed, in which case
     * the caller must stop rather than continue without durable memory.
     */
    public synchronized boolean record(String id, LedgerStatus status, String detail) {
        if (!RetailerPolicy.isRetailerId(id) || status == null) return false;
        JSONObject backup = copy(data);
        try {
            JSONObject entry = new JSONObject();
            entry.put("status", status.name());
            entry.put("updatedAt", System.currentTimeMillis());
            entry.put("detail", detail == null ? "" : detail);
            records().put(id, entry);
            // The list is worked through in order, and "next" means "after this one".
            data.put("lastHandled", id);
            // A terminal record always clears its own pending marker.
            if (id.equals(pendingId())) data.put("pending", JSONObject.NULL);
            if (write(data)) return true;
        } catch (JSONException e) {
            Log.e(TAG, "record() failed", e);
        }
        data = backup;
        return false;
    }

    /**
     * The retailer recorded most recently today, whatever the outcome; null when none.
     * The next retailer is the one after it in the list - see {@link RetailerSequence}.
     */
    public synchronized String lastHandledId() {
        String id = data.optString("lastHandled", "");
        return RetailerPolicy.isRetailerId(id) ? id : null;
    }

    /**
     * Forgets the position in the list, so the next pass starts again from the top. The
     * records themselves are kept: handled retailers stay handled.
     */
    public synchronized boolean clearLastHandled() {
        if (lastHandledId() == null) return true;
        JSONObject backup = copy(data);
        data.remove("lastHandled");
        if (write(data)) return true;
        data = backup;
        return false;
    }

    /**
     * Sets the place in the list by hand: the next retailer is the one after {@code id}.
     * Used when the operator gives a start point (see {@link StartPoint}); writes no record.
     */
    public synchronized boolean setLastHandled(String id) {
        if (!RetailerPolicy.isRetailerId(id)) return false;
        if (id.equals(lastHandledId())) return true;
        JSONObject backup = copy(data);
        try {
            data.put("lastHandled", id);
            if (write(data)) return true;
        } catch (JSONException e) {
            Log.e(TAG, "setLastHandled() failed", e);
        }
        data = backup;
        return false;
    }

    /**
     * Records a retailer as done before this run (it sits above the operator's start
     * point). Unlike {@link #record} it never replaces an existing record and does not move
     * the place in the list.
     */
    public synchronized boolean recordDoneBefore(String id, String detail) {
        if (!RetailerPolicy.isRetailerId(id) || isProcessed(id)) return false;
        JSONObject backup = copy(data);
        try {
            JSONObject entry = new JSONObject();
            entry.put("status", LedgerStatus.DONE_BEFORE.name());
            entry.put("updatedAt", System.currentTimeMillis());
            entry.put("detail", detail == null ? "" : detail);
            records().put(id, entry);
            if (write(data)) return true;
        } catch (JSONException e) {
            Log.e(TAG, "recordDoneBefore() failed", e);
        }
        data = backup;
        return false;
    }

    /** Flushes the pre-submit marker. Must succeed before PLACE ORDER is tapped. */
    public synchronized boolean markPending(String id, String phase) {
        if (!RetailerPolicy.isRetailerId(id)) return false;
        JSONObject backup = copy(data);
        try {
            JSONObject pending = new JSONObject();
            pending.put("retailerId", id);
            pending.put("phase", phase == null ? "READY_TO_PLACE" : phase);
            pending.put("createdAt", System.currentTimeMillis());
            data.put("pending", pending);
            if (write(data)) return true;
        } catch (JSONException e) {
            Log.e(TAG, "markPending() failed", e);
        }
        data = backup;
        return false;
    }

    public synchronized String pendingId() {
        JSONObject pending = data.optJSONObject("pending");
        if (pending == null) return null;
        String id = pending.optString("retailerId", "");
        return RetailerPolicy.isRetailerId(id) ? id : null;
    }

    public synchronized String pendingPhase() {
        JSONObject pending = data.optJSONObject("pending");
        return pending == null ? null : pending.optString("phase", "UNKNOWN");
    }

    public synchronized boolean hasPending() {
        return corrupt || data.optJSONObject("pending") != null;
    }

    /** Notes how far the in-flight order got, so a restart can reason about it. */
    public synchronized boolean setPendingPhase(String id, String phase) {
        JSONObject pending = data.optJSONObject("pending");
        if (pending == null || !id.equals(pending.optString("retailerId"))) return false;
        JSONObject backup = copy(data);
        try {
            pending.put("phase", phase);
            pending.put("updatedAt", System.currentTimeMillis());
            if (write(data)) return true;
        } catch (JSONException e) {
            Log.e(TAG, "setPendingPhase() failed", e);
        }
        data = backup;
        return false;
    }

    /** Only used when the pre-submit tap provably never fired. */
    public synchronized boolean clearPending() {
        JSONObject backup = copy(data);
        try {
            data.put("pending", JSONObject.NULL);
            if (write(data)) return true;
        } catch (JSONException e) {
            Log.e(TAG, "clearPending() failed", e);
        }
        data = backup;
        return false;
    }

    /**
     * Clears everything, including an in-flight marker.
     *
     * <p>Only for the operator pressing Reset. {@link #reset()} refuses while an order is
     * in flight — correct for an automatic run, but it also means a marker left behind by
     * an interrupted copy flow could never be cleared again, and every later START would
     * jump straight to "waiting for the payment screen" with nothing to pay. This gives the
     * human a way out.</p>
     */
    public synchronized boolean forceReset() {
        JSONObject backup = copy(data);
        try {
            data.put("records", new JSONObject());
            data.put("pending", JSONObject.NULL);
            data.remove("lastHandled");
            if (write(data)) return true;
        } catch (JSONException e) {
            Log.e(TAG, "forceReset() failed", e);
        }
        data = backup;
        return false;
    }

    /**
     * Forgets today's outcomes. Refused while an order is in flight, because that
     * would make the same retailer eligible again.
     */
    public synchronized boolean reset() {
        if (hasPending()) return false;
        JSONObject backup = copy(data);
        try {
            data.put("records", new JSONObject());
            data.put("pending", JSONObject.NULL);
            data.remove("lastHandled");
            if (write(data)) return true;
        } catch (JSONException e) {
            Log.e(TAG, "reset() failed", e);
        }
        data = backup;
        return false;
    }

    /** Compact one-line summary for the panel / log. */
    public synchronized String summary() {
        return "SUCCESS " + countBucket("SUCCESS")
                + " | SKIPPED " + countBucket("SKIPPED")
                + " | ERRORS " + countBucket("ERROR")
                + " | total " + processedCount();
    }

    // ---------------------------------------------------------------- internals

    private JSONObject records() {
        JSONObject records = data.optJSONObject("records");
        if (records == null) {
            records = new JSONObject();
            try {
                data.put("records", records);
            } catch (JSONException ignored) {
            }
        }
        return records;
    }

    private static LedgerStatus parse(String name) {
        try {
            return LedgerStatus.valueOf(name);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JSONObject copy(JSONObject value) {
        try {
            return new JSONObject(value.toString());
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    /** Atomic replace: write temp, fsync, rename over the real file. */
    private boolean write(JSONObject value) {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(value.toString(2).getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (Exception e) {
            Log.e(TAG, "Could not write ledger temp file", e);
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return false;
        }
        if (temp.renameTo(file)) return true;
        // Some filesystems refuse rename-over-existing; fall back to copy semantics.
        try (FileInputStream in = new FileInputStream(temp);
             FileOutputStream out = new FileOutputStream(file)) {
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) > 0) out.write(chunk, 0, read);
            out.getFD().sync();
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Could not replace ledger file", e);
            return false;
        }
    }
}
