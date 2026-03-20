package de.paperdrop.ui.history

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import de.paperdrop.R
import de.paperdrop.data.db.UploadDao
import de.paperdrop.data.db.UploadEntity
import de.paperdrop.data.db.UploadStatus
import de.paperdrop.worker.DirectoryPollingWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val uploadDao: UploadDao,
    private val workManager: WorkManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            uploadDao.getAllUploads().collect { uploads ->
                _uiState.update {
                    it.copy(
                        uploads   = uploads,
                        isLoading = false,
                        stats     = UploadStats(
                            total   = uploads.size,
                            success = uploads.count { u -> u.status == UploadStatus.SUCCESS },
                            failed  = uploads.count { u -> u.status == UploadStatus.FAILED },
                            running = uploads.count { u -> u.status == UploadStatus.RUNNING }
                        )
                    )
                }
            }
        }
    }

    fun onFilterChange(filter: HistoryFilter) = _uiState.update { it.copy(activeFilter = filter) }
    fun onSearchChange(query: String)         = _uiState.update { it.copy(searchQuery = query) }
    fun clearSearch()                         = _uiState.update { it.copy(searchQuery = "") }

    fun syncNow() = DirectoryPollingWorker.scanNow(workManager)

    fun clearAll() {
        viewModelScope.launch { uploadDao.deleteAll() }
    }

    fun cleanupSuccessful() {
        viewModelScope.launch {
            val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
            uploadDao.cleanupOlderThan(thirtyDaysAgo)
        }
    }

    fun filteredUploads(state: HistoryUiState): List<UploadEntity> =
        state.uploads
            .filter { upload ->
                when (state.activeFilter) {
                    HistoryFilter.ALL     -> true
                    HistoryFilter.SUCCESS -> upload.status == UploadStatus.SUCCESS
                    HistoryFilter.FAILED  -> upload.status == UploadStatus.FAILED
                    HistoryFilter.RUNNING -> upload.status == UploadStatus.RUNNING
                }
            }
            .filter { upload ->
                state.searchQuery.isBlank() || upload.fileName.contains(state.searchQuery, ignoreCase = true)
            }
}

data class HistoryUiState(
    val uploads: List<UploadEntity> = emptyList(),
    val isLoading: Boolean          = true,
    val activeFilter: HistoryFilter = HistoryFilter.ALL,
    val searchQuery: String         = "",
    val stats: UploadStats          = UploadStats()
)

data class UploadStats(val total: Int = 0, val success: Int = 0, val failed: Int = 0, val running: Int = 0)

enum class HistoryFilter(@StringRes val labelRes: Int) {
    ALL(R.string.history_filter_all),
    SUCCESS(R.string.history_filter_success),
    FAILED(R.string.history_filter_failed),
    RUNNING(R.string.history_filter_running)
}
