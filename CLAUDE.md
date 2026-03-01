# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

This is a standard Android project. Use Gradle wrapper:

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Install on connected device
./gradlew installDebug

# Run lint
./gradlew lint

# Run all tests (no unit tests currently exist)
./gradlew test
```

## Architecture

Single-module Android app (`de.paperdrop`) using MVVM + clean architecture:

- **`PaperDropApp`** — `@HiltAndroidApp` entry point; manually provides WorkManager configuration with `HiltWorkerFactory` (required for `@HiltWorker` injection)
- **`MainActivity`** — hosts `PaperlessNavGraph`, the single Compose navigation graph
- **`data/api/`** — Retrofit-based Paperless-ngx REST client. `ApiClientProvider` lazily builds/caches the `PaperlessApi` instance keyed by base URL (recreated when URL changes). `PaperlessRepository` performs upload + async task polling (exponential back-off via repeated `delay` calls)
- **`data/db/`** — Room database (`paperdrop.db`, version 1, `fallbackToDestructiveMigration`). Single table `uploads` with `UploadStatus` enum (RUNNING / SUCCESS / FAILED)
- **`data/preferences/`** — DataStore-backed `SettingsRepository`; `AppSettings` data class holds all user config. `AfterUploadAction` enum controls post-upload file handling (KEEP / DELETE / MOVE)
- **`di/AppModule`** — Hilt `SingletonComponent` providing `AppDatabase`, `UploadDao`, and `WorkManager`
- **`domain/UploadResult`** — sealed class representing upload pipeline stages: `Success` (task enqueued), `Completed` (Paperless processed, document ID known), `Error`
- **`worker/DirectoryPollingWorker`** — `@HiltWorker` periodic worker (15 min interval, network required). Scans SAF folder, filters PDFs not in the `uploads` table, enqueues one `UploadWorker` per new file using `ExistingWorkPolicy.KEEP`
- **`worker/UploadWorker`** — `@HiltWorker` one-time worker. Inserts a RUNNING record, uploads via `PaperlessRepository`, polls task status, updates record, then handles file (keep/delete/move via SAF `DocumentFile` API). Retries up to 3 times with exponential backoff

## Key Patterns

- **SAF (Storage Access Framework)**: all file access goes through `DocumentFile` / `ContentResolver`. The watched folder URI is a persisted SAF tree URI. Never use `java.io.File` for user-picked locations.
- **Deduplication**: `DirectoryPollingWorker` calls `uploadDao.getAllUris()` to skip files already tracked in Room, regardless of their current status.
- **WorkManager uniqueness**: polling uses `enqueueUniquePeriodicWork(WORK_NAME, KEEP, ...)`, each file upload uses `enqueueUniqueWork("upload_${file.uri}", KEEP, ...)` to prevent duplicates.
- **API auth**: Paperless token passed per-request as `Authorization: Token <token>` header, not via an OkHttp interceptor, so the token is always fresh from `SettingsRepository`.
- **Gradle version catalog**: dependencies are declared in `gradle/libs.versions.toml` (not shown inline in build files); use alias references when adding deps.
