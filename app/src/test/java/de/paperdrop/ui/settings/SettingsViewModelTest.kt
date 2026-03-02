package de.paperdrop.ui.settings

import android.content.Context
import androidx.work.WorkManager
import de.paperdrop.MainDispatcherRule
import de.paperdrop.R
import de.paperdrop.data.api.PaperlessRepository
import de.paperdrop.data.preferences.AfterUploadAction
import de.paperdrop.data.preferences.AppSettings
import de.paperdrop.data.preferences.SettingsRepository
import de.paperdrop.worker.DirectoryPollingWorker
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val paperlessRepository = mockk<PaperlessRepository>()
    private val workManager = mockk<WorkManager>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        mockkObject(DirectoryPollingWorker.Companion)
        every { DirectoryPollingWorker.schedule(any()) } just Runs
        every { DirectoryPollingWorker.cancel(any()) } just Runs
        every { settingsRepository.settings } returns flowOf(AppSettings())
        every { context.getString(R.string.error_connection_unknown) } returns "Error"
        viewModel = SettingsViewModel(settingsRepository, paperlessRepository, workManager, context)
    }

    @After
    fun tearDown() {
        unmockkObject(DirectoryPollingWorker.Companion)
        clearAllMocks()
    }

    @Test
    fun `initial state reflects settings from repository`() = runTest {
        every { settingsRepository.settings } returns flowOf(
            AppSettings(paperlessUrl = "http://x", apiToken = "tok")
        )
        viewModel = SettingsViewModel(settingsRepository, paperlessRepository, workManager, context)
        advanceUntilIdle()

        assertEquals("http://x", viewModel.uiState.value.paperlessUrl)
        assertEquals("tok", viewModel.uiState.value.apiToken)
    }

    @Test
    fun `onUrlChange updates URL and resets connection state`() {
        viewModel.onUrlChange("http://new")
        assertEquals("http://new", viewModel.uiState.value.paperlessUrl)
        assertEquals(ConnectionState.Idle, viewModel.uiState.value.connectionState)
    }

    @Test
    fun `onTokenChange updates token and resets connection state`() {
        viewModel.onTokenChange("new-token")
        assertEquals("new-token", viewModel.uiState.value.apiToken)
        assertEquals(ConnectionState.Idle, viewModel.uiState.value.connectionState)
    }

    @Test
    fun `onAfterUploadChange updates afterUpload`() {
        viewModel.onAfterUploadChange(AfterUploadAction.DELETE)
        assertEquals(AfterUploadAction.DELETE, viewModel.uiState.value.afterUpload)
    }

    @Test
    fun `saveSettings persists all fields to repository`() = runTest {
        viewModel.onUrlChange("http://server")
        viewModel.onTokenChange("mytoken")

        viewModel.saveSettings()
        advanceUntilIdle()

        coVerify { settingsRepository.updateUrl("http://server") }
        coVerify { settingsRepository.updateToken("mytoken") }
        coVerify { settingsRepository.updateFolderUri(any()) }
        coVerify { settingsRepository.updateAfterUpload(any()) }
        coVerify { settingsRepository.updateMoveTargetUri(any()) }
    }

    @Test
    fun `saveSettings sets savedFeedback to true`() = runTest {
        viewModel.saveSettings()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.savedFeedback)
    }

    @Test
    fun `onSavedFeedbackShown resets savedFeedback`() = runTest {
        viewModel.saveSettings()
        advanceUntilIdle()
        viewModel.onSavedFeedbackShown()
        assertFalse(viewModel.uiState.value.savedFeedback)
    }

    @Test
    fun `testConnection sets Loading then Success state`() = runTest {
        coEvery { paperlessRepository.testConnection(any(), any()) } returns Result.success(Unit)

        viewModel.testConnection()

        // With UnconfinedTestDispatcher the coroutine runs eagerly; after testConnection()
        // the Loading state is set and the suspend call resolves immediately.
        advanceUntilIdle()
        assertEquals(ConnectionState.Success, viewModel.uiState.value.connectionState)
    }

    @Test
    fun `testConnection sets Error state on failure`() = runTest {
        coEvery { paperlessRepository.testConnection(any(), any()) } returns
            Result.failure(Exception("connection refused"))

        viewModel.testConnection()
        advanceUntilIdle()

        val state = viewModel.uiState.value.connectionState
        assertTrue(state is ConnectionState.Error)
        assertEquals("connection refused", (state as ConnectionState.Error).message)
    }

    @Test
    fun `testConnection uses fallback message when exception has no message`() = runTest {
        coEvery { paperlessRepository.testConnection(any(), any()) } returns
            Result.failure(RuntimeException())

        viewModel.testConnection()
        advanceUntilIdle()

        val state = viewModel.uiState.value.connectionState as ConnectionState.Error
        assertEquals("Error", state.message)
    }

    @Test
    fun `toggleWatching true enables watching and schedules worker`() = runTest {
        viewModel.toggleWatching(true)
        advanceUntilIdle()

        coVerify { settingsRepository.updateWatchingEnabled(true) }
        verify { DirectoryPollingWorker.schedule(workManager) }
    }

    @Test
    fun `toggleWatching false disables watching and cancels worker`() = runTest {
        viewModel.toggleWatching(false)
        advanceUntilIdle()

        coVerify { settingsRepository.updateWatchingEnabled(false) }
        verify { DirectoryPollingWorker.cancel(workManager) }
    }

    @Test
    fun `toggleWatching preserves unsaved edits to URL and token`() = runTest {
        val settingsFlow = MutableStateFlow(
            AppSettings(paperlessUrl = "http://saved", apiToken = "saved-token")
        )
        every { settingsRepository.settings } returns settingsFlow

        viewModel = SettingsViewModel(settingsRepository, paperlessRepository, workManager, context)
        advanceUntilIdle()

        // User edits URL and token without saving
        viewModel.onUrlChange("http://new")
        viewModel.onTokenChange("new-token")

        // User toggles watching; simulate DataStore re-emitting after the write
        viewModel.toggleWatching(true)
        settingsFlow.value = settingsFlow.value.copy(isWatchingEnabled = true)
        advanceUntilIdle()

        // Unsaved edits must survive the settings flow re-emission
        assertEquals("http://new", viewModel.uiState.value.paperlessUrl)
        assertEquals("new-token", viewModel.uiState.value.apiToken)
        assertTrue(viewModel.uiState.value.isWatchingEnabled)
    }
}
