package com.drivesync.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * BootReceiver
 * Device restart হলে WorkManager নিজেই periodic work restore করে।
 * এই receiver শুধু log করে — extra safety।
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d("DriveSync_Boot", "Boot completed — WorkManager restoring jobs.");
        }
    }
}
