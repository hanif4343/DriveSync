package com.drivesync.app;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.Scope;
import com.google.api.services.drive.DriveScopes;

import java.util.concurrent.TimeUnit;

/**
 * ╔══════════════════════════════════════╗
 * ║  DriveSync — MainActivity            ║
 * ║  Google Sign-In (SHA-1 based)        ║
 * ║  Folder Picker (SAF)                 ║
 * ║  WorkManager (Wi-Fi + Charging only) ║
 * ╚══════════════════════════════════════╝
 *
 * NOTE: Android app-এ Google Client Secret লাগে না।
 *       SHA-1 fingerprint + google-services.json দিয়েই কাজ হয়।
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG          = "DriveSync";
    private static final String PREFS        = "DriveSync_Prefs";
    private static final String KEY_FOLDER   = "selected_folder_uri";
    private static final String WORK_TAG     = "DriveSync_Periodic";
    private static final int    PERM_REQ     = 101;

    // ── UI ──
    private TextView tvAccount, tvFolder, tvStatus, tvWifiNote;
    private Button   btnSignIn, btnFolder, btnStart, btnStop;

    // ── Auth ──
    private GoogleSignInClient mSignInClient;
    private GoogleSignInAccount mAccount;

    // ── Prefs ──
    private SharedPreferences mPrefs;

    // ── Launchers ──
    private ActivityResultLauncher<Intent> mSignInLauncher;
    private ActivityResultLauncher<Uri>    mFolderLauncher;

    // ════════════════════════════════════════
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        bindViews();
        buildSignInClient();
        registerLaunchers();
        setListeners();
        restoreSession();
        askPermission();
    }

    // ── Bind Views ──────────────────────────
    private void bindViews() {
        tvAccount  = findViewById(R.id.tv_account);
        tvFolder   = findViewById(R.id.tv_folder);
        tvStatus   = findViewById(R.id.tv_status);
        tvWifiNote = findViewById(R.id.tv_wifi_note);
        btnSignIn  = findViewById(R.id.btn_sign_in);
        btnFolder  = findViewById(R.id.btn_folder);
        btnStart   = findViewById(R.id.btn_start);
        btnStop    = findViewById(R.id.btn_stop);

        btnFolder.setEnabled(false);
        btnStart.setEnabled(false);
        btnStop.setEnabled(false);
        tvWifiNote.setVisibility(View.GONE);
    }

    // ── Google Sign-In Client ────────────────
    // Client Secret লাগে না — Android app এ শুধু
    // SHA-1 + google-services.json দিলেই হয়।
    private void buildSignInClient() {
        GoogleSignInOptions gso = new GoogleSignInOptions
                .Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope(DriveScopes.DRIVE))
                .build();
        mSignInClient = GoogleSignIn.getClient(this, gso);
    }

    // ── Register Activity Result Launchers ──
    private void registerLaunchers() {

        // Google Sign-In result
        mSignInLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    GoogleSignIn.getSignedInAccountFromIntent(result.getData())
                            .addOnSuccessListener(this::onSignedIn)
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "Sign-in failed: " + e.getMessage());
                                setStatus("❌ লগইন ব্যর্থ হয়েছে।\n" + e.getMessage());
                            });
                });

        // Folder picker result (SAF)
        mFolderLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                uri -> {
                    if (uri == null) return;

                    // Persistent read+write permission
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION |
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                    mPrefs.edit().putString(KEY_FOLDER, uri.toString()).apply();
                    showFolder(uri);
                    btnStart.setEnabled(true);
                    setStatus("✅ ফোল্ডার বেছে নেওয়া হয়েছে।\nএখন Sync শুরু করুন।");
                });
    }

    // ── Button Listeners ─────────────────────
    private void setListeners() {

        btnSignIn.setOnClickListener(v -> {
            if (mAccount == null) {
                mSignInLauncher.launch(mSignInClient.getSignInIntent());
                setStatus("⏳ Google লগইন হচ্ছে...");
            } else {
                signOut();
            }
        });

        btnFolder.setOnClickListener(v -> mFolderLauncher.launch(null));

        btnStart.setOnClickListener(v -> {
            String uri = mPrefs.getString(KEY_FOLDER, null);
            if (uri == null) {
                Toast.makeText(this, "আগে ফোল্ডার বেছে নিন!", Toast.LENGTH_SHORT).show();
                return;
            }
            startSync(uri);
        });

        btnStop.setOnClickListener(v -> stopSync());
    }

    // ── Sign-In Success ──────────────────────
    private void onSignedIn(GoogleSignInAccount account) {
        mAccount = account;
        tvAccount.setText("👤 " + account.getEmail());
        btnSignIn.setText("লগআউট করুন");
        btnFolder.setEnabled(true);

        // Drive service initialize করা (background thread-এ)
        DriveServiceHelper.init(getApplicationContext(), account);

        // আগের ফোল্ডার restore করা
        String saved = mPrefs.getString(KEY_FOLDER, null);
        if (saved != null) {
            showFolder(Uri.parse(saved));
            btnStart.setEnabled(true);
            setStatus("✅ আগের session পাওয়া গেছে। Sync শুরু করুন।");
        } else {
            setStatus("✅ লগইন সফল!\nএখন একটি ফোল্ডার বেছে নিন।");
        }
    }

    // ── Sign-Out ─────────────────────────────
    private void signOut() {
        stopSync();
        mSignInClient.signOut().addOnCompleteListener(this, t -> {
            mAccount = null;
            tvAccount.setText("লগইন করা হয়নি");
            btnSignIn.setText("Google দিয়ে লগইন করুন");
            btnFolder.setEnabled(false);
            btnStart.setEnabled(false);
            btnStop.setEnabled(false);
            setStatus("লগআউট সফল।");
        });
    }

    // ── Start Sync ───────────────────────────
    private void startSync(String folderUri) {

        // Constraints: শুধু Wi-Fi + Charging
        Constraints c = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED) // Wi-Fi only
                .setRequiresCharging(true)                      // Charging only
                .build();

        androidx.work.Data data = new androidx.work.Data.Builder()
                .putString(SyncWorker.KEY_FOLDER_URI, folderUri)
                .build();

        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                SyncWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(c)
                .setInputData(data)
                .addTag(WORK_TAG)
                .build();

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                WORK_TAG,
                ExistingPeriodicWorkPolicy.REPLACE,
                req);

        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        tvWifiNote.setVisibility(View.VISIBLE);
        setStatus("🔄 Sync চালু আছে!\nWi-Fi + Charging পেলে স্বয়ংক্রিয়ভাবে\nGoogle Drive-এ Sync হবে।");
        Log.d(TAG, "Sync started for: " + folderUri);
    }

    // ── Stop Sync ────────────────────────────
    private void stopSync() {
        WorkManager.getInstance(this).cancelAllWorkByTag(WORK_TAG);
        btnStart.setEnabled(mAccount != null);
        btnStop.setEnabled(false);
        tvWifiNote.setVisibility(View.GONE);
        setStatus("⏹ Sync বন্ধ করা হয়েছে।");
    }

    // ── Restore previous session ─────────────
    private void restoreSession() {
        GoogleSignInAccount last = GoogleSignIn.getLastSignedInAccount(this);
        if (last != null) onSignedIn(last);
    }

    // ── Helpers ──────────────────────────────
    private void showFolder(Uri uri) {
        String seg  = uri.getLastPathSegment(); // e.g. "primary:DCIM/Camera"
        String name = (seg != null && seg.contains(":"))
                ? seg.substring(seg.lastIndexOf(':') + 1) : seg;
        tvFolder.setText("📁 " + name);
    }

    private void setStatus(String msg) {
        tvStatus.setText(msg);
    }

    // ── Storage Permission (Android ≤ 10) ────
    private void askPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                        }, PERM_REQ);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code,
            @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == PERM_REQ && (results.length == 0
                || results[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this,
                    "Storage permission না দিলে ফাইল পড়া যাবে না!",
                    Toast.LENGTH_LONG).show();
        }
    }
}
