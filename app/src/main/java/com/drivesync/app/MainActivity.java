package com.drivesync.app;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.work.*;
import com.google.android.gms.auth.api.signin.*;
import com.google.android.gms.common.api.Scope;
import com.google.api.services.drive.DriveScopes;
import org.json.JSONArray;
import org.json.JSONException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS        = "DriveSync_Prefs";
    private static final String KEY_FOLDERS  = "folder_uris_json";   // JSON array
    private static final String KEY_LAST_LOG = "last_sync_log";
    private static final String WORK_TAG     = "DriveSync_Periodic";
    private static final String WORK_NOW_TAG = "DriveSync_Now";
    private static final int    PERM_REQ     = 101;

    // ── UI ──────────────────────────────────────────
    private TextView   tvAccount, tvStatus, tvLastSync;
    private Button     btnSignIn, btnAddFolder, btnSyncNow, btnStart, btnStop;
    private LinearLayout llFolders;
    private ProgressBar  progressBar;
    private TextView     tvProgress;
    private ScrollView   svLog;
    private TextView     tvLog;

    // ── Auth ──────────────────────────────────────────
    private GoogleSignInClient  mSignInClient;
    private GoogleSignInAccount mAccount;

    // ── Prefs ──────────────────────────────────────────
    private SharedPreferences mPrefs;
    private List<String>      mFolderUris = new ArrayList<>();

    // ── Launchers ──────────────────────────────────────
    private ActivityResultLauncher<Intent> mSignInLauncher;
    private ActivityResultLauncher<Uri>    mFolderLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        bindViews();
        buildSignInClient();
        registerLaunchers();
        setListeners();
        loadFolders();
        restoreSession();
        askPermission();
        refreshSyncLog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSyncLog();
    }

    // ── Bind ──────────────────────────────────────────
    private void bindViews() {
        tvAccount   = findViewById(R.id.tv_account);
        tvStatus    = findViewById(R.id.tv_status);
        tvLastSync  = findViewById(R.id.tv_last_sync);
        btnSignIn   = findViewById(R.id.btn_sign_in);
        btnAddFolder= findViewById(R.id.btn_add_folder);
        btnSyncNow  = findViewById(R.id.btn_sync_now);
        btnStart    = findViewById(R.id.btn_start);
        btnStop     = findViewById(R.id.btn_stop);
        llFolders   = findViewById(R.id.ll_folders);
        progressBar = findViewById(R.id.progress_bar);
        tvProgress  = findViewById(R.id.tv_progress);
        svLog       = findViewById(R.id.sv_log);
        tvLog       = findViewById(R.id.tv_log);

        btnAddFolder.setEnabled(false);
        btnSyncNow.setEnabled(false);
        btnStart.setEnabled(false);
        btnStop.setEnabled(false);
        progressBar.setVisibility(View.GONE);
        tvProgress.setVisibility(View.GONE);
    }

    // ── Google Sign-In ────────────────────────────────
    private void buildSignInClient() {
        GoogleSignInOptions gso = new GoogleSignInOptions
                .Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope(DriveScopes.DRIVE))
                .build();
        mSignInClient = GoogleSignIn.getClient(this, gso);
    }

    private void registerLaunchers() {
        mSignInLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                r -> GoogleSignIn.getSignedInAccountFromIntent(r.getData())
                        .addOnSuccessListener(this::onSignedIn)
                        .addOnFailureListener(e -> setStatus("❌ লগইন ব্যর্থ: " + e.getMessage())));

        mFolderLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                uri -> {
                    if (uri == null) return;
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION |
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    addFolder(uri.toString());
                });
    }

    // ── Listeners ─────────────────────────────────────
    private void setListeners() {
        btnSignIn.setOnClickListener(v -> {
            if (mAccount == null) {
                mSignInLauncher.launch(mSignInClient.getSignInIntent());
                setStatus("⏳ লগইন হচ্ছে...");
            } else {
                signOut();
            }
        });

        btnAddFolder.setOnClickListener(v -> mFolderLauncher.launch(null));

        // ── Manual Sync (works on mobile data, no charging needed) ──
        btnSyncNow.setOnClickListener(v -> {
            if (mFolderUris.isEmpty()) {
                Toast.makeText(this, "আগে ফোল্ডার যোগ করুন!", Toast.LENGTH_SHORT).show();
                return;
            }
            startManualSync();
        });

        btnStart.setOnClickListener(v -> {
            if (mFolderUris.isEmpty()) {
                Toast.makeText(this, "আগে ফোল্ডার যোগ করুন!", Toast.LENGTH_SHORT).show();
                return;
            }
            startAutoSync();
        });

        btnStop.setOnClickListener(v -> stopAutoSync());
    }

    // ── Sign-In / Out ─────────────────────────────────
    private void onSignedIn(GoogleSignInAccount account) {
        mAccount = account;
        tvAccount.setText("👤 " + account.getEmail());
        btnSignIn.setText("লগআউট করুন");
        btnAddFolder.setEnabled(true);
        btnSyncNow.setEnabled(!mFolderUris.isEmpty());
        btnStart.setEnabled(!mFolderUris.isEmpty());
        DriveServiceHelper.init(getApplicationContext(), account);
        setStatus("✅ লগইন সফল! ফোল্ডার বেছে নিন বা Sync করুন।");
    }

    private void signOut() {
        stopAutoSync();
        mSignInClient.signOut().addOnCompleteListener(this, t -> {
            mAccount = null;
            tvAccount.setText("লগইন করা হয়নি");
            btnSignIn.setText("Google দিয়ে লগইন করুন");
            btnAddFolder.setEnabled(false);
            btnSyncNow.setEnabled(false);
            btnStart.setEnabled(false);
            btnStop.setEnabled(false);
            setStatus("লগআউট হয়েছে।");
        });
    }

    // ── Folder Management ────────────────────────────
    private void loadFolders() {
        String json = mPrefs.getString(KEY_FOLDERS, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            mFolderUris.clear();
            for (int i = 0; i < arr.length(); i++) mFolderUris.add(arr.getString(i));
        } catch (JSONException e) { mFolderUris.clear(); }
        refreshFolderList();
    }

    private void saveFolders() {
        JSONArray arr = new JSONArray(mFolderUris);
        mPrefs.edit().putString(KEY_FOLDERS, arr.toString()).apply();
    }

    private void addFolder(String uriStr) {
        if (mFolderUris.contains(uriStr)) {
            Toast.makeText(this, "এই ফোল্ডার ইতিমধ্যে আছে!", Toast.LENGTH_SHORT).show();
            return;
        }
        mFolderUris.add(uriStr);
        saveFolders();
        refreshFolderList();
        btnSyncNow.setEnabled(true);
        btnStart.setEnabled(true);
        setStatus("✅ ফোল্ডার যোগ হয়েছে। Sync করুন।");
    }

    private void removeFolder(int index) {
        new AlertDialog.Builder(this)
                .setTitle("ফোল্ডার সরাবেন?")
                .setMessage("এই ফোল্ডারটি Sync তালিকা থেকে সরানো হবে।")
                .setPositiveButton("হ্যাঁ", (d, w) -> {
                    mFolderUris.remove(index);
                    saveFolders();
                    refreshFolderList();
                    if (mFolderUris.isEmpty()) {
                        btnSyncNow.setEnabled(false);
                        btnStart.setEnabled(false);
                    }
                })
                .setNegativeButton("না", null)
                .show();
    }

    private void refreshFolderList() {
        llFolders.removeAllViews();
        if (mFolderUris.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("কোনো ফোল্ডার নেই। নিচের বোতামে চাপুন।");
            empty.setTextColor(0xFF90A4AE);
            empty.setPadding(0, 8, 0, 8);
            llFolders.addView(empty);
            return;
        }
        for (int i = 0; i < mFolderUris.size(); i++) {
            final int idx = i;
            String uri = mFolderUris.get(i);
            String name = folderName(uri);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 6, 0, 6);

            TextView tv = new TextView(this);
            tv.setText("📁 " + name);
            tv.setTextColor(0xFF263238);
            tv.setTextSize(14);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tv.setLayoutParams(lp);

            Button del = new Button(this);
            del.setText("✕");
            del.setTextColor(0xFFB71C1C);
            del.setBackgroundColor(0x00000000);
            del.setPadding(8,0,8,0);
            del.setOnClickListener(v -> removeFolder(idx));

            row.addView(tv);
            row.addView(del);
            llFolders.addView(row);
        }
    }

    private String folderName(String uriStr) {
        Uri uri = Uri.parse(uriStr);
        String seg = uri.getLastPathSegment();
        if (seg != null && seg.contains(":")) return seg.substring(seg.lastIndexOf(':') + 1);
        return seg != null ? seg : uriStr;
    }

    // ── Manual Sync (no Wi-Fi/Charging constraint) ───
    private void startManualSync() {
        setStatus("⏳ Sync শুরু হচ্ছে...");
        showProgress(0, "প্রস্তুত হচ্ছে...");

        Data data = new Data.Builder()
                .putStringArray(SyncWorker.KEY_FOLDER_URIS,
                        mFolderUris.toArray(new String[0]))
                .putBoolean(SyncWorker.KEY_MANUAL, true)
                .build();

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setInputData(data)
                .addTag(WORK_NOW_TAG)
                .build();

        WorkManager wm = WorkManager.getInstance(this);
        wm.cancelAllWorkByTag(WORK_NOW_TAG);
        wm.enqueue(req);

        // Observer for progress
        wm.getWorkInfosByTagLiveData(WORK_NOW_TAG).observe(this, infos -> {
            if (infos == null || infos.isEmpty()) return;
            WorkInfo info = infos.get(0);
            if (info == null) return;

            Data progress = info.getProgress();
            int pct  = progress.getInt(SyncWorker.PROG_PCT, 0);
            String msg = progress.getString(SyncWorker.PROG_MSG);
            if (msg != null) showProgress(pct, msg);

            if (info.getState() == WorkInfo.State.SUCCEEDED) {
                Data out = info.getOutputData();
                int synced = out.getInt(SyncWorker.OUT_SYNCED, 0);
                long ms    = out.getLong(SyncWorker.OUT_DURATION_MS, 0);
                long bytes = out.getLong(SyncWorker.OUT_BYTES, 0);
                String log = out.getString(SyncWorker.OUT_LOG);
                hideProgress();
                saveSyncLog(synced, ms, bytes, log);
                refreshSyncLog();
                setStatus("✅ Sync সম্পন্ন! " + synced + " ফাইল আপলোড।");
            } else if (info.getState() == WorkInfo.State.FAILED) {
                hideProgress();
                setStatus("❌ Sync ব্যর্থ। আবার চেষ্টা করুন।");
            }
        });
    }

    // ── Auto Sync (Wi-Fi + Charging) ─────────────────
    private void startAutoSync() {
        Constraints c = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresCharging(true)
                .build();

        Data data = new Data.Builder()
                .putStringArray(SyncWorker.KEY_FOLDER_URIS,
                        mFolderUris.toArray(new String[0]))
                .putBoolean(SyncWorker.KEY_MANUAL, false)
                .build();

        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                SyncWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(c)
                .setInputData(data)
                .addTag(WORK_TAG)
                .build();

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                WORK_TAG, ExistingPeriodicWorkPolicy.REPLACE, req);

        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        setStatus("🔄 Auto Sync চালু!\nWi-Fi + Charging পেলে স্বয়ংক্রিয়ভাবে হবে।\nএখনই করতে 'Sync Now' চাপুন।");
    }

    private void stopAutoSync() {
        WorkManager.getInstance(this).cancelAllWorkByTag(WORK_TAG);
        btnStart.setEnabled(mAccount != null && !mFolderUris.isEmpty());
        btnStop.setEnabled(false);
        setStatus("⏹ Auto Sync বন্ধ।");
    }

    // ── Progress UI ──────────────────────────────────
    private void showProgress(int pct, String msg) {
        progressBar.setVisibility(View.VISIBLE);
        tvProgress.setVisibility(View.VISIBLE);
        progressBar.setProgress(pct);
        tvProgress.setText(msg);
    }

    private void hideProgress() {
        progressBar.setVisibility(View.GONE);
        tvProgress.setVisibility(View.GONE);
    }

    // ── Sync Log ─────────────────────────────────────
    private void saveSyncLog(int files, long ms, long bytes, String fileLog) {
        String time = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss",
                Locale.getDefault()).format(new Date());
        String duration = ms < 1000 ? ms + "ms" : (ms / 1000) + "s";
        String size = bytes < 1024 ? bytes + "B"
                : bytes < 1024*1024 ? (bytes/1024) + "KB"
                : String.format(Locale.getDefault(), "%.1fMB", bytes/1048576.0);

        String log = "🕐 " + time + "\n"
                + "📁 " + files + " ফাইল  |  📦 " + size + "  |  ⏱ " + duration + "\n"
                + (fileLog != null ? fileLog : "");
        mPrefs.edit().putString(KEY_LAST_LOG, log).apply();
    }

    private void refreshSyncLog() {
        String log = mPrefs.getString(KEY_LAST_LOG, null);
        if (log != null) {
            tvLastSync.setVisibility(View.VISIBLE);
            tvLog.setText(log);
            svLog.setVisibility(View.VISIBLE);
        }
    }

    // ── Helpers ──────────────────────────────────────
    private void restoreSession() {
        GoogleSignInAccount last = GoogleSignIn.getLastSignedInAccount(this);
        if (last != null) onSignedIn(last);
    }

    private void setStatus(String msg) { tvStatus.setText(msg); }

    private void askPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERM_REQ);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code,
            @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(code, p, r);
    }
}
