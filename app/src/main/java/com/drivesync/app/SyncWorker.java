package com.drivesync.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * SyncWorker
 * ══════════
 * WorkManager-এর periodic background worker।
 * শুধুমাত্র Wi-Fi + Charging থাকলে চলে।
 * ফোল্ডার scan করে নতুন/পরিবর্তিত ফাইল Drive-এ পাঠায়।
 */
public class SyncWorker extends Worker {

    private static final String TAG = "DriveSync_Worker";

    // Input data key
    public static final String KEY_FOLDER_URI = "folder_uri";

    // শেষ successful sync-এর সময় (epoch ms)
    // static — process জীবিত থাকলে মনে থাকবে
    private static long sLastSyncMs = 0L;

    public SyncWorker(@NonNull Context ctx, @NonNull WorkerParameters p) {
        super(ctx, p);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "⚙ Worker চালু…");

        String uriStr = getInputData().getString(KEY_FOLDER_URI);
        if (uriStr == null || uriStr.isEmpty()) {
            Log.e(TAG, "No folder URI — giving up.");
            return Result.failure();
        }

        DriveServiceHelper helper = DriveServiceHelper.getInstance();
        if (!helper.isReady()) {
            Log.w(TAG, "Drive not ready — retry.");
            return Result.retry();
        }

        try {
            int n = syncFolder(Uri.parse(uriStr), helper);
            Log.d(TAG, "✅ Sync done — " + n + " file(s) sent.");
            sLastSyncMs = System.currentTimeMillis();
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "doWork error: " + e.getMessage(), e);
            return Result.retry();
        }
    }

    /**
     * SAF ফোল্ডার scan করে পরিবর্তিত ফাইল upload/update করা।
     */
    private int syncFolder(Uri folderUri, DriveServiceHelper helper) {
        int count = 0;
        Context ctx = getApplicationContext();

        // SAF children URI
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                folderUri,
                DocumentsContract.getTreeDocumentId(folderUri));

        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
        };

        try (Cursor cur = ctx.getContentResolver()
                .query(childrenUri, projection, null, null, null)) {

            if (cur == null) { Log.e(TAG, "Cursor null"); return 0; }

            while (cur.moveToNext()) {
                String docId    = cur.getString(0);
                String name     = cur.getString(1);
                long   modified = cur.getLong(2);
                String mime     = cur.getString(3);

                // Sub-folder skip
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) continue;

                // আগেই sync হয়েছে এমন ফাইল skip
                if (modified <= sLastSyncMs) continue;

                Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, docId);
                String fileMime = (mime != null && !mime.isEmpty())
                        ? mime : DriveServiceHelper.getMime(name);

                boolean ok = helper.uploadOrUpdate(ctx, fileUri, name, fileMime);
                Log.d(TAG, (ok ? "✅ " : "❌ ") + name);
                if (ok) count++;
            }

        } catch (Exception e) {
            Log.e(TAG, "syncFolder: " + e.getMessage(), e);
        }

        return count;
    }
}
