package de.paperdrop.data.api

import android.content.ContentResolver
import android.content.Context
import de.paperdrop.data.preferences.AppSettings
import de.paperdrop.data.preferences.SettingsRepository
import de.paperdrop.domain.UploadResult
import io.mockk.*
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.ByteArrayInputStream
import java.io.IOException

class PaperlessRepositoryTest {

    private val api = mockk<PaperlessApi>()
    private val apiClientProvider = mockk<ApiClientProvider>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val mockContext = mockk<Context>(relaxed = true)
    private val mockContentResolver = mockk<ContentResolver>(relaxed = true)

    private val repository = PaperlessRepository(apiClientProvider, settingsRepository, mockContext)

    private val defaultSettings = AppSettings(
        paperlessUrl = "http://paperless.local",
        apiToken = "test-token"
    )

    @Before
    fun setUp() {
        every { mockContext.contentResolver } returns mockContentResolver
        every { mockContentResolver.query(any(), any(), any(), any(), any()) } returns null
        every { apiClientProvider.getApi(any()) } returns api
    }

    @After
    fun tearDown() = clearAllMocks()

    // ── testConnection ──────────────────────────────────────────────────────────

    @Test
    fun `testConnection returns success on 200`() = runTest {
        coEvery { api.ping(any()) } returns Response.success(null)
        assertTrue(repository.testConnection("http://test.local", "tok").isSuccess)
    }

    @Test
    fun `testConnection returns failure on HTTP 401`() = runTest {
        coEvery { api.ping(any()) } returns Response.error(401, "Unauthorized".toResponseBody())
        val result = repository.testConnection("http://test.local", "tok")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("401"))
    }

    @Test
    fun `testConnection returns failure on IOException`() = runTest {
        coEvery { api.ping(any()) } throws IOException("no route")
        assertTrue(repository.testConnection("http://test.local", "tok").isFailure)
    }

    // ── uploadPdf ───────────────────────────────────────────────────────────────

    @Test
    fun `uploadPdf returns error when paperlessUrl is blank`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns AppSettings(apiToken = "tok")
        val result = repository.uploadPdf(mockk())
        assertTrue(result is UploadResult.Error)
    }

    @Test
    fun `uploadPdf returns error when apiToken is blank`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns AppSettings(paperlessUrl = "http://x")
        val result = repository.uploadPdf(mockk())
        assertTrue(result is UploadResult.Error)
    }

    @Test
    fun `uploadPdf returns error when inputStream is null`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        every { mockContentResolver.openInputStream(any()) } returns null
        val result = repository.uploadPdf(mockk())
        assertTrue(result is UploadResult.Error)
    }

    @Test
    fun `uploadPdf returns Success with taskId on HTTP 200`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        every { mockContentResolver.openInputStream(any()) } returns ByteArrayInputStream(byteArrayOf(1, 2, 3))
        coEvery { api.uploadDocument(any(), any()) } returns
            Response.success("\"task-123\"".toResponseBody("text/plain".toMediaType()))

        val result = repository.uploadPdf(mockk())
        assertTrue(result is UploadResult.Success)
        assertEquals("task-123", (result as UploadResult.Success).taskId)
    }

    @Test
    fun `uploadPdf returns error on HTTP 500`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        every { mockContentResolver.openInputStream(any()) } returns ByteArrayInputStream(byteArrayOf(1))
        coEvery { api.uploadDocument(any(), any()) } returns
            Response.error(500, "Server error".toResponseBody(null))

        val result = repository.uploadPdf(mockk())
        assertTrue(result is UploadResult.Error)
        assertTrue((result as UploadResult.Error).message.contains("500"))
    }

    @Test
    fun `uploadPdf returns error on IOException`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        every { mockContentResolver.openInputStream(any()) } returns ByteArrayInputStream(byteArrayOf(1))
        coEvery { api.uploadDocument(any(), any()) } throws IOException("timeout")

        val result = repository.uploadPdf(mockk())
        assertTrue(result is UploadResult.Error)
        assertTrue((result as UploadResult.Error).message.contains("Netzwerkfehler"))
    }

    @Test
    fun `uploadPdf returns error when taskId is blank`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        every { mockContentResolver.openInputStream(any()) } returns ByteArrayInputStream(byteArrayOf(1))
        coEvery { api.uploadDocument(any(), any()) } returns
            Response.success("\"\"".toResponseBody("text/plain".toMediaType()))

        // Empty task ID string → repository returns Error
        val result = repository.uploadPdf(mockk())
        assertTrue(result is UploadResult.Error)
    }

    @Test
    fun `waitForTask extracts document id from success message string`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        coEvery { api.getTaskStatus(any(), any()) } returns Response.success(
            listOf(TaskStatusResponse("t", "SUCCESS", "Success. New document id 28 created", "doc.pdf"))
        )
        val result = repository.waitForTask("t", maxAttempts = 1)
        assertEquals(UploadResult.Completed(28, "doc.pdf"), result)
    }

    @Test
    fun `waitForTask extracts document id from duplicate message on SUCCESS`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        coEvery { api.getTaskStatus(any(), any()) } returns Response.success(
            listOf(TaskStatusResponse("t", "SUCCESS", "Not consuming file.pdf: It is a duplicate of file (#27).", "doc.pdf"))
        )
        val result = repository.waitForTask("t", maxAttempts = 1)
        assertEquals(UploadResult.Completed(27, "doc.pdf", isDuplicate = true), result)
    }

    @Test
    fun `waitForTask returns Completed with isDuplicate true on FAILURE with duplicate message`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        coEvery { api.getTaskStatus(any(), any()) } returns Response.success(
            listOf(TaskStatusResponse("t", "FAILURE", "Not consuming file.pdf: It is a duplicate of file (#27).", "doc.pdf"))
        )
        val result = repository.waitForTask("t", maxAttempts = 1)
        assertEquals(UploadResult.Completed(27, "doc.pdf", isDuplicate = true), result)
    }

    // ── waitForTask ─────────────────────────────────────────────────────────────

    @Test
    fun `waitForTask returns Completed on SUCCESS status`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        coEvery { api.getTaskStatus(any(), any()) } returns Response.success(
            listOf(TaskStatusResponse("t", "SUCCESS", "42", "doc.pdf"))
        )
        val result = repository.waitForTask("t", maxAttempts = 1)
        assertEquals(UploadResult.Completed(42, "doc.pdf"), result)
    }

    @Test
    fun `waitForTask returns Error on FAILURE status`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        coEvery { api.getTaskStatus(any(), any()) } returns Response.success(
            listOf(TaskStatusResponse("t", "FAILURE", "some error", null))
        )
        val result = repository.waitForTask("t", maxAttempts = 1)
        assertTrue(result is UploadResult.Error)
    }

    @Test
    fun `waitForTask retries and eventually succeeds`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        coEvery { api.getTaskStatus(any(), any()) } returnsMany listOf(
            Response.success(listOf(TaskStatusResponse("t", "PENDING", null, null))),
            Response.success(listOf(TaskStatusResponse("t", "SUCCESS", "7", "file.pdf")))
        )
        val result = repository.waitForTask("t", maxAttempts = 3)
        assertEquals(UploadResult.Completed(7, "file.pdf"), result)
    }

    @Test
    fun `waitForTask returns timeout error after max attempts`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        coEvery { api.getTaskStatus(any(), any()) } returns Response.success(
            listOf(TaskStatusResponse("t", "PENDING", null, null))
        )
        val result = repository.waitForTask("t", maxAttempts = 2)
        assertTrue(result is UploadResult.Error)
        assertTrue((result as UploadResult.Error).message.contains("Timeout"))
    }

    @Test
    fun `waitForTask returns error on HTTP failure`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        coEvery { api.getTaskStatus(any(), any()) } returns
            Response.error(503, "unavailable".toResponseBody())
        val result = repository.waitForTask("t", maxAttempts = 1)
        assertTrue(result is UploadResult.Error)
    }

    @Test
    fun `waitForTask returns error when task list is empty`() = runTest {
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings
        coEvery { api.getTaskStatus(any(), any()) } returns Response.success(emptyList())
        val result = repository.waitForTask("t", maxAttempts = 1)
        assertTrue(result is UploadResult.Error)
        assertTrue((result as UploadResult.Error).message.contains("nicht gefunden"))
    }
}
