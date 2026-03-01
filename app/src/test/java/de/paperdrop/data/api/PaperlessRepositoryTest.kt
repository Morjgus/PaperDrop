package de.paperdrop.data.api

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import de.paperdrop.data.preferences.AfterUploadAction
import de.paperdrop.data.preferences.AppSettings
import de.paperdrop.data.preferences.SettingsRepository
import de.paperdrop.domain.UploadResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class PaperlessRepositoryTest {

    private val apiClientProvider: ApiClientProvider = mockk()
    private val settingsRepository: SettingsRepository = mockk()
    private val context: Context = mockk()
    private val contentResolver: ContentResolver = mockk()
    private val api: PaperlessApi = mockk()

    private lateinit var repository: PaperlessRepository

    private val defaultSettings = AppSettings(
        paperlessUrl      = "https://paperless.example.com",
        apiToken          = "test-token",
        watchFolderUri    = "",
        afterUpload       = AfterUploadAction.KEEP,
        moveTargetUri     = "",
        isWatchingEnabled = false
    )

    @Before
    fun setUp() {
        every { context.contentResolver } returns contentResolver
        repository = PaperlessRepository(apiClientProvider, settingsRepository, context)
    }

    // ── testConnection ───────────────────────────────────────────────────────────

    @Test
    fun `testConnection returns success on HTTP 200`() = runTest {
        every { apiClientProvider.getApi(any()) } returns api
        coEvery { api.ping(any()) } returns Response.success(Unit)

        val result = repository.testConnection("https://paperless.example.com", "token")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `testConnection returns failure on HTTP 401`() = runTest {
        every { apiClientProvider.getApi(any()) } returns api
        coEvery { api.ping(any()) } returns Response.error(401, "Unauthorized".toResponseBody())

        val result = repository.testConnection("https://paperless.example.com", "token")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("401") == true)
    }

    @Test
    fun `testConnection sends token prefixed with Token keyword`() = runTest {
        every { apiClientProvider.getApi(any()) } returns api
        coEvery { api.ping("Token mytoken") } returns Response.success(Unit)

        repository.testConnection("https://paperless.example.com", "mytoken")

        coVerify { api.ping("Token mytoken") }
    }

    @Test
    fun `testConnection returns failure on network IOException`() = runTest {
        every { apiClientProvider.getApi(any()) } returns api
        coEvery { api.ping(any()) } throws java.io.IOException("Connection refused")

        val result = repository.testConnection("https://paperless.example.com", "token")

        assertTrue(result.isFailure)
    }

    // ── uploadPdf ────────────────────────────────────────────────────────────────

    @Test
    fun `uploadPdf returns Error when paperlessUrl is blank`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings.copy(paperlessUrl = "")

        val result = repository.uploadPdf(mockk())

        assertTrue(result is UploadResult.Error)
        assertTrue((result as UploadResult.Error).message.contains("URL"))
    }

    @Test
    fun `uploadPdf returns Error when apiToken is blank`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings.copy(apiToken = "")

        val result = repository.uploadPdf(mockk())

        assertTrue(result is UploadResult.Error)
    }

    @Test
    fun `uploadPdf returns Error when file stream cannot be opened`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        every { apiClientProvider.getApi(any()) } returns api
        every { contentResolver.openInputStream(any()) } returns null
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns null

        val result = repository.uploadPdf(mockk<Uri>())

        assertTrue(result is UploadResult.Error)
        assertTrue((result as UploadResult.Error).message.contains("geöffnet"))
    }

    @Test
    fun `uploadPdf returns Success with taskId on HTTP 200`() = runTest {
        val cursor = mockk<Cursor>(relaxed = true) {
            every { getColumnIndex(any()) } returns 0
            every { moveToFirst() } returns true
            every { getString(0) } returns "test.pdf"
        }
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        every { apiClientProvider.getApi(any()) } returns api
        every { contentResolver.openInputStream(any()) } returns "PDF content".byteInputStream()
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns cursor
        coEvery { api.uploadDocument(any(), any()) } returns
            Response.success(UploadTaskResponse(taskId = "task-999"))

        val result = repository.uploadPdf(mockk<Uri>())

        assertTrue(result is UploadResult.Success)
        assertEquals("task-999", (result as UploadResult.Success).taskId)
        assertEquals("test.pdf", result.fileName)
    }

    @Test
    fun `uploadPdf uses document_pdf as filename fallback when cursor returns null`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        every { apiClientProvider.getApi(any()) } returns api
        every { contentResolver.openInputStream(any()) } returns "PDF".byteInputStream()
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns null
        coEvery { api.uploadDocument(any(), any()) } returns
            Response.success(UploadTaskResponse(taskId = "task-1"))

        val result = repository.uploadPdf(mockk<Uri>())

        assertTrue(result is UploadResult.Success)
        assertEquals("document.pdf", (result as UploadResult.Success).fileName)
    }

    @Test
    fun `uploadPdf returns Error on HTTP 500`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        every { apiClientProvider.getApi(any()) } returns api
        every { contentResolver.openInputStream(any()) } returns "PDF".byteInputStream()
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns null
        coEvery { api.uploadDocument(any(), any()) } returns
            Response.error(500, "Server Error".toResponseBody())

        val result = repository.uploadPdf(mockk<Uri>())

        assertTrue(result is UploadResult.Error)
        assertTrue((result as UploadResult.Error).message.contains("500"))
    }

    @Test
    fun `uploadPdf wraps IOException as network error`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        every { apiClientProvider.getApi(any()) } returns api
        every { contentResolver.openInputStream(any()) } returns "PDF".byteInputStream()
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns null
        coEvery { api.uploadDocument(any(), any()) } throws java.io.IOException("timeout")

        val result = repository.uploadPdf(mockk<Uri>())

        assertTrue(result is UploadResult.Error)
        assertTrue((result as UploadResult.Error).message.contains("Netzwerkfehler"))
    }

    // ── waitForTask ──────────────────────────────────────────────────────────────

    @Test
    fun `waitForTask returns Completed on SUCCESS status`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        every { apiClientProvider.getApi(any()) } returns api
        val taskResponse = TaskStatusResponse(
            taskId = "task-1", status = "SUCCESS", result = 7, fileName = "doc.pdf"
        )
        coEvery { api.getTaskStatus(any(), "task-1") } returns Response.success(listOf(taskResponse))

        val result = repository.waitForTask("task-1", maxAttempts = 1)

        assertTrue(result is UploadResult.Completed)
        assertEquals(7, (result as UploadResult.Completed).documentId)
        assertEquals("doc.pdf", result.fileName)
    }

    @Test
    fun `waitForTask returns Error on FAILURE status`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        every { apiClientProvider.getApi(any()) } returns api
        val taskResponse = TaskStatusResponse(
            taskId = "task-1", status = "FAILURE", result = null, fileName = null
        )
        coEvery { api.getTaskStatus(any(), "task-1") } returns Response.success(listOf(taskResponse))

        val result = repository.waitForTask("task-1", maxAttempts = 1)

        assertTrue(result is UploadResult.Error)
    }

    @Test
    fun `waitForTask returns Error after exhausting all attempts`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        every { apiClientProvider.getApi(any()) } returns api
        val pending = TaskStatusResponse("task-1", "PENDING", null, null)
        coEvery { api.getTaskStatus(any(), "task-1") } returns Response.success(listOf(pending))

        val result = repository.waitForTask("task-1", maxAttempts = 2)

        assertTrue(result is UploadResult.Error)
        assertTrue((result as UploadResult.Error).message.contains("Timeout"))
    }

    @Test
    fun `waitForTask returns Error when task list is empty`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        every { apiClientProvider.getApi(any()) } returns api
        coEvery { api.getTaskStatus(any(), "task-1") } returns Response.success(emptyList())

        val result = repository.waitForTask("task-1", maxAttempts = 1)

        assertTrue(result is UploadResult.Error)
        assertTrue((result as UploadResult.Error).message.contains("nicht gefunden"))
    }

    @Test
    fun `waitForTask returns Error on non-successful HTTP response`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        every { apiClientProvider.getApi(any()) } returns api
        coEvery { api.getTaskStatus(any(), "task-1") } returns
            Response.error(500, "Server Error".toResponseBody())

        val result = repository.waitForTask("task-1", maxAttempts = 1)

        assertTrue(result is UploadResult.Error)
    }

    @Test
    fun `waitForTask succeeds on second attempt after pending`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        every { apiClientProvider.getApi(any()) } returns api
        val pending = TaskStatusResponse("t", "PENDING", null, null)
        val success = TaskStatusResponse("t", "SUCCESS", 5, "file.pdf")
        coEvery { api.getTaskStatus(any(), "t") } returnsMany listOf(
            Response.success(listOf(pending)),
            Response.success(listOf(success))
        )

        val result = repository.waitForTask("t", maxAttempts = 3)

        assertTrue(result is UploadResult.Completed)
        assertEquals(5, (result as UploadResult.Completed).documentId)
    }

    @Test
    fun `waitForTask uses documentId of -1 when result is null on SUCCESS`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        every { apiClientProvider.getApi(any()) } returns api
        val taskResponse = TaskStatusResponse("task-1", "SUCCESS", result = null, fileName = "f.pdf")
        coEvery { api.getTaskStatus(any(), "task-1") } returns Response.success(listOf(taskResponse))

        val result = repository.waitForTask("task-1", maxAttempts = 1)

        assertTrue(result is UploadResult.Completed)
        assertEquals(-1, (result as UploadResult.Completed).documentId)
    }
}
