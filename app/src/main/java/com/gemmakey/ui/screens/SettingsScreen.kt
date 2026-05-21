package com.gemmakey.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gemmakey.ai.BackendType
import com.gemmakey.viewmodel.GEMINI_MODELS
import com.gemmakey.viewmodel.SettingsViewModel

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

        // ── Backend selection ────────────────────────────────────────────────
        Card {
            Column(Modifier.padding(8.dp)) {
                BackendType.entries.forEach { type ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = uiState.backendType == type,
                            onClick  = { viewModel.setBackendType(type) }
                        )
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(
                                when (type) {
                                    BackendType.GEMMA_LOCAL -> "本機 Gemma（離線，不需 API Key）"
                                    BackendType.GEMINI_API  -> "Gemini API（雲端，需 API Key）"
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                when (type) {
                                    BackendType.GEMMA_LOCAL -> "模型運行於裝置端，保護隱私"
                                    BackendType.GEMINI_API  -> "速度快，支援最新 Gemini 模型"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // ── Gemini settings (shown only when Gemini is selected) ─────────────
        AnimatedVisibility(visible = uiState.backendType == BackendType.GEMINI_API) {
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
                            .menuAnchor()
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
