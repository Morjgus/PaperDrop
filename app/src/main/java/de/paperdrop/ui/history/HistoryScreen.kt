package de.paperdrop.ui.history

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.paperdrop.data.db.UploadEntity
import de.paperdrop.data.db.UploadStatus
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val filtered = viewModel.filteredUploads(uiState)
    var showCleanupDialog by remember { mutableStateOf(false) }

    val lazyListState = rememberLazyListState()
    var autoScrollEnabled by remember { mutableStateOf(true) }

    // Disable auto-scroll the moment the user touches the list to scroll manually
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) autoScrollEnabled = false
                return Offset.Zero
            }
        }
    }

    // Re-enable auto-scroll once the user scrolls back to the top
    LaunchedEffect(lazyListState) {
        snapshotFlow { lazyListState.firstVisibleItemIndex }
            .collect { index -> if (index == 0) autoScrollEnabled = true }
    }

    // Scroll to top when a new entry arrives (newest items are at index 0)
    LaunchedEffect(filtered.size) {
        if (autoScrollEnabled && filtered.isNotEmpty()) {
            lazyListState.animateScrollToItem(0)
        }
    }

    if (showCleanupDialog) {
        AlertDialog(
            onDismissRequest = { showCleanupDialog = false },
            icon    = { Icon(Icons.Default.DeleteSweep, null) },
            title   = { Text("Verlauf löschen") },
            text    = { Text("Alle Einträge werden aus dem Verlauf entfernt. Dokumente in Paperless bleiben erhalten.") },
            confirmButton = { TextButton(onClick = { viewModel.clearAll(); showCleanupDialog = false }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { showCleanupDialog = false }) { Text("Abbrechen") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Upload-Verlauf") },
                actions = {
                    IconButton(onClick = { showCleanupDialog = true }) {
                        Icon(Icons.Default.DeleteSweep, "Alte Einträge löschen")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!uiState.isLoading) StatsRow(uiState.stats)
            SearchBar(uiState.searchQuery, viewModel::onSearchChange, viewModel::clearSearch)
            FilterChipRow(uiState.activeFilter, viewModel::onFilterChange, uiState.stats)
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

            AnimatedContent(targetState = uiState.isLoading, label = "content") { loading ->
                when {
                    loading         -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                    filtered.isEmpty() -> EmptyState(uiState.activeFilter != HistoryFilter.ALL || uiState.searchQuery.isNotBlank())
                    else -> LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.nestedScroll(nestedScrollConnection),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filtered, key = { it.id }) { upload -> UploadCard(upload) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsRow(stats: UploadStats) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard(Modifier.weight(1f), stats.total.toString(),   "Gesamt",      MaterialTheme.colorScheme.primary)
        StatCard(Modifier.weight(1f), stats.success.toString(), "Erfolgreich", Color(0xFF4CAF50))
        StatCard(Modifier.weight(1f), stats.failed.toString(),  "Fehler",      Color(0xFFF44336))
        if (stats.running > 0) StatCard(Modifier.weight(1f), stats.running.toString(), "Läuft", Color(0xFF2196F3))
    }
}

@Composable
private fun StatCard(modifier: Modifier, value: String, label: String, color: Color) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, onClear: () -> Unit) {
    OutlinedTextField(
        value = query, onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("Dateiname suchen...") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = {
            AnimatedVisibility(visible = query.isNotBlank()) {
                IconButton(onClick = onClear) { Icon(Icons.Default.Clear, "Suche löschen") }
            }
        },
        singleLine = true, shape = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun FilterChipRow(active: HistoryFilter, onSelect: (HistoryFilter) -> Unit, stats: UploadStats) {
    val counts = mapOf(HistoryFilter.ALL to stats.total, HistoryFilter.SUCCESS to stats.success, HistoryFilter.FAILED to stats.failed, HistoryFilter.RUNNING to stats.running)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(HistoryFilter.entries) { filter ->
            FilterChip(
                selected = active == filter,
                onClick  = { onSelect(filter) },
                label    = { Text("${filter.label} (${counts[filter] ?: 0})") },
                leadingIcon = if (active == filter) { { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) } } else null
            )
        }
    }
}

private val DuplicateColor = Color(0xFFFF9800)

@Composable
private fun UploadCard(upload: UploadEntity) {
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMAN) }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusIndicator(upload.status, upload.isDuplicate)
            Column(modifier = Modifier.weight(1f)) {
                Text(upload.fileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(dateFormat.format(Date(upload.timestamp)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (upload.status == UploadStatus.FAILED && upload.errorMessage != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(upload.errorMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (upload.status == UploadStatus.SUCCESS && upload.documentId != null && !upload.isDuplicate) {
                    Spacer(Modifier.height(4.dp))
                    Text("Dokument #${upload.documentId}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF4CAF50))
                }
                if (upload.isDuplicate) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(DuplicateColor.copy(alpha = 0.1f))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Warning, null, Modifier.size(14.dp), tint = DuplicateColor)
                        Text(
                            "Duplikat – Dokument #${upload.documentId} existiert bereits in Paperless. " +
                            "Datei wurde nicht erneut importiert.",
                            style = MaterialTheme.typography.bodySmall,
                            color = DuplicateColor
                        )
                    }
                }
            }
            if (upload.status == UploadStatus.RUNNING) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun StatusIndicator(status: UploadStatus, isDuplicate: Boolean = false) {
    val (color, icon) = when {
        isDuplicate              -> DuplicateColor to Icons.Default.Warning
        status == UploadStatus.SUCCESS -> Color(0xFF4CAF50) to Icons.Default.CheckCircle
        status == UploadStatus.FAILED  -> Color(0xFFF44336) to Icons.Default.Error
        else                           -> Color(0xFF2196F3) to Icons.Default.Sync
    }
    Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Icon(icon, status.name, tint = color, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun EmptyState(hasFilter: Boolean) {
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(if (hasFilter) Icons.Default.SearchOff else Icons.Default.CloudUpload, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        Spacer(Modifier.height(16.dp))
        Text(if (hasFilter) "Keine Ergebnisse" else "Noch keine Uploads", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(if (hasFilter) "Versuche einen anderen Filter." else "PDFs im überwachten Ordner werden hier aufgelistet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
    }
}
