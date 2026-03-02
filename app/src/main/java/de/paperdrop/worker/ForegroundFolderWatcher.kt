package de.paperdrop.worker

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import de.paperdrop.data.preferences.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForegroundFolderWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val workManager: WorkManager
) {
    private val scope    = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observer: ContentObserver? = null

    fun start() {
        stop()
        scope.launch {
            val settings = settingsRepository.getSnapshot()
            if (!settings.isWatchingEnabled || settings.watchFolderUri.isBlank()) return@launch

            val treeUri     = Uri.parse(settings.watchFolderUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )

            val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    DirectoryPollingWorker.scanNow(workManager)
                }
            }
            context.contentResolver.registerContentObserver(childrenUri, true, obs)
            observer = obs
        }
    }

    fun stop() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
    }
}
