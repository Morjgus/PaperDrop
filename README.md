# PaperDrop

Android app that monitors a directory and automatically uploads new PDF files to [Paperless-ngx](https://docs.paperless-ngx.com/).

## Features

- **Directory monitoring** — freely selectable folder via Storage Access Framework
- **Automatic upload** — every 15 minutes via WorkManager (network-resilient, battery-friendly)
- **Paperless-ngx integration** — REST API with task-status polling and exponential back-off
- **Duplicate detection** — recognises files already in Paperless and shows a clear warning instead of failing silently
- **Post-upload behaviour** — keep, delete, or move the file after a successful upload
- **Upload history** — searchable and filterable list with per-status statistics and auto-scroll
- **Label assignment** — select Paperless tags to apply to every uploaded document
- **Dark Mode + Dynamic Color** (Android 12+)
- **24 languages** — English, German, French, Spanish, Italian, Portuguese, Dutch, Polish, Russian, Swedish, Danish, Norwegian, Finnish, Czech, Romanian, Greek, Chinese (Simplified & Traditional), Japanese, Korean, Hindi, Arabic, Vietnamese, Thai, Indonesian

## Screenshots

> Coming soon

## Tech Stack

| Area | Technology |
|------|------------|
| UI | Jetpack Compose + Material 3 |
| DI | Hilt |
| Background | WorkManager |
| Network | Retrofit + OkHttp |
| Persistence | Room + DataStore |
| File access | Storage Access Framework (SAF) |

## Project Structure

```
app/src/main/java/de/paperdrop/
├── PaperDropApp.kt          # Application + Hilt + WorkManager config
├── MainActivity.kt          # Entry point + navigation shell
├── data/
│   ├── api/                 # Retrofit client, PaperlessRepository
│   ├── db/                  # Room: UploadEntity, UploadDao
│   └── preferences/         # DataStore: SettingsRepository
├── di/                      # Hilt AppModule
├── domain/                  # UploadResult sealed class
├── ui/
│   ├── history/             # History screen + ViewModel
│   ├── navigation/          # NavGraph + routes
│   ├── settings/            # Settings screen + ViewModel
│   └── theme/               # Material 3 theme
└── worker/
    ├── DirectoryPollingWorker.kt   # Scans folder every 15 min
    └── UploadWorker.kt             # Uploads PDF + handles file afterwards
```

## Build from Source

Requirements: Android Studio or Android SDK with build tools, JDK 17+.

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

1. Open **Settings** and enter the Paperless-ngx server URL and API token
2. Tap **Test connection** to verify the credentials
3. Select the **source folder** to monitor
4. Optionally load and select **labels** to tag uploaded documents
5. Choose **post-upload behaviour**: Keep / Delete / Move
6. Enable **automatic monitoring**

New PDFs placed in the folder will be picked up within 15 minutes and uploaded automatically.

## Getting an API Token

In Paperless-ngx: **Settings → API Token** (or navigate to `/api/token/`).

## Requirements

- Android 8.0 (API 26) or higher
- Paperless-ngx 1.14 or higher (Task API required)

## License

Apache License 2.0 — see [LICENSE](LICENSE).
