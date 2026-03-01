package de.paperdrop.ui.settings

import androidx.work.WorkManager
import de.paperdrop.data.api.PaperlessRepository
import de.paperdrop.data.preferences.AfterUploadAction
import de.paperdrop.data.preferences.AppSettings
import de.paperdrop.data.preferences.SettingsRepository
import de.paperdrop.worker.DirectoryPollingWorker
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
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
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val settingsRepository: SettingsRepository = mockk()
    private val paperlessRepository: PaperlessRepository = mockk()
    private val workManager: WorkManager = mockk(relaxed = true)
    private val settingsFlow = MutableStateFlow(AppSettings())
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { settingsRepository.settings } returns settingsFlow
        coEvery { settingsRepository.updateUrl(any()) } just Runs
        coEvery { settingsRepository.updateToken(any()) } just Runs
        coEvery { settingsRepository.updateFolderUri(any()) } just Runs
        coEvery { settingsRepository.updateAfterUpload(any()) } just Runs
        coEvery { settingsRepository.updateMoveTargetUri(any()) } just Runs
        coEvery { settingsRepository.updateWatchingEnabled(any()) } just Runs
        mockkObject(DirectoryPollingWorker.Companion)
        every { DirectoryPollingWorker.schedule(any()) } just Runs
        every { DirectoryPollingWorker.cancel(any()) } just Runs
        viewModel = SettingsViewModel(settingsRepository, paperlessRepository, workManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkObject(DirectoryPollingWorker.Companion)
    }

    // ── initial state ─────────────────────────────────────────────────────────

    @Test
    fun `initial state reflects default AppSettings`() = runTest {
        settingsFlow.value = AppSettings(
            paperlessUrl = "https://server.example.com",
            apiToken     = "tok"
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("https://server.example.com", state.paperlessUrl)
        assertEquals("tok", state.apiToken)
    }

    @Test
    fun `initial connectionState is Idle`() {
        assertTrue(viewModel.uiState.value.connectionState is ConnectionState.Idle)
    }

    // ── field mutations ───────────────────────────────────────────────────────

    @Test
    fun `onUrlChange updates paperlessUrl`() {
        viewModel.onUrlChange("https://new.example.com")
        assertEquals("https://new.example.com", viewModel.uiState.value.paperlessUrl)
    }

    @Test
    fun `onUrlChange resets connectionState to Idle`() {
        viewModel.onUrlChange("https://any.example.com")
        assertTrue(viewModel.uiState.value.connectionState is ConnectionState.Idle)
    }

    @Test
    fun `onTokenChange updates apiToken`() {
        viewModel.onTokenChange("new-token")
        assertEquals("new-token", viewModel.uiState.value.apiToken)
    }

    @Test
    fun `onTokenChange resets connectionState to Idle`() {
        viewModel.onTokenChange("token")
        assertTrue(viewModel.uiState.value.connectionState is ConnectionState.Idle)
    }

    @Test
    fun `onAfterUploadChange updates afterUpload action`() {
        viewModel.onAfterUploadChange(AfterUploadAction.DELETE)
        assertEquals(AfterUploadAction.DELETE, viewModel.uiState.value.afterUpload)
    }

    @Test
    fun `onAfterUploadChange to MOVE sets MOVE action`() {
        viewModel.onAfterUploadChange(AfterUploadAction.MOVE)
        assertEquals(AfterUploadAction.MOVE, viewModel.uiState.value.afterUpload)
    }

    // ── testConnection ────────────────────────────────────────────────────────

    @Test
    fun `testConnection sets Loading immediately then Success`() = runTest {
        coEvery { paperlessRepository.testConnection(any(), any()) } returns Result.success(Unit)

        viewModel.testConnection()

        // Still in Loading while coroutine hasn't completed
        assertEquals(ConnectionState.Loading, viewModel.uiState.value.connectionState)

        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ConnectionState.Success, viewModel.uiState.value.connectionState)
    }

    @Test
    fun `testConnection sets Error state on failure`() = runTest {
        coEvery { paperlessRepository.testConnection(any(), any()) } returns
            Result.failure(Exception("Unauthorized"))

        viewModel.testConnection()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value.connectionState
        assertTrue(state is ConnectionState.Error)
        assertTrue((state as ConnectionState.Error).message.contains("Unauthorized"))
    }

    @Test
    fun `testConnection passes current URL and token to repository`() = runTest {
        viewModel.onUrlChange("https://my-server.com")
        viewModel.onTokenChange("secret-token")
        coEvery {
            paperlessRepository.testConnection("https://my-server.com", "secret-token")
        } returns Result.success(Unit)

        viewModel.testConnection()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { paperlessRepository.testConnection("https://my-server.com", "secret-token") }
    }

    @Test
    fun `testConnection uses fallback error message when exception has no message`() = runTest {
        coEvery { paperlessRepository.testConnection(any(), any()) } returns
            Result.failure(Exception())

        viewModel.testConnection()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value.connectionState
        assertTrue(state is ConnectionState.Error)
        assertEquals("Fehler", (state as ConnectionState.Error).message)
    }

    // ── saveSettings ──────────────────────────────────────────────────────────

    @Test
    fun `saveSettings persists all fields to repository`() = runTest {
        viewModel.onUrlChange("https://server.com")
        viewModel.onTokenChange("tok")
        viewModel.onAfterUploadChange(AfterUploadAction.DELETE)

        viewModel.saveSettings()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { settingsRepository.updateUrl("https://server.com") }
        coVerify { settingsRepository.updateToken("tok") }
        coVerify { settingsRepository.updateAfterUpload(AfterUploadAction.DELETE) }
    }

    @Test
    fun `saveSettings sets savedFeedback to true`() = runTest {
        viewModel.saveSettings()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.savedFeedback)
    }

    @Test
    fun `onSavedFeedbackShown clears savedFeedback`() = runTest {
        viewModel.saveSettings()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onSavedFeedbackShown()

        assertFalse(viewModel.uiState.value.savedFeedback)
    }

    // ── toggleWatching ────────────────────────────────────────────────────────

    @Test
    fun `toggleWatching true persists and schedules worker`() = runTest {
        viewModel.toggleWatching(true)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { settingsRepository.updateWatchingEnabled(true) }
        verify { DirectoryPollingWorker.schedule(workManager) }
    }

    @Test
    fun `toggleWatching false persists and cancels worker`() = runTest {
        viewModel.toggleWatching(false)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { settingsRepository.updateWatchingEnabled(false) }
        verify { DirectoryPollingWorker.cancel(workManager) }
    }

    // ── ConnectionState ───────────────────────────────────────────────────────

    @Test
    fun `ConnectionState Error exposes its message`() {
        val error = ConnectionState.Error("Server unreachable")
        assertEquals("Server unreachable", error.message)
    }

    @Test
    fun `ConnectionState subtypes are distinct`() {
        assertTrue(ConnectionState.Idle is ConnectionState.Idle)
        assertTrue(ConnectionState.Loading is ConnectionState.Loading)
        assertTrue(ConnectionState.Success is ConnectionState.Success)
        assertTrue(ConnectionState.Error("x") is ConnectionState.Error)
    }
}
