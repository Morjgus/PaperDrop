package de.paperdrop.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import de.paperdrop.data.db.UploadDao
import de.paperdrop.data.preferences.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForegroundFolderWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val uploadDao: UploadDao,
    private val workManager: WorkManager
) {
    private val scope    = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null

    fun start() {
        watchJob?.cancel()
        watchJob = scope.launch {
            Log.d(TAG, "Foreground folder watcher started")
            while (isActive) {
                checkForNewFiles()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        watchJob?.cancel()
        watchJob = null
        Log.d(TAG, "Foreground folder watcher stopped")
    }

    private suspend fun checkForNewFiles() {
        val settings = settingsRepository.getSnapshot()
        if (!settings.isWatchingEnabled || settings.watchFolderUri.isBlank()) return

        val folder = DocumentFile.fromTreeUri(context, Uri.parse(settings.watchFolderUri)) ?: return
        val pdfUris = folder.listFiles()
            .filter { it.isFile && it.name?.lowercase()?.endsWith(".pdf") == true && it.canRead() }
            .map { it.uri.toString() }
            .toSet()

        if (pdfUris.isEmpty()) return

        val knownUris = uploadDao.getAllUris().toSet()
        val newFiles  = pdfUris - knownUris
        if (newFiles.isNotEmpty()) {
            Log.d(TAG, "Detected ${newFiles.size} new file(s), triggering scan")
            DirectoryPollingWorker.scanNow(workManager)
        }
    }

    companion object {
        private const val TAG             = "ForegroundFolderWatcher"
        private const val POLL_INTERVAL_MS = 3_000L
    }
}
