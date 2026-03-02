# PaperDrop

Android app that monitors a directory and automatically uploads new PDF files to [Paperless-ngx](https://docs.paperless-ngx.com/).

## Features

- 📁 **Directory monitoring** — freely selectable folder via Storage Access Framework
- ☁️ **Automatic upload** — every 15 minutes via WorkManager (network-resilient)
- 🗂️ **Paperless-ngx integration** — REST API with task-status polling
- 🔄 **Post-upload behaviour** — keep, delete, or move the file
- 📋 **Upload history** — history with search, filter, and statistics
- 🌙 **Dark Mode + Dynamic Color** (Android 12+)

## Tech Stack

| Area | Technology |
|------|------------|
| UI | Jetpack Compose + Material3 |
| DI | Hilt |
| Background | WorkManager |
| Network | Retrofit + OkHttp |
| Persistence | Room + DataStore |
| File access | Storage Access Framework (SAF) |

## Project Structure

```
app/src/main/java/de/paperdrop/
├── PaperDropApp.kt          # Application + Hilt + WorkManager
├── MainActivity.kt          # Entry Point + Navigation Shell
├── data/
│   ├── api/                 # Retrofit, PaperlessRepository
│   ├── db/                  # Room: UploadEntity, UploadDao
│   └── preferences/         # DataStore: SettingsRepository
├── di/                      # Hilt AppModule
├── domain/                  # UploadResult sealed class
├── ui/
│   ├── history/             # History screen + ViewModel
│   ├── navigation/          # NavGraph + Routes
│   ├── settings/            # Settings screen + ViewModel
│   └── theme/               # Material3 Theme
└── worker/
    ├── DirectoryPollingWorker.kt   # Scan folder (every 15 min)
    └── UploadWorker.kt             # Upload PDF + handle file
```

## Build from Source

Requirements: Android Studio or the Android SDK with build tools, JDK 17+.

```bash
git clone https://github.com/Morjgus/PaperDrop.git
cd PaperDrop
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Install directly on a connected device:

```bash
./gradlew installDebug
```

## Setup

1. Enter the Paperless-ngx server URL and API token in Settings
2. Select the source folder
3. Configure post-upload behaviour (Keep / Delete / Move)
4. Enable monitoring

## API Token

In Paperless-ngx: **Settings → API Token** (or `/api/token/`)

## Requirements

- Android 8.0 (API 26)
- Paperless-ngx ≥ 1.14 (Task API)
