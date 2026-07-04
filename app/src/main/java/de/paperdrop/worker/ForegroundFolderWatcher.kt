package de.paperdrop.worker

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import de.paperdrop.data.preferences.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForegroundFolderWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val workManager: WorkManager
) {
    private val scope    = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null

    fun start(dispatcher: CoroutineDispatcher = Dispatchers.IO) {
        watchJob?.cancel()
        watchJob = scope.launch(dispatcher) {
            Log.d(TAG, "Foreground folder watcher started")
            settingsRepository.settings
                .map { it.isWatchingEnabled to it.watchFolderUri }
                .distinctUntilChanged()
                .collectLatest { (isWatchingEnabled, watchFolderUri) ->
                    if (isWatchingEnabled && watchFolderUri.isNotBlank()) {
                        observeFolder(watchFolderUri)
                    }
                }
        }
    }

    fun stop() {
        watchJob?.cancel()
        watchJob = null
        Log.d(TAG, "Foreground folder watcher stopped")
    }

    private suspend fun observeFolder(watchFolderUri: String) {
        val childrenUri = runCatching {
            val treeUri = Uri.parse(watchFolderUri)
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        }.getOrElse {
            Log.w(TAG, "Cannot observe non-tree URI $watchFolderUri: ${it.message}")
            return
        }

        val changes  = Channel<Unit>(Channel.CONFLATED)
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                changes.trySend(Unit)
            }
        }

        context.contentResolver.registerContentObserver(childrenUri, true, observer)
        try {
            Log.d(TAG, "Observing $childrenUri for changes")
            DirectoryPollingWorker.scanNow(workManager)

            for (change in changes) {
                while (withTimeoutOrNull(DEBOUNCE_MS) { changes.receive() } != null) {
                    // Keep draining the burst, each received change resets the debounce window.
                }
                DirectoryPollingWorker.scanNow(workManager)
            }
        } finally {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }

    companion object {
        private const val TAG = "ForegroundFolderWatcher"
        internal const val DEBOUNCE_MS = 500L
    }
}
