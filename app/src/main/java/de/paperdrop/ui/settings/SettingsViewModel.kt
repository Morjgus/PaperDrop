package de.paperdrop.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val workManager: WorkManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                _uiState.update {
                    it.copy(
                        paperlessUrl      = s.paperlessUrl,
                        apiToken          = s.apiToken,
                        watchFolderUri    = s.watchFolderUri,
                        afterUpload       = s.afterUpload,
                        moveTargetUri     = s.moveTargetUri,
                        isWatchingEnabled = s.isWatchingEnabled
                    )
                }
            }
        }
    }

    fun onUrlChange(v: String)   = _uiState.update { it.copy(paperlessUrl = v, connectionState = ConnectionState.Idle) }
    fun onTokenChange(v: String) = _uiState.update { it.copy(apiToken = v,     connectionState = ConnectionState.Idle) }
    fun onAfterUploadChange(a: AfterUploadAction) = _uiState.update { it.copy(afterUpload = a) }

    fun onFolderSelected(uri: Uri, context: Context) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        _uiState.update { it.copy(watchFolderUri = uri.toString()) }
    }

    fun onMoveTargetSelected(uri: Uri, context: Context) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        _uiState.update { it.copy(moveTargetUri = uri.toString()) }
    }

    fun saveSettings() {
        viewModelScope.launch {
            val s = _uiState.value
            settingsRepository.updateUrl(s.paperlessUrl)
            settingsRepository.updateToken(s.apiToken)
            settingsRepository.updateFolderUri(s.watchFolderUri)
            settingsRepository.updateAfterUpload(s.afterUpload)
            settingsRepository.updateMoveTargetUri(s.moveTargetUri)
            _uiState.update { it.copy(savedFeedback = true) }
        }
    }

    fun onSavedFeedbackShown() = _uiState.update { it.copy(savedFeedback = false) }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(connectionState = ConnectionState.Loading) }
            val result = paperlessRepository.testConnection(_uiState.value.paperlessUrl, _uiState.value.apiToken)
            _uiState.update {
                it.copy(
                    connectionState = if (result.isSuccess) ConnectionState.Success
                    else ConnectionState.Error(result.exceptionOrNull()?.message ?: "Fehler")
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
    val paperlessUrl: String      = "",
    val apiToken: String          = "",
    val watchFolderUri: String    = "",
    val afterUpload: AfterUploadAction = AfterUploadAction.KEEP,
    val moveTargetUri: String     = "",
    val isWatchingEnabled: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.Idle,
    val savedFeedback: Boolean    = false
)

sealed class ConnectionState {
    object Idle    : ConnectionState()
    object Loading : ConnectionState()
    object Success : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
