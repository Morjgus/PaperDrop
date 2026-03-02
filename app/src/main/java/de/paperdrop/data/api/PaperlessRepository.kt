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
import de.paperdrop.R
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

    suspend fun fetchLabels(url: String, token: String): Result<List<PaperlessLabel>> = runCatching {
        val response = apiClientProvider.getApi(url).getTags("Token $token")
        if (!response.isSuccessful)
            throw Exception("HTTP ${response.code()}: ${response.message()}")
        response.body()?.results ?: emptyList()
    }

    suspend fun uploadPdf(fileUri: Uri): UploadResult {
        val settings = settingsRepository.getSnapshot()
        if (settings.paperlessUrl.isBlank() || settings.apiToken.isBlank())
            return UploadResult.Error(context.getString(R.string.error_url_or_token_not_configured))

        return try {
            val api      = apiClientProvider.getApi(settings.paperlessUrl)
            val fileName = resolveFileName(fileUri)
            val bytes    = context.contentResolver.openInputStream(fileUri)?.readBytes()
                ?: return UploadResult.Error(context.getString(R.string.error_file_open_failed))

            val body  = bytes.toRequestBody("application/pdf".toMediaType())
            val parts = buildList {
                add(MultipartBody.Part.createFormData("document", fileName, body))
                settings.selectedLabelIds.forEach { id ->
                    add(MultipartBody.Part.createFormData("tags", id.toString()))
                }
            }

            val response = api.uploadDocument("Token ${settings.apiToken}", parts)
            if (response.isSuccessful) {
                // Paperless-ngx returns the task ID as a plain JSON string: "uuid-here"
                val taskId = response.body()?.string()
                    ?.trim()
                    ?.removeSurrounding("\"")
                    ?.takeIf { it.isNotBlank() }
                    ?: return UploadResult.Error(context.getString(R.string.error_no_task_id))
                UploadResult.Success(taskId = taskId, fileName = fileName)
            } else {
                UploadResult.Error(context.getString(R.string.error_upload_failed_http, response.code()))
            }
        } catch (e: IOException) {
            UploadResult.Error(context.getString(R.string.error_network, e.message ?: ""))
        } catch (e: Exception) {
            UploadResult.Error(context.getString(R.string.error_general, e.message ?: ""))
        }
    }

    suspend fun waitForTask(taskId: String, maxAttempts: Int = 10): UploadResult {
        val settings = settingsRepository.getSnapshot()
        val api      = apiClientProvider.getApi(settings.paperlessUrl)

        repeat(maxAttempts) { attempt ->
            delay(3000L * (attempt + 1))
            val response = api.getTaskStatus("Token ${settings.apiToken}", taskId)
            if (!response.isSuccessful) return UploadResult.Error(context.getString(R.string.error_task_query_failed))

            val task = response.body()?.firstOrNull()
                ?: return UploadResult.Error(context.getString(R.string.error_task_not_found))

            when (task.status) {
                "SUCCESS" -> {
                    val isDuplicate = task.result != null &&
                        task.result.contains("duplicate", ignoreCase = true)
                    return UploadResult.Completed(parseDocumentId(task.result), task.fileName ?: "", isDuplicate)
                }
                "FAILURE" -> {
                    if (task.result != null && task.result.contains("duplicate", ignoreCase = true))
                        return UploadResult.Completed(parseDocumentId(task.result), task.fileName ?: "", isDuplicate = true)
                    return UploadResult.Error(context.getString(R.string.error_paperless_processing_failed))
                }
            }
        }
        return UploadResult.Error(context.getString(R.string.error_paperless_timeout))
    }

    private fun parseDocumentId(result: String?): Int {
        if (result == null) return -1
        result.trim().toIntOrNull()?.let { return it }
        // "Success. New document id 28 created"
        Regex("""(?i)id\s+(\d+)""").find(result)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        // "It is a duplicate of SomeName (#27)."
        Regex("""\(#(\d+)\)""").find(result)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return -1
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
