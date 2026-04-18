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

/**
 * SyncWorker — Recursive folder sync
 * সিলেক্ট করা ফোল্ডারের সম্পূর্ণ structure Drive-এ হুবহু তৈরি হবে।
 */
public class SyncWorker extends Worker {

    private static final String TAG = "DriveSync_Worker";

    public static final String KEY_FOLDER_URIS = "folder_uris";
    public static final String KEY_MANUAL      = "is_manual";
    public static final String PROG_PCT        = "progress_pct";
    public static final String PROG_MSG        = "progress_msg";
    public static final String OUT_SYNCED      = "synced_count";
    public static final String OUT_DURATION_MS = "duration_ms";
    public static final String OUT_BYTES       = "total_bytes";
    public static final String OUT_LOG         = "file_log";

    private static long   sLastSyncMs  = 0L;
    private int           mTotalSynced = 0;
    private long          mTotalBytes  = 0;
    private StringBuilder mLog         = new StringBuilder();

    public SyncWorker(@NonNull Context ctx, @NonNull WorkerParameters p) {
        super(ctx, p);
    }

    @NonNull
    @Override
    public Result doWork() {
        String[] uriArr  = getInputData().getStringArray(KEY_FOLDER_URIS);
        boolean isManual = getInputData().getBoolean(KEY_MANUAL, false);

        if (uriArr == null || uriArr.length == 0) return Result.failure();

        DriveServiceHelper helper = DriveServiceHelper.getInstance();
        if (!helper.isReady()) return Result.retry();

        long start   = System.currentTimeMillis();
        mTotalSynced = 0;
        mTotalBytes  = 0;
        mLog         = new StringBuilder();

        // DriveSync_Backup root folder ID নিশ্চিত করা
        String rootId = helper.ensureRootFolder();
        if (rootId == null) return Result.retry();

        for (int i = 0; i < uriArr.length; i++) {
            Uri    treeUri  = Uri.parse(uriArr[i]);
            String rootName = getFolderDisplayName(uriArr[i]);

            setProgress("📁 " + rootName + " scan হচ্ছে...",
                    (int)(i * 100.0 / uriArr.length));

            // Drive-এ root-level folder তৈরি (যেমন: DriveSync_Backup/baby/)
            String driveFolderId = helper.getOrCreateSubFolder(rootId, rootName);
            if (driveFolderId == null) continue;

            syncRecursive(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
                driveFolderId,
                helper, isManual, rootName
            );
        }

        long duration = System.currentTimeMillis() - start;
        if (!isManual) sLastSyncMs = System.currentTimeMillis();
        setProgress("✅ " + mTotalSynced + " ফাইল sync হয়েছে", 100);

        return Result.success(new Data.Builder()
                .putInt(OUT_SYNCED,      mTotalSynced)
                .putLong(OUT_DURATION_MS, duration)
                .putLong(OUT_BYTES,       mTotalBytes)
                .putString(OUT_LOG,       mLog.toString())
                .build());
    }

    /**
     * Recursive sync — sub-folder structure Drive-এ হুবহু তৈরি হবে।
     *
     * @param treeUri       SAF tree URI (selection root)
     * @param docId         বর্তমান folder-এর SAF document ID
     * @param driveFolderId Drive-এ বর্তমান folder-এর ID
     * @param helper        DriveServiceHelper
     * @param isManual      true = সব ফাইল; false = শুধু নতুন/পরিবর্তিত
     * @param displayPath   Log display path (e.g. "baby/photos")
     */
    private void syncRecursive(Uri treeUri, String docId,
            String driveFolderId, DriveServiceHelper helper,
            boolean isManual, String displayPath) {

        Context ctx = getApplicationContext();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);

        String[] proj = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        };

        try (Cursor c = ctx.getContentResolver()
                .query(childrenUri, proj, null, null, null)) {

            if (c == null) return;

            while (c.moveToNext()) {
                String childId   = c.getString(0);
                String name      = c.getString(1);
                long   modified  = c.getLong(2);
                String mime      = c.getString(3);
                long   size      = c.getLong(4);

                if (name == null || name.isEmpty()) continue;

                boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);

                if (isDir) {
                    // ── Sub-folder: Drive-এ same নামে folder তৈরি → ভেতরে ঢোকো ──
                    String subDriveId = helper.getOrCreateSubFolder(driveFolderId, name);
                    if (subDriveId != null) {
                        syncRecursive(treeUri, childId, subDriveId,
                                helper, isManual, displayPath + "/" + name);
                    }
                } else {
                    // ── File ──
                    if (!isManual && modified <= sLastSyncMs) continue;

                    Uri    fileUri  = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId);
                    String fileMime = (mime != null && !mime.isEmpty())
                            ? mime : DriveServiceHelper.getMime(name);
                    String path     = displayPath + "/" + name;

                    setProgress("⬆ " + path + " (" + formatSize(size) + ")", -1);

                    boolean ok = helper.uploadOrUpdateToFolder(
                            ctx, fileUri, name, fileMime, size, driveFolderId);

                    if (ok) {
                        mTotalSynced++;
                        mTotalBytes += size;
                        mLog.append("✅ ").append(path)
                            .append(" (").append(formatSize(size)).append(")\n");
                    } else {
                        mLog.append("❌ ").append(path).append("\n");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "syncRecursive [" + displayPath + "]: " + e.getMessage(), e);
        }
    }

    private void setProgress(String msg, int pct) {
        Data.Builder b = new Data.Builder().putString(PROG_MSG, msg);
        if (pct >= 0) b.putInt(PROG_PCT, pct);
        setProgressAsync(b.build());
    }

    private String getFolderDisplayName(String uriStr) {
        Uri uri = Uri.parse(uriStr);
        String seg = uri.getLastPathSegment();
        if (seg != null && seg.contains(":"))
            return seg.substring(seg.lastIndexOf(':') + 1);
        return seg != null ? seg : "Folder";
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1048576) return (bytes / 1024) + "KB";
        return String.format(java.util.Locale.getDefault(), "%.1fMB", bytes / 1048576.0);
    }
}
