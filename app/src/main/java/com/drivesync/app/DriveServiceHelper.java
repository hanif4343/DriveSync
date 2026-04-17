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

    private static final String TAG       = "DriveSync_Helper";
    public  static final String DRIVE_DIR = "DriveSync_Backup";
    private static final String APP_NAME  = "DriveSync";

    private static volatile DriveServiceHelper sInstance;
    private Drive  mService;
    private String mFolderID;

    private DriveServiceHelper() {}

    public static void init(Context ctx, GoogleSignInAccount account) {
        sInstance = new DriveServiceHelper();
        sInstance.build(ctx.getApplicationContext(), account);
    }

    public static DriveServiceHelper getInstance() {
        if (sInstance == null) sInstance = new DriveServiceHelper();
        return sInstance;
    }

    public boolean isReady() { return mService != null && mFolderID != null; }

    private void build(Context ctx, GoogleSignInAccount account) {
        new Thread(() -> {
            try {
                GoogleAccountCredential cred = GoogleAccountCredential
                        .usingOAuth2(ctx, Collections.singletonList(DriveScopes.DRIVE));
                cred.setSelectedAccount(account.getAccount());

                mService = new Drive.Builder(
                        AndroidHttp.newCompatibleTransport(),
                        GsonFactory.getDefaultInstance(), cred)
                        .setApplicationName(APP_NAME).build();

                mFolderID = getOrCreateFolder();
                Log.d(TAG, "Drive ready ✅ folder=" + mFolderID);
            } catch (Exception e) {
                Log.e(TAG, "build error: " + e.getMessage(), e);
            }
        }).start();
    }

    private String getOrCreateFolder() throws IOException {
        String q = "mimeType='application/vnd.google-apps.folder'"
                + " and name='" + DRIVE_DIR + "' and trashed=false";
        FileList r = mService.files().list().setQ(q).setFields("files(id)").execute();
        if (r.getFiles() != null && !r.getFiles().isEmpty())
            return r.getFiles().get(0).getId();
        File meta = new File();
        meta.setName(DRIVE_DIR);
        meta.setMimeType("application/vnd.google-apps.folder");
        return mService.files().create(meta).setFields("id").execute().getId();
    }

    /**
     * Upload বা Update — file size সহ
     */
    public boolean uploadOrUpdate(Context ctx, Uri fileUri,
                                   String name, String mime, long size) {
        if (!isReady()) return false;
        java.io.File tmp = null;
        try {
            tmp = copyToTemp(ctx, fileUri, name);
            if (tmp == null) return false;
            String existingId = findFile(name);
            if (existingId != null) return doUpdate(existingId, tmp, mime);
            return doUpload(name, tmp, mime);
        } catch (Exception e) {
            Log.e(TAG, name + ": " + e.getMessage(), e);
            return false;
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    private String findFile(String name) throws IOException {
        String q = "name='" + esc(name) + "' and '"
                + mFolderID + "' in parents and trashed=false";
        FileList r = mService.files().list().setQ(q).setFields("files(id)").execute();
        if (r.getFiles() != null && !r.getFiles().isEmpty())
            return r.getFiles().get(0).getId();
        return null;
    }

    private boolean doUpload(String name, java.io.File tmp, String mime) throws IOException {
        File meta = new File();
        meta.setName(name);
        meta.setParents(Collections.singletonList(mFolderID));
        mService.files().create(meta, new FileContent(mime, tmp))
                .setFields("id,name").execute();
        Log.d(TAG, "Uploaded: " + name);
        return true;
    }

    private boolean doUpdate(String id, java.io.File tmp, String mime) throws IOException {
        mService.files().update(id, new File(), new FileContent(mime, tmp))
                .setFields("id,name,modifiedTime").execute();
        Log.d(TAG, "Updated: " + id);
        return true;
    }

    private java.io.File copyToTemp(Context ctx, Uri uri, String name) {
        try {
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            java.io.File tmp = new java.io.File(ctx.getCacheDir(), "ds_" + name);
            FileOutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
            in.close(); out.close();
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
            case "jpg": case "jpeg": return "image/jpeg";
            case "png":  return "image/png";
            case "gif":  return "image/gif";
            case "webp": return "image/webp";
            case "pdf":  return "application/pdf";
            case "doc":  return "application/msword";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls":  return "application/vnd.ms-excel";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "txt":  return "text/plain";
            case "mp3":  return "audio/mpeg";
            case "mp4":  return "video/mp4";
            case "zip":  return "application/zip";
            case "apk":  return "application/vnd.android.package-archive";
            default:     return "application/octet-stream";
        }
    }
}
