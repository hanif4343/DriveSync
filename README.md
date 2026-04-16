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
`58:72:C5:20:D9:A9:DA:89:39:2A:FC:16:E1:0A:05:51:5C:59:18:B5`

## Package
`com.drivesync.app`
