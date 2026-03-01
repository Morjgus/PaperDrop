package de.paperdrop.data.api

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import de.paperdrop.data.preferences.SettingsRepository
import de.paperdrop.domain.UploadResult
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PaperlessRepository @Inject constructor(
    private val apiClientProvider: ApiClientProvider,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
) {
    suspend fun testConnection(url: String, token: String): Result<Unit> = runCatching {
        val response = apiClientProvider.getApi(url).ping("Token $token")
        if (!response.isSuccessful)
            throw Exception("HTTP ${response.code()}: ${response.message()}")
    }

    suspend fun uploadPdf(fileUri: Uri): UploadResult {
        val settings = settingsRepository.getSnapshot()
        if (settings.paperlessUrl.isBlank() || settings.apiToken.isBlank())
            return UploadResult.Error("URL oder Token nicht konfiguriert")

        return try {
            val api      = apiClientProvider.getApi(settings.paperlessUrl)
            val fileName = resolveFileName(fileUri)
            val bytes    = context.contentResolver.openInputStream(fileUri)?.readBytes()
                ?: return UploadResult.Error("Datei konnte nicht geöffnet werden")

            val body = bytes.toRequestBody("application/pdf".toMediaType())
            val part = MultipartBody.Part.createFormData("document", fileName, body)

            val response = api.uploadDocument("Token ${settings.apiToken}", part)
            if (response.isSuccessful) {
                val taskId = response.body()?.taskId
                    ?: return UploadResult.Error("Keine Task-ID erhalten")
                UploadResult.Success(taskId = taskId, fileName = fileName)
            } else {
                UploadResult.Error("Upload fehlgeschlagen: HTTP ${response.code()}")
            }
        } catch (e: IOException) {
            UploadResult.Error("Netzwerkfehler: ${e.message}")
        } catch (e: Exception) {
            UploadResult.Error("Fehler: ${e.message}")
        }
    }

    suspend fun waitForTask(taskId: String, maxAttempts: Int = 10): UploadResult {
        val settings = settingsRepository.getSnapshot()
        val api      = apiClientProvider.getApi(settings.paperlessUrl)

        repeat(maxAttempts) { attempt ->
            delay(3000L * (attempt + 1))
            val response = api.getTaskStatus("Token ${settings.apiToken}", taskId)
            if (!response.isSuccessful) return UploadResult.Error("Task-Abfrage fehlgeschlagen")

            val task = response.body()?.firstOrNull()
                ?: return UploadResult.Error("Task nicht gefunden")

            when (task.status) {
                "SUCCESS" -> return UploadResult.Completed(task.result ?: -1, task.fileName ?: "")
                "FAILURE" -> return UploadResult.Error("Paperless-Verarbeitung fehlgeschlagen")
            }
        }
        return UploadResult.Error("Timeout: Paperless hat nicht geantwortet")
    }

    private fun resolveFileName(uri: Uri): String {
        var name = "document.pdf"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) name = cursor.getString(idx)
        }
        return name
    }
}
