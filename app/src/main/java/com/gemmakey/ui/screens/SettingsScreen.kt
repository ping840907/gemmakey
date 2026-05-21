package com.gemmakey.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gemmakey.ai.BackendMode
import com.gemmakey.viewmodel.GEMINI_MODELS
import com.gemmakey.viewmodel.SettingsViewModel

private data class ModeOption(
    val mode: BackendMode,
    val title: String,
    val subtitle: String
)

private val MODE_OPTIONS = listOf(
    ModeOption(
        BackendMode.SMART,
        "智慧切換（建議）",
        "有網路時使用 Gemini，離線或 API 失效時自動切回本機 Gemma"
    ),
    ModeOption(
        BackendMode.GEMINI_ONLY,
        "Gemini API（雲端）",
        "速度快，支援最新模型，需 API Key；無網路時無法使用"
    ),
    ModeOption(
        BackendMode.GEMMA_ONLY,
        "本機 Gemma（隱私）",
        "完全離線，不連線任何雲端服務，保護隱私"
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onSaved: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var showApiKey by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) onSaved()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("推論後端", style = MaterialTheme.typography.titleMedium)

        // ── Backend mode selection ───────────────────────────────────────────
        Card {
            Column(Modifier.padding(8.dp)) {
                MODE_OPTIONS.forEach { option ->
                    val (enabled, unavailableHint) = when (option.mode) {
                        BackendMode.GEMMA_ONLY  ->
                            if (uiState.isGemmaInstalled) true to null
                            else false to "需先下載 Gemma 模型（見 MODEL_SETUP.md）"
                        BackendMode.GEMINI_ONLY ->
                            if (uiState.geminiApiKey.isNotBlank()) true to null
                            else false to "請先設定 API Key"
                        BackendMode.SMART       ->
                            if (uiState.isGemmaInstalled && uiState.geminiApiKey.isNotBlank()) true to null
                            else false to "需同時設置 Gemma 模型與 Gemini API Key"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .alpha(if (enabled) 1f else 0.38f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = uiState.backendMode == option.mode,
                            onClick  = { if (enabled) viewModel.setBackendMode(option.mode) },
                            enabled  = enabled
                        )
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(option.title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (unavailableHint != null) "${option.subtitle}（$unavailableHint）"
                                else option.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // ── Gemini settings (always visible so the API key can be entered first) ──
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

            Text("Gemini 設定", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = uiState.geminiApiKey,
                onValueChange = viewModel::setApiKey,
                label = { Text("API Key") },
                placeholder = { Text("AIza...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showApiKey) VisualTransformation.None
                                      else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            if (showApiKey) Icons.Default.VisibilityOff
                            else Icons.Default.Visibility,
                            contentDescription = null
                        )
                    }
                }
            )

            // Model picker
            ExposedDropdownMenuBox(
                expanded = modelMenuExpanded,
                onExpandedChange = { modelMenuExpanded = it }
            ) {
                OutlinedTextField(
                    value = uiState.geminiModelName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("模型") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelMenuExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = modelMenuExpanded,
                    onDismissRequest = { modelMenuExpanded = false }
                ) {
                    GEMINI_MODELS.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                viewModel.setModelName(name)
                                modelMenuExpanded = false
                            }
                        )
                    }
                }
            }

            Text(
                "API Key 可至 aistudio.google.com 免費取得",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = viewModel::save,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Check, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("儲存並套用")
        }
    }
}
