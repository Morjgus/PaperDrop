package de.paperdrop.worker

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import de.paperdrop.data.api.PaperlessRepository
import de.paperdrop.data.db.UploadDao
import de.paperdrop.data.db.UploadEntity
import de.paperdrop.data.db.UploadStatus
import de.paperdrop.data.preferences.AfterUploadAction
import de.paperdrop.data.preferences.AppSettings
import de.paperdrop.data.preferences.SettingsRepository
import de.paperdrop.domain.UploadResult
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UploadWorkerTest {

    private lateinit var context: Context
    private val paperlessRepository = mockk<PaperlessRepository>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val uploadDao = mockk<UploadDao>(relaxed = true)

    private val defaultSettings = AppSettings(
        paperlessUrl = "http://test.local",
        apiToken = "token",
        afterUpload = AfterUploadAction.KEEP
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockkStatic(DocumentFile::class)
        // MockK's relaxed mode fabricates a non-null UploadEntity for unstubbed nullable
        // return types instead of null, so the "no previous attempt" case must be explicit.
        coEvery { uploadDao.getByUri(any()) } returns null
    }

    @After
    fun tearDown() {
        unmockkStatic(DocumentFile::class)
        clearAllMocks()
    }

    private fun buildWorker(
        uriString: String = "content://test/file.pdf",
        fileName: String = "file.pdf",
        runAttemptCount: Int = 0
    ): UploadWorker =
        TestListenableWorkerBuilder<UploadWorker>(
            context,
            workDataOf(UploadWorker.KEY_FILE_URI to uriString, UploadWorker.KEY_FILE_NAME to fileName)
        )
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker = UploadWorker(
                    appContext, workerParameters, paperlessRepository, settingsRepository, uploadDao
                )
            })
            .build()

    @Test
    fun `doWork returns failure when file URI is missing`() = runBlocking {
        val worker = TestListenableWorkerBuilder<UploadWorker>(context, Data.EMPTY)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(a: Context, b: String, p: WorkerParameters): ListenableWorker =
                    UploadWorker(a, p, paperlessRepository, settingsRepository, uploadDao)
            })
            .build()
        assertEquals(Result.failure(), worker.doWork())
        coVerify(exactly = 0) { uploadDao.insert(any()) }
    }

    @Test
    fun `doWork inserts RUNNING entity before upload`() = runBlocking {
        coEvery { uploadDao.insert(any()) } returns 1L
        coEvery { paperlessRepository.uploadPdf(any()) } returns UploadResult.Error("fail")
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings

        buildWorker(runAttemptCount = 3).doWork()

        val slot = slot<UploadEntity>()
        coVerify { uploadDao.insert(capture(slot)) }
        assertEquals(UploadStatus.RUNNING, slot.captured.status)
    }

    @Test
    fun `doWork retries when upload fails and attempt is under limit`() = runBlocking {
        coEvery { uploadDao.insert(any()) } returns 1L
        coEvery { paperlessRepository.uploadPdf(any()) } returns UploadResult.Error("fail")

        assertEquals(Result.retry(), buildWorker(runAttemptCount = 0).doWork())
    }

    @Test
    fun `doWork fails when upload fails and attempt is at limit`() = runBlocking {
        coEvery { uploadDao.insert(any()) } returns 1L
        coEvery { paperlessRepository.uploadPdf(any()) } returns UploadResult.Error("fail")

        assertEquals(Result.failure(), buildWorker(runAttemptCount = 3).doWork())
    }

    @Test
    fun `doWork updates status to FAILED when upload errors`() = runBlocking {
        coEvery { uploadDao.insert(any()) } returns 5L
        coEvery { paperlessRepository.uploadPdf(any()) } returns UploadResult.Error("bad")

        buildWorker(runAttemptCount = 3).doWork()

        coVerify { uploadDao.updateStatus(5L, UploadStatus.FAILED, "bad") }
    }

    @Test
    fun `doWork does not mark FAILED when retrying after upload failure`() = runBlocking {
        coEvery { uploadDao.insert(any()) } returns 1L
        coEvery { paperlessRepository.uploadPdf(any()) } returns UploadResult.Error("fail")

        buildWorker(runAttemptCount = 0).doWork()

        coVerify(exactly = 0) { uploadDao.updateStatus(any(), UploadStatus.FAILED, any<String>()) }
    }

    @Test
    fun `doWork persists taskId before polling`() = runBlocking {
        coEvery { uploadDao.insert(any()) } returns 3L
        coEvery { paperlessRepository.uploadPdf(any()) } returns UploadResult.Success("task-xyz", "f.pdf")
        coEvery { paperlessRepository.waitForTask("task-xyz") } returns UploadResult.Completed(1, "f.pdf")
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings

        buildWorker().doWork()

        coVerify { uploadDao.updateTaskId(3L, "task-xyz") }
    }

    @Test
    fun `doWork reuses existing row and resumes polling via persisted taskId without re-uploading`() = runBlocking {
        val existing = UploadEntity(
            id = 7L,
            fileName = "file.pdf",
            fileUri = "content://test/file.pdf",
            status = UploadStatus.RUNNING,
            timestamp = 500L,
            errorMessage = null,
            taskId = "task-existing"
        )
        coEvery { uploadDao.getByUri("content://test/file.pdf") } returns existing
        coEvery { paperlessRepository.waitForTask("task-existing") } returns UploadResult.Completed(9, "file.pdf")
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings

        val result = buildWorker(runAttemptCount = 1).doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 0) { paperlessRepository.uploadPdf(any()) }
        coVerify(exactly = 0) { uploadDao.insert(any()) }
        coVerify { uploadDao.updateStatus(7L, UploadStatus.RUNNING) }
        coVerify { uploadDao.updateStatus(7L, UploadStatus.SUCCESS, 9, false) }
    }

    @Test
    fun `doWork calls waitForTask with the correct taskId`() = runBlocking {
        coEvery { uploadDao.insert(any()) } returns 1L
        coEvery { paperlessRepository.uploadPdf(any()) } returns UploadResult.Success("task-abc", "file.pdf")
        coEvery { paperlessRepository.waitForTask("task-abc") } returns UploadResult.Completed(1, "file.pdf")
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings

        buildWorker().doWork()

        coVerify { paperlessRepository.waitForTask("task-abc") }
    }

    @Test
    fun `doWork returns success and updates status to SUCCESS on Completed`() = runBlocking {
        coEvery { uploadDao.insert(any()) } returns 2L
        coEvery { paperlessRepository.uploadPdf(any()) } returns UploadResult.Success("t", "f.pdf")
        coEvery { paperlessRepository.waitForTask(any()) } returns UploadResult.Completed(42, "f.pdf")
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings

        assertEquals(Result.success(), buildWorker().doWork())
        coVerify { uploadDao.updateStatus(2L, UploadStatus.SUCCESS, 42) }
    }

    @Test
    fun `doWork retries when waitForTask fails and attempt is under limit`() = runBlocking {
        coEvery { uploadDao.insert(any()) } returns 1L
        coEvery { paperlessRepository.uploadPdf(any()) } returns UploadResult.Success("t", "f.pdf")
        coEvery { paperlessRepository.waitForTask(any()) } returns UploadResult.Error("timeout")

        assertEquals(Result.retry(), buildWorker(runAttemptCount = 1).doWork())
    }

    @Test
    fun `doWork fails when waitForTask fails and attempt is at limit`() = runBlocking {
        coEvery { uploadDao.insert(any()) } returns 1L
        coEvery { paperlessRepository.uploadPdf(any()) } returns UploadResult.Success("t", "f.pdf")
        coEvery { paperlessRepository.waitForTask(any()) } returns UploadResult.Error("timeout")

        assertEquals(Result.failure(), buildWorker(runAttemptCount = 3).doWork())
    }

    @Test
    fun `doWork does not mark FAILED when retrying after waitForTask failure`() = runBlocking {
        coEvery { uploadDao.insert(any()) } returns 1L
        coEvery { paperlessRepository.uploadPdf(any()) } returns UploadResult.Success("t", "f.pdf")
        coEvery { paperlessRepository.waitForTask(any()) } returns UploadResult.Error("timeout")

        buildWorker(runAttemptCount = 1).doWork()

        coVerify(exactly = 0) { uploadDao.updateStatus(any(), UploadStatus.FAILED, any<String>()) }
    }

    @Test
    fun `doWork does not touch file when afterUpload is KEEP`() = runBlocking {
        coEvery { uploadDao.insert(any()) } returns 1L
        coEvery { paperlessRepository.uploadPdf(any()) } returns UploadResult.Success("t", "f.pdf")
        coEvery { paperlessRepository.waitForTask(any()) } returns UploadResult.Completed(1, "f.pdf")
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings.copy(afterUpload = AfterUploadAction.KEEP)

        buildWorker().doWork()

        verify(exactly = 0) { DocumentFile.fromSingleUri(any(), any()) }
        verify(exactly = 0) { DocumentFile.fromTreeUri(any(), any()) }
    }

    @Test
    fun `doWork deletes file when afterUpload is DELETE`() = runBlocking {
        val mockFile = mockk<DocumentFile>(relaxed = true)
        every { DocumentFile.fromSingleUri(any(), any()) } returns mockFile

        coEvery { uploadDao.insert(any()) } returns 1L
        coEvery { paperlessRepository.uploadPdf(any()) } returns UploadResult.Success("t", "f.pdf")
        coEvery { paperlessRepository.waitForTask(any()) } returns UploadResult.Completed(1, "f.pdf")
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings.copy(afterUpload = AfterUploadAction.DELETE)

        buildWorker().doWork()

        verify { mockFile.delete() }
    }

    @Test
    fun `doWork skips file move when moveTargetUri is blank`() = runBlocking {
        coEvery { uploadDao.insert(any()) } returns 1L
        coEvery { paperlessRepository.uploadPdf(any()) } returns UploadResult.Success("t", "f.pdf")
        coEvery { paperlessRepository.waitForTask(any()) } returns UploadResult.Completed(1, "f.pdf")
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings.copy(
            afterUpload = AfterUploadAction.MOVE,
            moveTargetUri = ""
        )

        // Should not throw and should not access DocumentFile
        assertEquals(Result.success(), buildWorker().doWork())
        verify(exactly = 0) { DocumentFile.fromTreeUri(any(), any()) }
    }

    // ── moveFile stream-failure handling ────────────────────────────────────────

    private fun buildMoveWorker(
        moveContentResolver: ContentResolver,
        sourceFile: DocumentFile,
        targetFile: DocumentFile
    ): UploadWorker {
        val moveContext = mockk<Context>(relaxed = true)
        val targetDir = mockk<DocumentFile>(relaxed = true)
        every { moveContext.contentResolver } returns moveContentResolver
        every { DocumentFile.fromSingleUri(moveContext, any()) } returns sourceFile
        every { DocumentFile.fromTreeUri(moveContext, any()) } returns targetDir
        every { targetDir.createFile(any(), any()) } returns targetFile

        coEvery { uploadDao.insert(any()) } returns 1L
        coEvery { paperlessRepository.uploadPdf(any()) } returns UploadResult.Success("t", "f.pdf")
        coEvery { paperlessRepository.waitForTask(any()) } returns UploadResult.Completed(1, "f.pdf")
        coEvery { settingsRepository.getSnapshot() } returns defaultSettings.copy(
            afterUpload = AfterUploadAction.MOVE,
            moveTargetUri = "content://test/target"
        )

        return TestListenableWorkerBuilder<UploadWorker>(
            moveContext,
            workDataOf(UploadWorker.KEY_FILE_URI to "content://test/file.pdf", UploadWorker.KEY_FILE_NAME to "file.pdf")
        )
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(a: Context, b: String, p: WorkerParameters): ListenableWorker =
                    UploadWorker(a, p, paperlessRepository, settingsRepository, uploadDao)
            })
            .build()
    }

    @Test
    fun `moveFile does not delete source when input stream is null`() = runBlocking {
        val sourceFile = mockk<DocumentFile>(relaxed = true)
        val targetFile = mockk<DocumentFile>(relaxed = true)
        every { sourceFile.name } returns "file.pdf"
        every { targetFile.uri } returns Uri.parse("content://test/target/file.pdf")

        val moveContentResolver = mockk<ContentResolver>(relaxed = true)
        every { moveContentResolver.openInputStream(any()) } returns null

        val worker = buildMoveWorker(moveContentResolver, sourceFile, targetFile)
        worker.doWork()

        verify(exactly = 0) { sourceFile.delete() }
        verify(exactly = 0) { moveContentResolver.openOutputStream(any()) }
        verify { targetFile.delete() }
    }

    @Test
    fun `moveFile does not delete source when output stream is null`() = runBlocking {
        val sourceFile = mockk<DocumentFile>(relaxed = true)
        val targetFile = mockk<DocumentFile>(relaxed = true)
        every { sourceFile.name } returns "file.pdf"
        every { targetFile.uri } returns Uri.parse("content://test/target/file.pdf")

        val moveContentResolver = mockk<ContentResolver>(relaxed = true)
        every { moveContentResolver.openInputStream(any()) } returns ByteArrayInputStream(byteArrayOf(1, 2, 3))
        every { moveContentResolver.openOutputStream(any()) } returns null

        val worker = buildMoveWorker(moveContentResolver, sourceFile, targetFile)
        worker.doWork()

        verify(exactly = 0) { sourceFile.delete() }
        verify { targetFile.delete() }
    }

    @Test
    fun `moveFile does not delete source and cleans up target when copy throws`() = runBlocking {
        val sourceFile = mockk<DocumentFile>(relaxed = true)
        val targetFile = mockk<DocumentFile>(relaxed = true)
        every { sourceFile.name } returns "file.pdf"
        every { targetFile.uri } returns Uri.parse("content://test/target/file.pdf")

        val throwingInputStream = object : InputStream() {
            override fun read(): Int = throw IOException("boom")
        }
        val moveContentResolver = mockk<ContentResolver>(relaxed = true)
        every { moveContentResolver.openInputStream(any()) } returns throwingInputStream
        every { moveContentResolver.openOutputStream(any()) } returns ByteArrayOutputStream()

        val worker = buildMoveWorker(moveContentResolver, sourceFile, targetFile)
        worker.doWork()

        verify(exactly = 0) { sourceFile.delete() }
        verify { targetFile.delete() }
    }

    @Test
    fun `moveFile deletes source after successful copy`() = runBlocking {
        val sourceFile = mockk<DocumentFile>(relaxed = true)
        val targetFile = mockk<DocumentFile>(relaxed = true)
        every { sourceFile.name } returns "file.pdf"
        every { targetFile.uri } returns Uri.parse("content://test/target/file.pdf")

        val moveContentResolver = mockk<ContentResolver>(relaxed = true)
        every { moveContentResolver.openInputStream(any()) } returns ByteArrayInputStream(byteArrayOf(1, 2, 3))
        every { moveContentResolver.openOutputStream(any()) } returns ByteArrayOutputStream()

        val worker = buildMoveWorker(moveContentResolver, sourceFile, targetFile)
        worker.doWork()

        verify { sourceFile.delete() }
        verify(exactly = 0) { targetFile.delete() }
    }
}
