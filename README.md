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
`61:ED:37:7E:85:D3:86:A8:DF:EE:6B:86:4B:D8:5B:0B:FA:A5:AF:81`

## Package
`com.drivesync.app`
