package de.paperdrop.worker

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.paperdrop.data.db.UploadDao
import de.paperdrop.data.preferences.SettingsRepository
import java.util.concurrent.TimeUnit

@HiltWorker
class DirectoryPollingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    private val uploadDao: UploadDao,
    private val workManager: WorkManager
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val WORK_NAME = "directory_polling"

        fun schedule(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<DirectoryPollingWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag("polling")
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(workManager: WorkManager) {
            workManager.cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val settings = settingsRepository.getSnapshot()

        if (!settings.isWatchingEnabled || settings.watchFolderUri.isBlank())
            return Result.success()

        val folder = DocumentFile.fromTreeUri(applicationContext, Uri.parse(settings.watchFolderUri))
            ?: return Result.failure(workDataOf("error" to "Ordner nicht erreichbar"))

        val pdfFiles = folder.listFiles().filter { file ->
            file.isFile && file.name?.lowercase()?.endsWith(".pdf") == true && file.canRead()
        }

        if (pdfFiles.isEmpty()) return Result.success()

        val knownUris = uploadDao.getAllUris().toSet()
        val newFiles  = pdfFiles.filter { it.uri.toString() !in knownUris }

        newFiles.forEach { file ->
            workManager.enqueueUniqueWork(
                "upload_${file.uri}",
                ExistingWorkPolicy.KEEP,
                UploadWorker.buildRequest(file.uri.toString(), file.name ?: "document.pdf")
            )
        }

        return Result.success()
    }
}
