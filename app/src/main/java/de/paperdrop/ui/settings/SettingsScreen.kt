package de.paperdrop.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.paperdrop.data.api.PaperlessLabel
import de.paperdrop.data.preferences.AfterUploadAction

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.onFolderSelected(it, context) }
    }
    val moveTargetPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.onMoveTargetSelected(it, context) }
    }

    LaunchedEffect(uiState.savedFeedback) {
        if (uiState.savedFeedback) {
            snackbarHostState.showSnackbar("Einstellungen gespeichert")
            viewModel.onSavedFeedbackShown()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Einstellungen") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ── Verbindung ──────────────────────────────────────
            SectionHeader("Paperless-ngx Verbindung")
            OutlinedTextField(
                value = uiState.paperlessUrl, onValueChange = viewModel::onUrlChange,
                label = { Text("Server-URL") }, placeholder = { Text("https://paperless.example.com") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.apiToken, onValueChange = viewModel::onTokenChange,
                label = { Text("API Token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            ConnectionTestButton(state = uiState.connectionState, onTest = viewModel::testConnection)
            HorizontalDivider()

            // ── Ordner ──────────────────────────────────────────
            SectionHeader("Überwachter Ordner")
            FolderPickerRow(label = "Quellordner", uri = uiState.watchFolderUri, onPick = { folderPicker.launch(null) })
            HorizontalDivider()

            // ── Verhalten ───────────────────────────────────────
            SectionHeader("Nach dem Upload")
            AfterUploadSelector(selected = uiState.afterUpload, onSelect = viewModel::onAfterUploadChange)
            AnimatedVisibility(visible = uiState.afterUpload == AfterUploadAction.MOVE) {
                FolderPickerRow(label = "Zielordner", uri = uiState.moveTargetUri, onPick = { moveTargetPicker.launch(null) })
            }
            HorizontalDivider()

            // ── Labels ──────────────────────────────────────────
            SectionHeader("Labels")
            LabelSelector(
                availableLabels  = uiState.availableLabels,
                selectedLabelIds = uiState.selectedLabelIds,
                isLoading        = uiState.labelsLoading,
                error            = uiState.labelsError,
                serverConfigured = uiState.paperlessUrl.isNotBlank() && uiState.apiToken.isNotBlank(),
                onFetch          = viewModel::fetchLabels,
                onToggle         = viewModel::onLabelToggled
            )
            HorizontalDivider()

            // ── Überwachung ─────────────────────────────────────
            SectionHeader("Überwachung")
            WatchingToggleRow(
                enabled           = uiState.isWatchingEnabled,
                folderConfigured  = uiState.watchFolderUri.isNotBlank(),
                serverConfigured  = uiState.paperlessUrl.isNotBlank() && uiState.apiToken.isNotBlank(),
                onToggle          = viewModel::toggleWatching
            )
            HorizontalDivider()

            Button(onClick = viewModel::saveSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Einstellungen speichern")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable private fun SectionHeader(title: String) =
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
private fun ConnectionTestButton(state: ConnectionState, onTest: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onTest, enabled = state !is ConnectionState.Loading) {
            if (state is ConnectionState.Loading)
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            else Text("Verbindung testen")
        }
        when (state) {
            is ConnectionState.Success -> Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50))
            is ConnectionState.Error   -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
    }
}

@Composable
private fun FolderPickerRow(label: String, uri: String, onPick: () -> Unit) {
    val displayPath = remember(uri) {
        if (uri.isBlank()) "Kein Ordner gewählt"
        else android.net.Uri.decode(uri).substringAfterLast(":").ifBlank { uri }
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(displayPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onPick) {
            Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Wählen")
        }
    }
}

@Composable
private fun AfterUploadSelector(selected: AfterUploadAction, onSelect: (AfterUploadAction) -> Unit) {
    val options = listOf(AfterUploadAction.KEEP to "Behalten", AfterUploadAction.DELETE to "Löschen", AfterUploadAction.MOVE to "Verschieben")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (action, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .selectable(selected = selected == action, onClick = { onSelect(action) }, role = Role.RadioButton)
                    .padding(vertical = 4.dp)
            ) {
                RadioButton(selected = selected == action, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(label)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelSelector(
    availableLabels: List<PaperlessLabel>,
    selectedLabelIds: Set<Int>,
    isLoading: Boolean,
    error: String?,
    serverConfigured: Boolean,
    onFetch: () -> Unit,
    onToggle: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onFetch, enabled = serverConfigured && !isLoading) {
                if (isLoading)
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else
                    Text("Labels laden")
            }
            if (availableLabels.isNotEmpty())
                Text(
                    "${selectedLabelIds.size} von ${availableLabels.size} gewählt",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
        }

        if (!serverConfigured)
            Text("Bitte zuerst URL und Token konfigurieren.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

        if (error != null)
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

        if (availableLabels.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                availableLabels.forEach { label ->
                    FilterChip(
                        selected = label.id in selectedLabelIds,
                        onClick  = { onToggle(label.id) },
                        label    = { Text(label.name) }
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchingToggleRow(enabled: Boolean, folderConfigured: Boolean, serverConfigured: Boolean, onToggle: (Boolean) -> Unit) {
    val canEnable = folderConfigured && serverConfigured
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Automatisch überwachen", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (enabled) "Aktiv — prüft alle 15 Minuten" else "Inaktiv",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle, enabled = canEnable)
        }
        if (!canEnable) Text("Bitte zuerst URL, Token und Ordner konfigurieren.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
