package com.drivesync.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.ArrayList;
import java.util.List;

/**
 * SyncWorker — একাধিক ফোল্ডার support, progress tracking, file details
 */
public class SyncWorker extends Worker {

    private static final String TAG = "DriveSync_Worker";

    // Input keys
    public static final String KEY_FOLDER_URIS = "folder_uris";
    public static final String KEY_MANUAL      = "is_manual";

    // Progress keys
    public static final String PROG_PCT = "progress_pct";
    public static final String PROG_MSG = "progress_msg";

    // Output keys
    public static final String OUT_SYNCED      = "synced_count";
    public static final String OUT_DURATION_MS = "duration_ms";
    public static final String OUT_BYTES       = "total_bytes";
    public static final String OUT_LOG         = "file_log";

    // শেষ sync সময়
    private static long sLastSyncMs = 0L;

    public SyncWorker(@NonNull Context ctx, @NonNull WorkerParameters p) {
        super(ctx, p);
    }

    @NonNull
    @Override
    public Result doWork() {
        String[] uriArr = getInputData().getStringArray(KEY_FOLDER_URIS);
        boolean isManual = getInputData().getBoolean(KEY_MANUAL, false);

        if (uriArr == null || uriArr.length == 0) {
            return Result.failure();
        }

        DriveServiceHelper helper = DriveServiceHelper.getInstance();
        if (!helper.isReady()) {
            setProgress("⏳ Drive সংযোগ হচ্ছে...", 0);
            return Result.retry();
        }

        long startTime = System.currentTimeMillis();
        int totalSynced = 0;
        long totalBytes = 0;
        StringBuilder fileLog = new StringBuilder();

        // সব ফোল্ডার একে একে scan করা
        for (int fi = 0; fi < uriArr.length; fi++) {
            String uriStr = uriArr[fi];
            String folderName = getFolderName(uriStr);
            setProgress("📁 " + folderName + " scan হচ্ছে...",
                    (int)((fi * 100.0) / uriArr.length));

            List<FileInfo> files = scanFolder(Uri.parse(uriStr), isManual);

            for (int i = 0; i < files.size(); i++) {
                FileInfo file = files.get(i);
                int pct = (int)(((fi + (double)i/files.size()) / uriArr.length) * 100);
                setProgress("⬆ " + file.name + " (" + formatSize(file.size) + ")", pct);

                boolean ok = helper.uploadOrUpdate(
                        getApplicationContext(), file.uri, file.name, file.mime, file.size);

                if (ok) {
                    totalSynced++;
                    totalBytes += file.size;
                    fileLog.append("✅ ").append(file.name)
                           .append(" (").append(formatSize(file.size)).append(")\n");
                    Log.d(TAG, "✅ " + file.name);
                } else {
                    fileLog.append("❌ ").append(file.name).append("\n");
                    Log.e(TAG, "❌ " + file.name);
                }
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        if (!isManual) sLastSyncMs = System.currentTimeMillis();

        setProgress("✅ সম্পন্ন! " + totalSynced + " ফাইল।", 100);

        Data output = new Data.Builder()
                .putInt(OUT_SYNCED, totalSynced)
                .putLong(OUT_DURATION_MS, duration)
                .putLong(OUT_BYTES, totalBytes)
                .putString(OUT_LOG, fileLog.toString())
                .build();

        return Result.success(output);
    }

    /**
     * SAF folder scan — সব ফাইল খুঁজে বের করা
     */
    private List<FileInfo> scanFolder(Uri folderUri, boolean isManual) {
        List<FileInfo> result = new ArrayList<>();
        Context ctx = getApplicationContext();

        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                folderUri, DocumentsContract.getTreeDocumentId(folderUri));

        String[] proj = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
        };

        try (Cursor cur = ctx.getContentResolver()
                .query(childrenUri, proj, null, null, null)) {
            if (cur == null) return result;

            while (cur.moveToNext()) {
                String docId    = cur.getString(0);
                String name     = cur.getString(1);
                long   modified = cur.getLong(2);
                String mime     = cur.getString(3);
                long   size     = cur.getLong(4);

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) continue;

                // Manual sync = সব ফাইল। Auto sync = শুধু নতুন/পরিবর্তিত।
                if (!isManual && modified <= sLastSyncMs) continue;

                Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, docId);
                String fileMime = (mime != null && !mime.isEmpty())
                        ? mime : DriveServiceHelper.getMime(name);

                result.add(new FileInfo(name, fileUri, fileMime, size));
            }
        } catch (Exception e) {
            Log.e(TAG, "scanFolder: " + e.getMessage());
        }
        return result;
    }

    private void setProgress(String msg, int pct) {
        Data prog = new Data.Builder()
                .putInt(PROG_PCT, pct)
                .putString(PROG_MSG, msg)
                .build();
        setProgressAsync(prog);
    }

    private String getFolderName(String uriStr) {
        Uri uri = Uri.parse(uriStr);
        String seg = uri.getLastPathSegment();
        if (seg != null && seg.contains(":")) return seg.substring(seg.lastIndexOf(':') + 1);
        return seg != null ? seg : "Folder";
    }

    /** Bytes → human readable */
    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return String.format(java.util.Locale.getDefault(), "%.1fMB", bytes / 1048576.0);
    }

    // ── Inner class ────────────────────────────────────
    static class FileInfo {
        String name, mime;
        Uri    uri;
        long   size;
        FileInfo(String n, Uri u, String m, long s) {
            name=n; uri=u; mime=m; size=s;
        }
    }
}
