package de.paperdrop.ui.history

import de.paperdrop.data.db.UploadDao
import de.paperdrop.data.db.UploadEntity
import de.paperdrop.data.db.UploadStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val uploadDao: UploadDao = mockk()
    private val uploadsFlow = MutableStateFlow<List<UploadEntity>>(emptyList())
    private lateinit var viewModel: HistoryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { uploadDao.getAllUploads() } returns uploadsFlow
        coEvery { uploadDao.cleanupOlderThan(any()) } just Runs
        viewModel = HistoryViewModel(uploadDao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sampleUploads() = listOf(
        UploadEntity(id = 1, fileName = "invoice.pdf",  fileUri = "u1", status = UploadStatus.SUCCESS, timestamp = 1000L),
        UploadEntity(id = 2, fileName = "receipt.pdf",  fileUri = "u2", status = UploadStatus.FAILED,  timestamp = 2000L),
        UploadEntity(id = 3, fileName = "contract.pdf", fileUri = "u3", status = UploadStatus.RUNNING, timestamp = 3000L),
        UploadEntity(id = 4, fileName = "notes.pdf",    fileUri = "u4", status = UploadStatus.SUCCESS, timestamp = 4000L)
    )

    // ── initial state ─────────────────────────────────────────────────────────

    @Test
    fun `initial state has isLoading true before first emission`() {
        assertTrue(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `initial state has ALL filter and empty search`() {
        assertEquals(HistoryFilter.ALL, viewModel.uiState.value.activeFilter)
        assertEquals("", viewModel.uiState.value.searchQuery)
    }

    @Test
    fun `state updates when dao emits uploads`() = runTest {
        uploadsFlow.value = sampleUploads()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(4, state.uploads.size)
        assertFalse(state.isLoading)
    }

    // ── stats ──────────────────────────────────────────────────────────────────

    @Test
    fun `stats are computed correctly from uploads`() = runTest {
        uploadsFlow.value = sampleUploads()
        testDispatcher.scheduler.advanceUntilIdle()

        val stats = viewModel.uiState.value.stats
        assertEquals(4, stats.total)
        assertEquals(2, stats.success)
        assertEquals(1, stats.failed)
        assertEquals(1, stats.running)
    }

    @Test
    fun `stats are all zero for empty list`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        val stats = viewModel.uiState.value.stats
        assertEquals(0, stats.total)
        assertEquals(0, stats.success)
        assertEquals(0, stats.failed)
        assertEquals(0, stats.running)
    }

    @Test
    fun `stats update when uploads change`() = runTest {
        uploadsFlow.value = sampleUploads()
        testDispatcher.scheduler.advanceUntilIdle()

        uploadsFlow.value = emptyList()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.stats.total)
    }

    // ── filtering ─────────────────────────────────────────────────────────────

    @Test
    fun `filter ALL returns all uploads`() = runTest {
        uploadsFlow.value = sampleUploads()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onFilterChange(HistoryFilter.ALL)
        val filtered = viewModel.filteredUploads(viewModel.uiState.value)
        assertEquals(4, filtered.size)
    }

    @Test
    fun `filter SUCCESS returns only successful uploads`() = runTest {
        uploadsFlow.value = sampleUploads()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onFilterChange(HistoryFilter.SUCCESS)
        val filtered = viewModel.filteredUploads(viewModel.uiState.value)
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.status == UploadStatus.SUCCESS })
    }

    @Test
    fun `filter FAILED returns only failed uploads`() = runTest {
        uploadsFlow.value = sampleUploads()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onFilterChange(HistoryFilter.FAILED)
        val filtered = viewModel.filteredUploads(viewModel.uiState.value)
        assertEquals(1, filtered.size)
        assertEquals(UploadStatus.FAILED, filtered.first().status)
    }

    @Test
    fun `filter RUNNING returns only running uploads`() = runTest {
        uploadsFlow.value = sampleUploads()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onFilterChange(HistoryFilter.RUNNING)
        val filtered = viewModel.filteredUploads(viewModel.uiState.value)
        assertEquals(1, filtered.size)
        assertEquals(UploadStatus.RUNNING, filtered.first().status)
    }

    @Test
    fun `filter FAILED on empty list returns empty result`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onFilterChange(HistoryFilter.FAILED)
        val filtered = viewModel.filteredUploads(viewModel.uiState.value)
        assertTrue(filtered.isEmpty())
    }

    // ── search ─────────────────────────────────────────────────────────────────

    @Test
    fun `search filters by filename case-insensitively`() = runTest {
        uploadsFlow.value = sampleUploads()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onSearchChange("INVOICE")
        val filtered = viewModel.filteredUploads(viewModel.uiState.value)
        assertEquals(1, filtered.size)
        assertEquals("invoice.pdf", filtered.first().fileName)
    }

    @Test
    fun `blank search returns all uploads`() = runTest {
        uploadsFlow.value = sampleUploads()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onSearchChange("")
        val filtered = viewModel.filteredUploads(viewModel.uiState.value)
        assertEquals(4, filtered.size)
    }

    @Test
    fun `search with no match returns empty list`() = runTest {
        uploadsFlow.value = sampleUploads()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onSearchChange("nonexistent_file.pdf")
        val filtered = viewModel.filteredUploads(viewModel.uiState.value)
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `search and filter are combined`() = runTest {
        uploadsFlow.value = sampleUploads()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onFilterChange(HistoryFilter.SUCCESS)
        viewModel.onSearchChange("invoice")
        val filtered = viewModel.filteredUploads(viewModel.uiState.value)
        assertEquals(1, filtered.size)
        assertEquals("invoice.pdf", filtered.first().fileName)
    }

    @Test
    fun `clearSearch resets search query to empty string`() {
        viewModel.onSearchChange("invoice")
        viewModel.clearSearch()
        assertEquals("", viewModel.uiState.value.searchQuery)
    }

    // ── cleanupSuccessful ─────────────────────────────────────────────────────

    @Test
    fun `cleanupSuccessful delegates to dao with a timestamp`() = runTest {
        viewModel.cleanupSuccessful()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { uploadDao.cleanupOlderThan(any()) }
    }

    // ── HistoryFilter labels ──────────────────────────────────────────────────

    @Test
    fun `HistoryFilter labels are correct`() {
        assertEquals("Alle",           HistoryFilter.ALL.label)
        assertEquals("Erfolgreich",    HistoryFilter.SUCCESS.label)
        assertEquals("Fehlgeschlagen", HistoryFilter.FAILED.label)
        assertEquals("Läuft",          HistoryFilter.RUNNING.label)
    }
}
