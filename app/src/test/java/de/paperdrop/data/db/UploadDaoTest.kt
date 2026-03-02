package de.paperdrop.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UploadDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: UploadDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.uploadDao()
    }

    @After
    fun tearDown() = db.close()

    private fun entity(
        fileName: String = "test.pdf",
        fileUri: String = "content://test/1",
        status: UploadStatus = UploadStatus.RUNNING,
        timestamp: Long = 1000L
    ) = UploadEntity(fileName = fileName, fileUri = fileUri, status = status, timestamp = timestamp)

    @Test
    fun `insert assigns auto-generated id`() = runBlocking {
        val id = dao.insert(entity())
        assertTrue(id > 0)
    }

    @Test
    fun `getAllUris returns all tracked uris regardless of status`() = runBlocking {
        dao.insert(entity(fileUri = "content://success", status = UploadStatus.SUCCESS))
        dao.insert(entity(fileUri = "content://failed",  status = UploadStatus.FAILED))
        dao.insert(entity(fileUri = "content://running", status = UploadStatus.RUNNING))

        val uris = dao.getAllUris().toSet()
        assertEquals(setOf("content://success", "content://failed", "content://running"), uris)
    }

    @Test
    fun `getAllUris returns empty list when table is empty`() = runBlocking {
        assertTrue(dao.getAllUris().isEmpty())
    }

    @Test
    fun `updateStatus with error updates status and errorMessage`() = runBlocking {
        val id = dao.insert(entity())
        dao.updateStatus(id, UploadStatus.FAILED, "network error")

        val uploads = dao.getAllUploads().first()
        assertEquals(UploadStatus.FAILED, uploads[0].status)
        assertEquals("network error", uploads[0].errorMessage)
    }

    @Test
    fun `updateStatus with documentId updates status and documentId`() = runBlocking {
        val id = dao.insert(entity())
        dao.updateStatus(id, UploadStatus.SUCCESS, 99)

        val uploads = dao.getAllUploads().first()
        assertEquals(UploadStatus.SUCCESS, uploads[0].status)
        assertEquals(99, uploads[0].documentId)
        assertFalse(uploads[0].isDuplicate)
    }

    @Test
    fun `updateStatus with isDuplicate true persists flag`() = runBlocking {
        val id = dao.insert(entity())
        dao.updateStatus(id, UploadStatus.SUCCESS, 42, isDuplicate = true)

        val uploads = dao.getAllUploads().first()
        assertEquals(UploadStatus.SUCCESS, uploads[0].status)
        assertEquals(42, uploads[0].documentId)
        assertTrue(uploads[0].isDuplicate)
    }

    @Test
    fun `getAllUploads emits inserted entities`() = runBlocking {
        dao.insert(entity(fileName = "a.pdf"))
        dao.insert(entity(fileName = "b.pdf", fileUri = "content://test/2"))

        val uploads = dao.getAllUploads().first()
        assertEquals(2, uploads.size)
    }

    @Test
    fun `getAllUploads orders by timestamp descending`() = runBlocking {
        dao.insert(entity(timestamp = 1000L))
        dao.insert(entity(fileUri = "content://test/2", timestamp = 2000L))

        val uploads = dao.getAllUploads().first()
        assertTrue(uploads[0].timestamp > uploads[1].timestamp)
    }

    @Test
    fun `cleanupOlderThan deletes old SUCCESS records`() = runBlocking {
        val thirtyOneDaysAgo = System.currentTimeMillis() - (31L * 24 * 60 * 60 * 1000)
        dao.insert(entity(fileUri = "content://old",   status = UploadStatus.SUCCESS, timestamp = thirtyOneDaysAgo))
        dao.insert(entity(fileUri = "content://recent", status = UploadStatus.SUCCESS, timestamp = System.currentTimeMillis()))

        val cutoff = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        dao.cleanupOlderThan(cutoff)

        val remaining = dao.getAllUploads().first()
        assertEquals(1, remaining.size)
        assertEquals("content://recent", remaining[0].fileUri)
    }

    @Test
    fun `cleanupOlderThan does not delete FAILED or RUNNING records`() = runBlocking {
        val oldTimestamp = System.currentTimeMillis() - (31L * 24 * 60 * 60 * 1000)
        dao.insert(entity(fileUri = "content://failed",  status = UploadStatus.FAILED,  timestamp = oldTimestamp))
        dao.insert(entity(fileUri = "content://running", status = UploadStatus.RUNNING, timestamp = oldTimestamp))

        dao.cleanupOlderThan(System.currentTimeMillis())

        assertEquals(2, dao.getAllUploads().first().size)
    }
}
