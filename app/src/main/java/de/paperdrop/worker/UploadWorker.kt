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

        // Reuse the row from a previous attempt (if any) instead of inserting a new one,
        // so retries don't pollute history with duplicate rows for the same file.
        val existing = uploadDao.getByUri(uriString)
        val entityId = existing?.id ?: uploadDao.insert(
            UploadEntity(
                fileName  = fileName,
                fileUri   = uriString,
                status    = UploadStatus.RUNNING,
                timestamp = System.currentTimeMillis()
            )
        )
        if (existing != null) {
            uploadDao.updateStatus(entityId, UploadStatus.RUNNING)
        }

        // If a previous attempt already got Paperless to accept the file (taskId persisted),
        // skip uploadPdf entirely and resume polling – otherwise we'd re-upload the document.
        val taskId: String = existing?.taskId ?: run {
            val uploadResult = paperlessRepository.uploadPdf(fileUri)
            if (uploadResult is UploadResult.Error) {
                return failOrRetry(entityId, uploadResult.message)
            }
            val newTaskId = (uploadResult as UploadResult.Success).taskId
            uploadDao.updateTaskId(entityId, newTaskId)
            newTaskId
        }

        val finalResult = paperlessRepository.waitForTask(taskId)

        return when (finalResult) {
            is UploadResult.Completed -> {
                if (finalResult.isDuplicate) {
                    Log.w(
                        "UploadWorker",
                        "DUPLIKAT ERKANNT: \"${finalResult.fileName}\" ist bereits als Dokument " +
                        "#${finalResult.documentId} in Paperless vorhanden. " +
                        "Die Datei wurde nicht erneut importiert – Paperless hat den Upload abgelehnt."
                    )
                }
                uploadDao.updateStatus(entityId, UploadStatus.SUCCESS, finalResult.documentId, finalResult.isDuplicate)
                handleFileAfterUpload(fileUri)
                Result.success()
            }
            is UploadResult.Error -> failOrRetry(entityId, finalResult.message)
            else -> Result.failure()
        }
    }

    /**
     * While retries remain, leave the row RUNNING (WorkManager will re-run doWork).
     * Only mark FAILED once we give up for good and return Result.failure().
     */
    private suspend fun failOrRetry(entityId: Long, message: String): Result {
        if (runAttemptCount < 3) return Result.retry()
        uploadDao.updateStatus(entityId, UploadStatus.FAILED, message)
        return Result.failure()
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
        var targetFile: DocumentFile? = null
        try {
            val targetDir  = DocumentFile.fromTreeUri(applicationContext, Uri.parse(targetUriString)) ?: return
            val sourceFile = DocumentFile.fromSingleUri(applicationContext, uri) ?: return
            val newTargetFile = targetDir.createFile("application/pdf", sourceFile.name ?: "document.pdf") ?: return
            targetFile = newTargetFile

            val input = applicationContext.contentResolver.openInputStream(uri)
            if (input == null) {
                Log.e("UploadWorker", "Verschieben fehlgeschlagen: InputStream konnte nicht geöffnet werden")
                newTargetFile.delete()
                return
            }
            input.use { inputStream ->
                val output = applicationContext.contentResolver.openOutputStream(newTargetFile.uri)
                if (output == null) {
                    Log.e("UploadWorker", "Verschieben fehlgeschlagen: OutputStream konnte nicht geöffnet werden")
                    newTargetFile.delete()
                    return
                }
                output.use { inputStream.copyTo(it) }
            }
            sourceFile.delete()
        } catch (e: Exception) {
            Log.e("UploadWorker", "Verschieben fehlgeschlagen: ${e.message}")
            try {
                targetFile?.delete()
            } catch (cleanupException: Exception) {
                Log.e("UploadWorker", "Aufräumen fehlgeschlagen: ${cleanupException.message}")
            }
        }
    }
}
