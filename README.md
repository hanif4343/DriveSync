# ☁ DriveSync

Local Android folder → Google Drive automatic sync app.

## Features
- Google Sign-In (no client secret needed — SHA-1 based)
- SAF folder picker (Android 10+)
- WorkManager background sync
- Wi-Fi + Charging constraints (battery safe)
- Supports all file types

## Build via GitHub Actions

1. Fork / upload this repo to GitHub
2. Go to **Settings → Secrets → Actions**
3. Add secret: `GOOGLE_SERVICES_JSON` = paste your `google-services.json` content
4. Go to **Actions tab → Build DriveSync APK → Run workflow**
5. Download APK from **Artifacts**

## SHA-1
`bf:7a:fa:e7:46:ea:20:7b:ff:e8:33:ce:2d:85:4f:d3:0b:bb:05:ec`

## Package
`com.drivesync.app`
