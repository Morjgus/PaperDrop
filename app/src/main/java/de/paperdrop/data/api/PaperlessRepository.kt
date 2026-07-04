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
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
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
        val api = apiClientProvider.getApi(url)
        val labels = mutableListOf<PaperlessLabel>()
        var page = 1
        while (true) {
            val response = api.getTags("Token $token", page = page)
            if (!response.isSuccessful)
                throw Exception("HTTP ${response.code()}: ${response.message()}")
            val body = response.body()
            labels.addAll(body?.results ?: emptyList())
            if (body?.next == null || page >= MAX_TAG_PAGES) break
            page++
        }
        labels
    }

    suspend fun uploadPdf(fileUri: Uri): UploadResult {
        val settings = settingsRepository.getSnapshot()
        if (settings.paperlessUrl.isBlank() || settings.apiToken.isBlank())
            return UploadResult.Error(context.getString(R.string.error_url_or_token_not_configured))

        return try {
            val api      = apiClientProvider.getApi(settings.paperlessUrl)
            val fileName = resolveFileName(fileUri)
            // Fail fast if the file can't be opened; the upload itself streams the
            // content instead of buffering the whole PDF in memory.
            val probe = context.contentResolver.openInputStream(fileUri)
                ?: return UploadResult.Error(context.getString(R.string.error_file_open_failed))
            probe.close()

            val body  = pdfRequestBody(fileUri)
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
                ?: return@repeat // task not visible in Paperless yet – keep polling

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

    // Streams the content on write instead of buffering it; each writeTo opens a
    // fresh stream, so the body stays repeatable for OkHttp retries.
    private fun pdfRequestBody(uri: Uri): RequestBody {
        val size = resolveFileSize(uri)
        return object : RequestBody() {
            override fun contentType() = "application/pdf".toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Could not open input stream for $uri")
                input.source().use { sink.writeAll(it) }
            }
        }
    }

    private fun resolveFileSize(uri: Uri): Long {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && idx >= 0 && !cursor.isNull(idx)) return cursor.getLong(idx)
        }
        return -1L
    }

    private fun resolveFileName(uri: Uri): String {
        var name = "document.pdf"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) name = cursor.getString(idx)
        }
        return name
    }

    private companion object {
        // Hard cap so a misbehaving server can't make fetchLabels loop forever.
        const val MAX_TAG_PAGES = 20
    }
}
