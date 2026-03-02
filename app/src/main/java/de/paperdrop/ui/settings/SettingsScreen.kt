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
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.paperdrop.R
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

    val savedText = stringResource(R.string.settings_saved)
    LaunchedEffect(uiState.savedFeedback) {
        if (uiState.savedFeedback) {
            snackbarHostState.showSnackbar(savedText)
            viewModel.onSavedFeedbackShown()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_screen_title)) }) },
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
            SectionHeader(stringResource(R.string.settings_section_connection))
            OutlinedTextField(
                value = uiState.paperlessUrl, onValueChange = viewModel::onUrlChange,
                label = { Text(stringResource(R.string.settings_url_label)) }, placeholder = { Text(stringResource(R.string.settings_url_placeholder)) },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = uiState.urlError != null,
                supportingText = uiState.urlError?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.apiToken, onValueChange = viewModel::onTokenChange,
                label = { Text(stringResource(R.string.settings_token_label)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            ConnectionTestButton(state = uiState.connectionState, onTest = viewModel::testConnection)
            HorizontalDivider()

            // ── Ordner ──────────────────────────────────────────
            SectionHeader(stringResource(R.string.settings_section_folder))
            FolderPickerRow(label = stringResource(R.string.settings_source_folder_label), uri = uiState.watchFolderUri, onPick = { folderPicker.launch(null) })
            HorizontalDivider()

            // ── Verhalten ───────────────────────────────────────
            SectionHeader(stringResource(R.string.settings_section_after_upload))
            AfterUploadSelector(selected = uiState.afterUpload, onSelect = viewModel::onAfterUploadChange)
            AnimatedVisibility(visible = uiState.afterUpload == AfterUploadAction.MOVE) {
                FolderPickerRow(label = stringResource(R.string.settings_target_folder_label), uri = uiState.moveTargetUri, onPick = { moveTargetPicker.launch(null) })
            }
            HorizontalDivider()

            // ── Labels ──────────────────────────────────────────
            SectionHeader(stringResource(R.string.settings_section_labels))
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
            SectionHeader(stringResource(R.string.settings_section_watching))
            WatchingToggleRow(
                enabled           = uiState.isWatchingEnabled,
                folderConfigured  = uiState.watchFolderUri.isNotBlank(),
                serverConfigured  = uiState.paperlessUrl.isNotBlank() && uiState.apiToken.isNotBlank(),
                onToggle          = viewModel::toggleWatching
            )
            HorizontalDivider()

            Button(onClick = viewModel::saveSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_save_button))
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
            else Text(stringResource(R.string.settings_test_connection_button))
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
    val noFolderText = stringResource(R.string.settings_no_folder_selected)
    val displayPath = remember(uri, noFolderText) {
        if (uri.isBlank()) noFolderText
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
            Text(stringResource(R.string.settings_choose_folder_button))
        }
    }
}

@Composable
private fun AfterUploadSelector(selected: AfterUploadAction, onSelect: (AfterUploadAction) -> Unit) {
    val options = listOf(
        AfterUploadAction.KEEP   to stringResource(R.string.settings_after_upload_keep),
        AfterUploadAction.DELETE to stringResource(R.string.settings_after_upload_delete),
        AfterUploadAction.MOVE   to stringResource(R.string.settings_after_upload_move)
    )
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
                    Text(stringResource(R.string.settings_load_labels_button))
            }
            if (availableLabels.isNotEmpty())
                Text(
                    stringResource(R.string.settings_labels_selected_count, selectedLabelIds.size, availableLabels.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
        }

        if (!serverConfigured)
            Text(stringResource(R.string.settings_labels_config_required), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

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
                Text(stringResource(R.string.settings_auto_watch_label), style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (enabled) stringResource(R.string.settings_watching_active) else stringResource(R.string.settings_watching_inactive),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle, enabled = canEnable)
        }
        if (!canEnable) Text(stringResource(R.string.settings_watching_config_required), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
