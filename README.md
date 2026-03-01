# PaperDrop

Android-App, die ein Verzeichnis überwacht und neue PDF-Dateien automatisch an [Paperless-ngx](https://docs.paperless-ngx.com/) hochlädt.

## Features

- 📁 **Verzeichnisüberwachung** — frei wählbarer Ordner via Storage Access Framework
- ☁️ **Automatischer Upload** — alle 15 Minuten via WorkManager (netzwerkresillient)
- 🗂️ **Paperless-ngx Integration** — REST API mit Task-Status-Polling
- 🔄 **Verhalten nach Upload** — Datei behalten, löschen oder verschieben
- 📋 **Upload-Verlauf** — History mit Suche, Filter und Statistiken
- 🌙 **Dark Mode + Dynamic Color** (Android 12+)

## Tech Stack

| Bereich | Technologie |
|---------|-------------|
| UI | Jetpack Compose + Material3 |
| DI | Hilt |
| Hintergrund | WorkManager |
| Netzwerk | Retrofit + OkHttp |
| Persistenz | Room + DataStore |
| Dateizugriff | Storage Access Framework (SAF) |

## Projektstruktur

```
app/src/main/java/de/paperdrop/
├── PaperDropApp.kt          # Application + Hilt + WorkManager
├── MainActivity.kt                 # Entry Point + Navigation Shell
├── data/
│   ├── api/                        # Retrofit, PaperlessRepository
│   ├── db/                         # Room: UploadEntity, UploadDao
│   └── preferences/                # DataStore: SettingsRepository
├── di/                             # Hilt AppModule
├── domain/                         # UploadResult sealed class
├── ui/
│   ├── history/                    # Verlauf-Screen + ViewModel
│   ├── navigation/                 # NavGraph + Routes
│   ├── settings/                   # Einstellungen-Screen + ViewModel
│   └── theme/                      # Material3 Theme
└── worker/
    ├── DirectoryPollingWorker.kt   # Ordner scannen (alle 15 min)
    └── UploadWorker.kt             # PDF hochladen + Datei behandeln
```

## Setup

1. Paperless-ngx Server-URL und API-Token in den Einstellungen eintragen
2. Quellordner auswählen
3. Verhalten nach Upload festlegen (Behalten / Löschen / Verschieben)
4. Überwachung aktivieren

## API Token

In Paperless-ngx: **Einstellungen → API Token** (oder `/api/token/`)

## Mindestanforderungen

- Android 8.0 (API 26)
- Paperless-ngx ≥ 1.14 (Task-API)
