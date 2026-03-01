package de.paperdrop.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.paperdrop.data.api.PaperlessRepository
import de.paperdrop.data.db.UploadDao
import de.paperdrop.data.db.UploadEntity
import de.paperdrop.data.db.UploadStatus
import de.paperdrop.data.preferences.AfterUploadAction
import de.paperdrop.data.preferences.SettingsRepository
import de.paperdrop.domain.UploadResult
import java.util.concurrent.TimeUnit

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val paperlessRepository: PaperlessRepository,
    private val settingsRepository: SettingsRepository,
    private val uploadDao: UploadDao
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val KEY_FILE_URI  = "file_uri"
        const val KEY_FILE_NAME = "file_name"

        fun buildRequest(fileUri: String, fileName: String): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_FILE_URI to fileUri, KEY_FILE_NAME to fileName))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .addTag("upload")
                .build()
    }

    override suspend fun doWork(): Result {
        val uriString = inputData.getString(KEY_FILE_URI)  ?: return Result.failure()
        val fileName  = inputData.getString(KEY_FILE_NAME) ?: "document.pdf"
        val fileUri   = Uri.parse(uriString)

        val entityId = uploadDao.insert(
            UploadEntity(
                fileName  = fileName,
                fileUri   = uriString,
                status    = UploadStatus.RUNNING,
                timestamp = System.currentTimeMillis()
            )
        )

        val uploadResult = paperlessRepository.uploadPdf(fileUri)
        if (uploadResult is UploadResult.Error) {
            uploadDao.updateStatus(entityId, UploadStatus.FAILED, uploadResult.message)
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }

        val taskId      = (uploadResult as UploadResult.Success).taskId
        val finalResult = paperlessRepository.waitForTask(taskId)

        return when (finalResult) {
            is UploadResult.Completed -> {
                uploadDao.updateStatus(entityId, UploadStatus.SUCCESS, finalResult.documentId)
                handleFileAfterUpload(fileUri)
                Result.success()
            }
            is UploadResult.Error -> {
                uploadDao.updateStatus(entityId, UploadStatus.FAILED, finalResult.message)
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
            else -> Result.failure()
        }
    }

    private suspend fun handleFileAfterUpload(fileUri: Uri) {
        val settings = settingsRepository.getSnapshot()
        when (settings.afterUpload) {
            AfterUploadAction.DELETE -> deleteFile(fileUri)
            AfterUploadAction.MOVE   -> moveFile(fileUri, settings.moveTargetUri)
            AfterUploadAction.KEEP   -> Unit
        }
    }

    private fun deleteFile(uri: Uri) {
        try {
            DocumentFile.fromSingleUri(applicationContext, uri)?.delete()
        } catch (e: Exception) {
            Log.e("UploadWorker", "Löschen fehlgeschlagen: ${e.message}")
        }
    }

    private fun moveFile(uri: Uri, targetUriString: String) {
        if (targetUriString.isBlank()) return
        try {
            val targetDir  = DocumentFile.fromTreeUri(applicationContext, Uri.parse(targetUriString)) ?: return
            val sourceFile = DocumentFile.fromSingleUri(applicationContext, uri) ?: return
            val targetFile = targetDir.createFile("application/pdf", sourceFile.name ?: "document.pdf") ?: return

            applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                applicationContext.contentResolver.openOutputStream(targetFile.uri)?.use { output ->
                    input.copyTo(output)
                }
            }
            sourceFile.delete()
        } catch (e: Exception) {
            Log.e("UploadWorker", "Verschieben fehlgeschlagen: ${e.message}")
        }
    }
}
