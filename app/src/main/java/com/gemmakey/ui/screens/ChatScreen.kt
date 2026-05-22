package com.gemmakey.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gemmakey.ai.BackendMode
import com.gemmakey.ai.BackendType
import com.gemmakey.ai.InferenceBackend
import com.gemmakey.ui.components.ConfirmationDialog
import com.gemmakey.ui.components.InputBar
import com.gemmakey.ui.components.MessageBubble
import com.gemmakey.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    uiState.pendingExpense?.let { parsed ->
        ConfirmationDialog(
            parsed   = parsed,
            rawInput = uiState.pendingRawInput,
            onConfirm = viewModel::confirmSave,
            onDismiss = viewModel::dismissConfirmation
        )
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        // ── 頂部欄 ──────────────────────────────────────────────────────────
        TopAppBar(
            title = { Text("記帳助理", style = MaterialTheme.typography.titleMedium) },
            actions = {
                if (uiState.messages.size > 1 && !uiState.isGenerating) {
                    IconButton(onClick = viewModel::clearConversation) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = "清除對話",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        // ── 後端狀態列 ───────────────────────────────────────────────────────
        InferenceStatusBar(
            backendType = uiState.backendType,
            backendMode = uiState.backendMode,
            isOnline    = uiState.isOnline,
            isReady     = uiState.inferenceState.isReady,
            hwBackend   = uiState.inferenceState.backend,
            isLoading   = uiState.inferenceState.isLoading,
            error       = uiState.inferenceState.error
        )

        // Message list
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(uiState.messages, key = { it.id }) { message ->
                MessageBubble(message = message)
            }
        }

        HorizontalDivider(thickness = 0.5.dp)

        // Input bar
        InputBar(
            isGenerating  = uiState.isGenerating,
            onSendText    = viewModel::sendTextMessage,
            onVoiceResult = viewModel::sendVoiceResult,
            onImageSelected = { uri -> viewModel.sendImageUri(uri) }
        )
    }
}

@Composable
private fun InferenceStatusBar(
    backendType: BackendType,
    backendMode: BackendMode,
    isOnline: Boolean,
    isReady: Boolean,
    hwBackend: InferenceBackend,
    isLoading: Boolean,
    error: String?
) {
    AnimatedVisibility(visible = isLoading || error != null || isReady) {
        val smartTag = if (backendMode == BackendMode.SMART) {
            if (isOnline) " · 🌐 自動" else " · 📵 離線"
        } else ""

        val (color, text) = when {
            isLoading -> MaterialTheme.colorScheme.secondaryContainer to when (backendType) {
                BackendType.GEMINI_API  -> "正在連線 Gemini API…"
                BackendType.GEMMA_LOCAL -> "正在載入 Gemma 4 模型…"
            }
            error != null -> MaterialTheme.colorScheme.errorContainer to "⚠️ $error"
            backendType == BackendType.GEMINI_API ->
                MaterialTheme.colorScheme.tertiaryContainer to "✦ Gemini API 就緒$smartTag"
            else -> {
                val label = when (hwBackend) {
                    InferenceBackend.NPU -> "NPU 加速 ⚡"
                    InferenceBackend.GPU -> "GPU 加速"
                    InferenceBackend.CPU -> "CPU 運算"
                }
                MaterialTheme.colorScheme.primaryContainer to "✓ Gemma 4 就緒 · $label$smartTag"
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text  = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}
