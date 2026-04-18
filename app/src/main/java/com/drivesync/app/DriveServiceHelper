package com.drivesync.app;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.http.FileContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

public class DriveServiceHelper {

    private static final String TAG = "DriveSync_Helper";
    public static final String DRIVE_DIR = "DriveSync_Backup";
    private static final String APP_NAME = "DriveSync";

    public static String DRIVE_DIR_ID = null;
    private static volatile DriveServiceHelper sInstance;

    private Drive mService;
    private boolean mReady = false;

    private DriveServiceHelper() {}

    public static void init(Context ctx, GoogleSignInAccount account) {
        sInstance = new DriveServiceHelper();
        DRIVE_DIR_ID = null;
        sInstance.build(ctx.getApplicationContext(), account);
    }

    public static DriveServiceHelper getInstance() {
        if (sInstance == null) sInstance = new DriveServiceHelper();
        return sInstance;
    }

    public boolean isReady() {
        return mReady && mService != null;
    }

    private void build(Context ctx, GoogleSignInAccount account) {
        new Thread(() -> {
            try {
                GoogleAccountCredential cred = GoogleAccountCredential
                        .usingOAuth2(ctx, Collections.singletonList(DriveScopes.DRIVE));
                cred.setSelectedAccount(account.getAccount());
                mService = new Drive.Builder(
                        AndroidHttp.newCompatibleTransport(),
                        GsonFactory.getDefaultInstance(),
                        cred)
                        .setApplicationName(APP_NAME)
                        .build();
                DRIVE_DIR_ID = ensureRootFolder();
                mReady = true;
                Log.d(TAG, "Drive ready. rootId=" + DRIVE_DIR_ID);
            } catch (Exception e) {
                Log.e(TAG, "build error: " + e.getMessage(), e);
            }
        }).start();
    }

    public String ensureRootFolder() {
        if (DRIVE_DIR_ID != null) return DRIVE_DIR_ID;
        try {
            DRIVE_DIR_ID = getOrCreateSubFolder(null, DRIVE_DIR);
        } catch (Exception e) {
            Log.e(TAG, "ensureRootFolder: " + e.getMessage());
        }
        return DRIVE_DIR_ID;
    }

    public String getOrCreateSubFolder(String parentId, String name) {
        if (mService == null) return null;
        try {
            String parentQ = (parentId != null)
                    ? " and '" + parentId + "' in parents"
                    : " and 'root' in parents";
            String q = "mimeType='application/vnd.google-apps.folder'"
                    + " and name='" + esc(name) + "'"
                    + parentQ
                    + " and trashed=false";
            FileList result = mService.files().list()
                    .setQ(q)
                    .setFields("files(id)")
                    .execute();
            if (result.getFiles() != null && !result.getFiles().isEmpty()) {
                return result.getFiles().get(0).getId();
            }
            File meta = new File();
            meta.setName(name);
            meta.setMimeType("application/vnd.google-apps.folder");
            if (parentId != null) {
                meta.setParents(Collections.singletonList(parentId));
            }
            return mService.files().create(meta).setFields("id").execute().getId();
        } catch (IOException e) {
            Log.e(TAG, "getOrCreateSubFolder [" + name + "]: " + e.getMessage());
            return null;
        }
    }

    public boolean uploadOrUpdateToFolder(Context ctx, Uri fileUri,
            String name, String mime, long size, String driveFolderId) {
        if (!isReady()) return false;
        java.io.File tmp = null;
        try {
            tmp = copyToTemp(ctx, fileUri, name);
            if (tmp == null) return false;
            String existingId = findFileInFolder(name, driveFolderId);
            if (existingId != null) {
                mService.files()
                        .update(existingId, new File(), new FileContent(mime, tmp))
                        .setFields("id,name,modifiedTime")
                        .execute();
                Log.d(TAG, "Updated: " + name);
            } else {
                File meta = new File();
                meta.setName(name);
                meta.setParents(Collections.singletonList(driveFolderId));
                mService.files()
                        .create(meta, new FileContent(mime, tmp))
                        .setFields("id,name")
                        .execute();
                Log.d(TAG, "Uploaded: " + name);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "uploadOrUpdateToFolder [" + name + "]: " + e.getMessage(), e);
            return false;
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    private String findFileInFolder(String name, String folderId) throws IOException {
        String q = "name='" + esc(name) + "'"
                + " and '" + folderId + "' in parents"
                + " and trashed=false"
                + " and mimeType!='application/vnd.google-apps.folder'";
        FileList result = mService.files().list()
                .setQ(q)
                .setFields("files(id)")
                .execute();
        if (result.getFiles() != null && !result.getFiles().isEmpty()) {
            return result.getFiles().get(0).getId();
        }
        return null;
    }

    private java.io.File copyToTemp(Context ctx, Uri uri, String name) {
        try {
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            java.io.File tmp = new java.io.File(ctx.getCacheDir(), "ds_" + name);
            FileOutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
            in.close();
            out.close();
            return tmp;
        } catch (IOException e) {
            Log.e(TAG, "copyToTemp: " + e.getMessage());
            return null;
        }
    }

    private String esc(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    public static String getMime(String name) {
        if (name == null) return "application/octet-stream";
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        switch (name.substring(dot + 1).toLowerCase()) {
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "png":  return "image/png";
            case "gif":  return "image/gif";
            case "webp": return "image/webp";
            case "bmp":  return "image/bmp";
            case "pdf":  return "application/pdf";
            case "doc":  return "application/msword";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls":  return "application/vnd.ms-excel";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt":  return "application/vnd.ms-powerpoint";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "txt":  return "text/plain";
            case "csv":  return "text/csv";
            case "json": return "application/json";
            case "mp3":  return "audio/mpeg";
            case "wav":  return "audio/wav";
            case "m4a":  return "audio/mp4";
            case "mp4":  return "video/mp4";
            case "mkv":  return "video/x-matroska";
            case "3gp":  return "video/3gpp";
            case "avi":  return "video/x-msvideo";
            case "zip":  return "application/zip";
            case "rar":  return "application/x-rar-compressed";
            case "apk":  return "application/vnd.android.package-archive";
            default:     return "application/octet-stream";
        }
    }
}
