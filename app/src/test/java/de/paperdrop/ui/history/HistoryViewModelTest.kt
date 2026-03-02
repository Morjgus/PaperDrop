package de.paperdrop.ui.history

import de.paperdrop.MainDispatcherRule
import de.paperdrop.data.db.UploadDao
import de.paperdrop.data.db.UploadEntity
import de.paperdrop.data.db.UploadStatus
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class HistoryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val uploadDao = mockk<UploadDao>()
    private lateinit var viewModel: HistoryViewModel

    private fun entity(
        fileName: String = "test.pdf",
        status: UploadStatus = UploadStatus.SUCCESS,
        timestamp: Long = System.currentTimeMillis()
    ) = UploadEntity(fileName = fileName, fileUri = "uri://$fileName", status = status, timestamp = timestamp)

    @Before
    fun setUp() {
        every { uploadDao.getAllUploads() } returns flowOf(emptyList())
        viewModel = HistoryViewModel(uploadDao)
    }

    @After
    fun tearDown() = clearAllMocks()

    @Test
    fun `initial state has isLoading true before dao emits`() {
        every { uploadDao.getAllUploads() } returns flowOf()
        val freshVm = HistoryViewModel(uploadDao)
        assertTrue(freshVm.uiState.value.isLoading)
    }

    @Test
    fun `state is updated when dao flow emits`() = runTest {
        val uploads = listOf(entity(), entity(fileName = "b.pdf"))
        every { uploadDao.getAllUploads() } returns flowOf(uploads)
        viewModel = HistoryViewModel(uploadDao)
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.uploads.size)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `stats counts correctly by status`() = runTest {
        val uploads = listOf(
            entity(fileName = "a.pdf", status = UploadStatus.SUCCESS),
            entity(fileName = "b.pdf", status = UploadStatus.SUCCESS),
            entity(fileName = "c.pdf", status = UploadStatus.FAILED),
            entity(fileName = "d.pdf", status = UploadStatus.RUNNING)
        )
        every { uploadDao.getAllUploads() } returns flowOf(uploads)
        viewModel = HistoryViewModel(uploadDao)
        advanceUntilIdle()

        val stats = viewModel.uiState.value.stats
        assertEquals(4, stats.total)
        assertEquals(2, stats.success)
        assertEquals(1, stats.failed)
        assertEquals(1, stats.running)
    }

    @Test
    fun `onFilterChange updates activeFilter`() {
        viewModel.onFilterChange(HistoryFilter.SUCCESS)
        assertEquals(HistoryFilter.SUCCESS, viewModel.uiState.value.activeFilter)
    }

    @Test
    fun `onSearchChange updates searchQuery`() {
        viewModel.onSearchChange("invoice")
        assertEquals("invoice", viewModel.uiState.value.searchQuery)
    }

    @Test
    fun `clearSearch resets searchQuery`() {
        viewModel.onSearchChange("invoice")
        viewModel.clearSearch()
        assertEquals("", viewModel.uiState.value.searchQuery)
    }

    // ── filteredUploads ──────────────────────────────────────────────────────────

    private val mixedUploads = listOf(
        entity(fileName = "success1.pdf", status = UploadStatus.SUCCESS),
        entity(fileName = "success2.pdf", status = UploadStatus.SUCCESS),
        entity(fileName = "failed.pdf",   status = UploadStatus.FAILED),
        entity(fileName = "running.pdf",  status = UploadStatus.RUNNING)
    )

    @Test
    fun `filteredUploads with ALL filter returns everything`() {
        val state = HistoryUiState(uploads = mixedUploads, activeFilter = HistoryFilter.ALL)
        assertEquals(4, viewModel.filteredUploads(state).size)
    }

    @Test
    fun `filteredUploads with SUCCESS filter returns only success`() {
        val state = HistoryUiState(uploads = mixedUploads, activeFilter = HistoryFilter.SUCCESS)
        val result = viewModel.filteredUploads(state)
        assertEquals(2, result.size)
        assertTrue(result.all { it.status == UploadStatus.SUCCESS })
    }

    @Test
    fun `filteredUploads with FAILED filter returns only failed`() {
        val state = HistoryUiState(uploads = mixedUploads, activeFilter = HistoryFilter.FAILED)
        val result = viewModel.filteredUploads(state)
        assertEquals(1, result.size)
        assertEquals(UploadStatus.FAILED, result[0].status)
    }

    @Test
    fun `filteredUploads with RUNNING filter returns only running`() {
        val state = HistoryUiState(uploads = mixedUploads, activeFilter = HistoryFilter.RUNNING)
        val result = viewModel.filteredUploads(state)
        assertEquals(1, result.size)
        assertEquals(UploadStatus.RUNNING, result[0].status)
    }

    @Test
    fun `filteredUploads search filters on fileName case-insensitively`() {
        val state = HistoryUiState(uploads = mixedUploads, searchQuery = "SUCCESS")
        val result = viewModel.filteredUploads(state)
        assertEquals(2, result.size)
        assertTrue(result.all { it.fileName.contains("success", ignoreCase = true) })
    }

    @Test
    fun `filteredUploads combines filter and search`() {
        val state = HistoryUiState(
            uploads = mixedUploads,
            activeFilter = HistoryFilter.SUCCESS,
            searchQuery = "success1"
        )
        val result = viewModel.filteredUploads(state)
        assertEquals(1, result.size)
        assertEquals("success1.pdf", result[0].fileName)
    }

    @Test
    fun `clearAll deletes all entries`() = runTest {
        coEvery { uploadDao.deleteAll() } just Runs

        viewModel.clearAll()
        advanceUntilIdle()

        coVerify { uploadDao.deleteAll() }
    }

    @Test
    fun `cleanupSuccessful calls dao with thirty-day cutoff`() = runTest {
        coEvery { uploadDao.cleanupOlderThan(any()) } just Runs
        val before = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)

        viewModel.cleanupSuccessful()
        advanceUntilIdle()

        val after = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        val slot = slot<Long>()
        coVerify { uploadDao.cleanupOlderThan(capture(slot)) }
        assertTrue(slot.captured in before..after)
    }
}
