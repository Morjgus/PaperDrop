package de.paperdrop.worker

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import de.paperdrop.data.db.UploadDao
import de.paperdrop.data.preferences.AppSettings
import de.paperdrop.data.preferences.SettingsRepository
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DirectoryPollingWorkerTest {

    private lateinit var context: Context
    private val settingsRepository = mockk<SettingsRepository>()
    private val uploadDao = mockk<UploadDao>()
    private val workManager = mockk<WorkManager>(relaxed = true)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockkStatic(DocumentFile::class)
    }

    @After
    fun tearDown() {
        unmockkStatic(DocumentFile::class)
        clearAllMocks()
    }

    private fun buildWorker(): DirectoryPollingWorker =
        TestListenableWorkerBuilder<DirectoryPollingWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker = DirectoryPollingWorker(
                    appContext, workerParameters, settingsRepository, uploadDao, workManager
                )
            })
            .build()

    @Test
    fun `doWork returns success when watching is disabled`() = runBlocking {
        coEvery { settingsRepository.getSnapshot() } returns AppSettings(isWatchingEnabled = false)
        assertEquals(Result.success(), buildWorker().doWork())
    }

    @Test
    fun `doWork returns success when watch folder URI is blank`() = runBlocking {
        coEvery { settingsRepository.getSnapshot() } returns AppSettings(
            isWatchingEnabled = true, watchFolderUri = ""
        )
        assertEquals(Result.success(), buildWorker().doWork())
    }

    @Test
    fun `doWork returns failure when folder is not accessible`() = runBlocking {
        coEvery { settingsRepository.getSnapshot() } returns AppSettings(
            isWatchingEnabled = true, watchFolderUri = "content://folder/1"
        )
        every { DocumentFile.fromTreeUri(any(), any()) } returns null

        assertEquals(Result.failure(workDataOf("error" to "Ordner nicht erreichbar")), buildWorker().doWork())
    }

    @Test
    fun `doWork returns success when folder contains no PDFs`() = runBlocking {
        val mockFolder = mockk<DocumentFile>()
        coEvery { settingsRepository.getSnapshot() } returns AppSettings(
            isWatchingEnabled = true, watchFolderUri = "content://folder/1"
        )
        every { DocumentFile.fromTreeUri(any(), any()) } returns mockFolder
        every { mockFolder.listFiles() } returns emptyArray()

        assertEquals(Result.success(), buildWorker().doWork())
        verify(exactly = 0) { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
    }

    @Test
    fun `doWork skips files already in the DAO`() = runBlocking {
        val knownUri = "content://files/known.pdf"
        val mockFolder = mockk<DocumentFile>()
        val mockFile = mockk<DocumentFile>()
        coEvery { settingsRepository.getSnapshot() } returns AppSettings(
            isWatchingEnabled = true, watchFolderUri = "content://folder/1"
        )
        every { DocumentFile.fromTreeUri(any(), any()) } returns mockFolder
        every { mockFolder.listFiles() } returns arrayOf(mockFile)
        every { mockFile.isFile } returns true
        every { mockFile.name } returns "known.pdf"
        every { mockFile.canRead() } returns true
        every { mockFile.uri } returns Uri.parse(knownUri)
        coEvery { uploadDao.getAllUris() } returns listOf(knownUri)

        buildWorker().doWork()
        verify(exactly = 0) { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
    }

    @Test
    fun `doWork enqueues upload worker for new PDF`() = runBlocking {
        val newUri = "content://files/new.pdf"
        val mockFolder = mockk<DocumentFile>()
        val mockFile = mockk<DocumentFile>()
        coEvery { settingsRepository.getSnapshot() } returns AppSettings(
            isWatchingEnabled = true, watchFolderUri = "content://folder/1"
        )
        every { DocumentFile.fromTreeUri(any(), any()) } returns mockFolder
        every { mockFolder.listFiles() } returns arrayOf(mockFile)
        every { mockFile.isFile } returns true
        every { mockFile.name } returns "new.pdf"
        every { mockFile.canRead() } returns true
        every { mockFile.uri } returns Uri.parse(newUri)
        coEvery { uploadDao.getAllUris() } returns emptyList()

        buildWorker().doWork()
        verify(exactly = 1) { workManager.enqueueUniqueWork("upload_$newUri", ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) }
    }

    @Test
    fun `doWork enqueues one worker per new PDF`() = runBlocking {
        val mockFolder = mockk<DocumentFile>()
        val files = (1..3).map { i ->
            mockk<DocumentFile>().also {
                every { it.isFile } returns true
                every { it.name } returns "file$i.pdf"
                every { it.canRead() } returns true
                every { it.uri } returns Uri.parse("content://files/file$i.pdf")
            }
        }
        coEvery { settingsRepository.getSnapshot() } returns AppSettings(
            isWatchingEnabled = true, watchFolderUri = "content://folder/1"
        )
        every { DocumentFile.fromTreeUri(any(), any()) } returns mockFolder
        every { mockFolder.listFiles() } returns files.toTypedArray()
        coEvery { uploadDao.getAllUris() } returns emptyList()

        buildWorker().doWork()
        verify(exactly = 3) { workManager.enqueueUniqueWork(any(), ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) }
    }

    @Test
    fun `doWork ignores non-PDF files`() = runBlocking {
        val mockFolder = mockk<DocumentFile>()
        val txtFile = mockk<DocumentFile>()
        coEvery { settingsRepository.getSnapshot() } returns AppSettings(
            isWatchingEnabled = true, watchFolderUri = "content://folder/1"
        )
        every { DocumentFile.fromTreeUri(any(), any()) } returns mockFolder
        every { mockFolder.listFiles() } returns arrayOf(txtFile)
        every { txtFile.isFile } returns true
        every { txtFile.name } returns "document.txt"
        every { txtFile.canRead() } returns true

        buildWorker().doWork()
        verify(exactly = 0) { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
    }

    @Test
    fun `doWork ignores unreadable files`() = runBlocking {
        val mockFolder = mockk<DocumentFile>()
        val lockedFile = mockk<DocumentFile>()
        coEvery { settingsRepository.getSnapshot() } returns AppSettings(
            isWatchingEnabled = true, watchFolderUri = "content://folder/1"
        )
        every { DocumentFile.fromTreeUri(any(), any()) } returns mockFolder
        every { mockFolder.listFiles() } returns arrayOf(lockedFile)
        every { lockedFile.isFile } returns true
        every { lockedFile.name } returns "locked.pdf"
        every { lockedFile.canRead() } returns false

        buildWorker().doWork()
        verify(exactly = 0) { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
    }
}
