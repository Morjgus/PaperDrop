package de.paperdrop.worker

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import de.paperdrop.data.preferences.AppSettings
import de.paperdrop.data.preferences.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ForegroundFolderWatcherTest {

    private lateinit var context: Context
    private lateinit var settingsFlow: MutableStateFlow<AppSettings>
    private val settingsRepository = mockk<SettingsRepository>()
    private val workManager = mockk<WorkManager>(relaxed = true)
    private lateinit var watcher: ForegroundFolderWatcher

    @Before
    fun setUp() {
        context      = ApplicationProvider.getApplicationContext()
        settingsFlow = MutableStateFlow(AppSettings())
        every { settingsRepository.settings } returns settingsFlow
        watcher = ForegroundFolderWatcher(context, settingsRepository, workManager)
    }

    @After
    fun tearDown() {
        watcher.stop()
    }

    private fun watchingSettings(watchFolderUri: String = FOLDER_URI) = AppSettings(
        isWatchingEnabled = true,
        watchFolderUri    = watchFolderUri
    )

    private fun childrenUriFor(treeUriString: String): Uri {
        val treeUri = Uri.parse(treeUriString)
        return DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
    }

    private fun verifyScanCount(count: Int) {
        verify(exactly = count) {
            workManager.enqueueUniqueWork("foreground_scan", ExistingWorkPolicy.APPEND_OR_REPLACE, any<OneTimeWorkRequest>())
        }
    }

    @Test
    fun `start registers observer and triggers initial scan`() = runTest {
        settingsFlow.value = watchingSettings()
        watcher.start(StandardTestDispatcher(testScheduler))
        runCurrent()

        val childrenUri = childrenUriFor(FOLDER_URI)
        assertTrue(shadowOf(context.contentResolver).getContentObservers(childrenUri).isNotEmpty())
        verifyScanCount(1)
    }

    @Test
    fun `disabled or blank folder uri registers no observer and triggers no scan`() = runTest {
        watcher.start(StandardTestDispatcher(testScheduler))
        runCurrent()

        settingsFlow.value = AppSettings(isWatchingEnabled = false, watchFolderUri = FOLDER_URI)
        runCurrent()
        verifyScanCount(0)

        settingsFlow.value = AppSettings(isWatchingEnabled = true, watchFolderUri = "")
        runCurrent()
        verifyScanCount(0)
    }

    @Test
    fun `notify triggers scan only after debounce elapses`() = runTest {
        settingsFlow.value = watchingSettings()
        watcher.start(StandardTestDispatcher(testScheduler))
        runCurrent()
        verifyScanCount(1)

        val childrenUri = childrenUriFor(FOLDER_URI)
        context.contentResolver.notifyChange(childrenUri, null)
        runCurrent()
        verifyScanCount(1)

        advanceTimeBy(ForegroundFolderWatcher.DEBOUNCE_MS + 1)
        runCurrent()
        verifyScanCount(2)
    }

    @Test
    fun `burst of five notifications triggers a single debounced scan`() = runTest {
        settingsFlow.value = watchingSettings()
        watcher.start(StandardTestDispatcher(testScheduler))
        runCurrent()
        verifyScanCount(1)

        val childrenUri = childrenUriFor(FOLDER_URI)
        repeat(5) {
            context.contentResolver.notifyChange(childrenUri, null)
            runCurrent()
        }
        verifyScanCount(1)

        advanceTimeBy(ForegroundFolderWatcher.DEBOUNCE_MS + 1)
        runCurrent()
        verifyScanCount(2)
    }

    @Test
    fun `debounce timer resets on each notification`() = runTest {
        settingsFlow.value = watchingSettings()
        watcher.start(StandardTestDispatcher(testScheduler))
        runCurrent()
        verifyScanCount(1)

        val childrenUri = childrenUriFor(FOLDER_URI)

        context.contentResolver.notifyChange(childrenUri, null)
        runCurrent()
        advanceTimeBy(300L)
        runCurrent()

        context.contentResolver.notifyChange(childrenUri, null)
        runCurrent()
        advanceTimeBy(300L)
        runCurrent()

        context.contentResolver.notifyChange(childrenUri, null)
        runCurrent()
        verifyScanCount(1)

        advanceTimeBy(ForegroundFolderWatcher.DEBOUNCE_MS + 1)
        runCurrent()
        verifyScanCount(2)
    }

    @Test
    fun `changing watch folder uri re-registers observer on the new uri`() = runTest {
        settingsFlow.value = watchingSettings(FOLDER_URI)
        watcher.start(StandardTestDispatcher(testScheduler))
        runCurrent()

        val oldChildrenUri = childrenUriFor(FOLDER_URI)
        assertTrue(shadowOf(context.contentResolver).getContentObservers(oldChildrenUri).isNotEmpty())

        settingsFlow.value = watchingSettings(OTHER_FOLDER_URI)
        runCurrent()

        val newChildrenUri = childrenUriFor(OTHER_FOLDER_URI)
        assertTrue(shadowOf(context.contentResolver).getContentObservers(oldChildrenUri).isEmpty())
        assertTrue(shadowOf(context.contentResolver).getContentObservers(newChildrenUri).isNotEmpty())
    }

    @Test
    fun `disabling watching mid-session unregisters the observer`() = runTest {
        settingsFlow.value = watchingSettings()
        watcher.start(StandardTestDispatcher(testScheduler))
        runCurrent()

        val childrenUri = childrenUriFor(FOLDER_URI)
        assertTrue(shadowOf(context.contentResolver).getContentObservers(childrenUri).isNotEmpty())

        settingsFlow.value = watchingSettings().copy(isWatchingEnabled = false)
        runCurrent()

        assertTrue(shadowOf(context.contentResolver).getContentObservers(childrenUri).isEmpty())
    }

    @Test
    fun `unrelated settings change does not re-register or trigger an extra scan`() = runTest {
        settingsFlow.value = watchingSettings()
        watcher.start(StandardTestDispatcher(testScheduler))
        runCurrent()
        verifyScanCount(1)

        val childrenUri = childrenUriFor(FOLDER_URI)
        val observersBefore = shadowOf(context.contentResolver).getContentObservers(childrenUri).toList()

        settingsFlow.value = watchingSettings().copy(apiToken = "new-token")
        runCurrent()

        val observersAfter = shadowOf(context.contentResolver).getContentObservers(childrenUri).toList()
        assertEquals(observersBefore, observersAfter)
        verifyScanCount(1)
    }

    @Test
    fun `stop unregisters the observer`() = runTest {
        settingsFlow.value = watchingSettings()
        watcher.start(StandardTestDispatcher(testScheduler))
        runCurrent()

        val childrenUri = childrenUriFor(FOLDER_URI)
        assertTrue(shadowOf(context.contentResolver).getContentObservers(childrenUri).isNotEmpty())

        watcher.stop()
        runCurrent()

        assertTrue(shadowOf(context.contentResolver).getContentObservers(childrenUri).isEmpty())
    }

    @Test
    fun `malformed non-tree uri does not crash and registers no observer`() = runTest {
        settingsFlow.value = watchingSettings(MALFORMED_URI)
        watcher.start(StandardTestDispatcher(testScheduler))
        runCurrent()

        verifyScanCount(0)
        assertTrue(shadowOf(context.contentResolver).getContentObservers(Uri.parse(MALFORMED_URI)).isEmpty())
    }

    companion object {
        private const val FOLDER_URI       = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
        private const val OTHER_FOLDER_URI = "content://com.android.externalstorage.documents/tree/primary%3AOther"
        private const val MALFORMED_URI    = "content://com.android.externalstorage.documents/document/primary%3ADocuments"
    }
}
