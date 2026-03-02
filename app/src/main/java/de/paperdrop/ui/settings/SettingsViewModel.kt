package de.paperdrop.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.paperdrop.R
import de.paperdrop.data.api.PaperlessLabel
import de.paperdrop.data.api.PaperlessRepository
import de.paperdrop.data.preferences.AfterUploadAction
import de.paperdrop.data.preferences.SettingsRepository
import de.paperdrop.worker.DirectoryPollingWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val paperlessRepository: PaperlessRepository,
    private val workManager: WorkManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var settingsLoaded = false

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                if (!settingsLoaded) {
                    _uiState.update {
                        it.copy(
                            paperlessUrl      = s.paperlessUrl,
                            apiToken          = s.apiToken,
                            watchFolderUri    = s.watchFolderUri,
                            afterUpload       = s.afterUpload,
                            moveTargetUri     = s.moveTargetUri,
                            isWatchingEnabled = s.isWatchingEnabled,
                            selectedLabelIds  = s.selectedLabelIds
                        )
                    }
                    settingsLoaded = true
                } else {
                    _uiState.update { it.copy(isWatchingEnabled = s.isWatchingEnabled) }
                }
            }
        }
    }

    fun onUrlChange(v: String)   = _uiState.update { it.copy(paperlessUrl = v, connectionState = ConnectionState.Idle, urlError = null) }
    fun onTokenChange(v: String) = _uiState.update { it.copy(apiToken = v,     connectionState = ConnectionState.Idle) }
    fun onAfterUploadChange(a: AfterUploadAction) = _uiState.update { it.copy(afterUpload = a) }

    fun onFolderSelected(uri: Uri, context: Context) {
        takePersistablePermission(uri, context)
        _uiState.update { it.copy(watchFolderUri = uri.toString()) }
    }

    fun onMoveTargetSelected(uri: Uri, context: Context) {
        takePersistablePermission(uri, context)
        _uiState.update { it.copy(moveTargetUri = uri.toString()) }
    }

    private fun takePersistablePermission(uri: Uri, context: Context) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Some providers only grant a subset of the requested flags.
            // Fall back to read-only persistence; writes will still work for
            // the current session via the originally granted URI permission.
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Permission could not be persisted at all — the URI is still
                // usable for the current session via the activity's grant.
            }
        }
    }

    fun onLabelToggled(id: Int) {
        _uiState.update { state ->
            val updated = if (id in state.selectedLabelIds)
                state.selectedLabelIds - id
            else
                state.selectedLabelIds + id
            state.copy(selectedLabelIds = updated)
        }
    }

    fun fetchLabels() {
        val url   = _uiState.value.paperlessUrl
        val token = _uiState.value.apiToken
        if (url.isBlank() || token.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(labelsLoading = true, labelsError = null) }
            val result = paperlessRepository.fetchLabels(url, token)
            _uiState.update {
                it.copy(
                    labelsLoading    = false,
                    availableLabels  = result.getOrElse { emptyList() },
                    labelsError      = result.exceptionOrNull()?.message
                )
            }
        }
    }

    fun saveSettings() {
        val s = _uiState.value
        if (s.paperlessUrl.isNotBlank() && !s.paperlessUrl.startsWith("https://", ignoreCase = true)) {
            _uiState.update { it.copy(urlError = context.getString(R.string.error_url_https_required)) }
            return
        }
        viewModelScope.launch {
            settingsRepository.updateUrl(s.paperlessUrl)
            settingsRepository.updateToken(s.apiToken)
            settingsRepository.updateFolderUri(s.watchFolderUri)
            settingsRepository.updateAfterUpload(s.afterUpload)
            settingsRepository.updateMoveTargetUri(s.moveTargetUri)
            settingsRepository.updateLabelIds(s.selectedLabelIds)
            _uiState.update { it.copy(savedFeedback = true) }
        }
    }

    fun onSavedFeedbackShown() = _uiState.update { it.copy(savedFeedback = false) }

    fun testConnection() {
        val url = _uiState.value.paperlessUrl
        if (url.isNotBlank() && !url.startsWith("https://", ignoreCase = true)) {
            _uiState.update { it.copy(urlError = context.getString(R.string.error_url_https_required)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(connectionState = ConnectionState.Loading) }
            val result = paperlessRepository.testConnection(_uiState.value.paperlessUrl, _uiState.value.apiToken)
            _uiState.update {
                it.copy(
                    connectionState = if (result.isSuccess) ConnectionState.Success
                    else ConnectionState.Error(result.exceptionOrNull()?.message ?: context.getString(R.string.error_connection_unknown))
                )
            }
        }
    }

    fun toggleWatching(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateWatchingEnabled(enabled)
            if (enabled) DirectoryPollingWorker.schedule(workManager)
            else         DirectoryPollingWorker.cancel(workManager)
        }
    }
}

data class SettingsUiState(
    val paperlessUrl: String           = "",
    val urlError: String?              = null,
    val apiToken: String               = "",
    val watchFolderUri: String         = "",
    val afterUpload: AfterUploadAction = AfterUploadAction.KEEP,
    val moveTargetUri: String          = "",
    val isWatchingEnabled: Boolean     = false,
    val connectionState: ConnectionState = ConnectionState.Idle,
    val savedFeedback: Boolean         = false,
    val availableLabels: List<PaperlessLabel> = emptyList(),
    val selectedLabelIds: Set<Int>     = emptySet(),
    val labelsLoading: Boolean         = false,
    val labelsError: String?           = null
)

sealed class ConnectionState {
    object Idle    : ConnectionState()
    object Loading : ConnectionState()
    object Success : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
