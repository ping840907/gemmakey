package com.moneytalks.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.moneytalks.ai.BackendMode
import com.moneytalks.ai.ModelDownloadManager
import com.moneytalks.viewmodel.ImportStagedData
import com.moneytalks.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

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
    val uiState       by viewModel.uiState.collectAsState()
    val downloadState by viewModel.modelDownload.state.collectAsState()
    var showApiKey         by remember { mutableStateOf(false) }
    var modelMenuExpanded  by remember { mutableStateOf(false) }

    val scope              = rememberCoroutineScope()
    val context            = LocalContext.current
    val snackbarHostState  = remember { SnackbarHostState() }

    // ── Export (SAF CreateDocument) ───────────────────────────────────────────
    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = pendingExportJson ?: return@rememberLauncherForActivityResult
        pendingExportJson = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)
                        ?.bufferedWriter()
                        ?.use { it.write(json) }
                }
            }.onSuccess {
                snackbarHostState.showSnackbar("備份成功")
            }.onFailure {
                snackbarHostState.showSnackbar("備份失敗：${it.message}")
            }
        }
    }

    // ── Import (SAF OpenDocument) ─────────────────────────────────────────────
    var importStaged by remember { mutableStateOf<ImportStagedData?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.readText()
                        ?: error("無法讀取檔案")
                }
                viewModel.prepareImport(json)
            }.onSuccess { staged ->
                if (staged.existingCount > 0) {
                    importStaged = staged
                } else {
                    val (inserted, _) = viewModel.commitImport(staged, merge = false)
                    snackbarHostState.showSnackbar("已導入 $inserted 筆記錄")
                }
            }.onFailure {
                snackbarHostState.showSnackbar("解析失敗：格式不正確")
            }
        }
    }

    // ── Conflict resolution dialog ────────────────────────────────────────────
    importStaged?.let { staged ->
        AlertDialog(
            onDismissRequest = { importStaged = null },
            title = { Text("選擇導入方式") },
            text  = {
                Text(
                    "目前已有 ${staged.existingCount} 筆記錄，備份包含 ${staged.entries.size} 筆。\n\n" +
                    "「合併」：保留現有記錄並加入備份\n" +
                    "「覆蓋」：清除現有記錄，僅保留備份"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val data = staged
                    importStaged = null
                    scope.launch {
                        runCatching { viewModel.commitImport(data, merge = true) }
                            .onSuccess { (inserted, skipped) ->
                                val msg = if (skipped > 0) "已新增 $inserted 筆，略過 $skipped 筆重複"
                                          else "已合併導入 $inserted 筆記錄"
                                snackbarHostState.showSnackbar(msg)
                            }
                            .onFailure { snackbarHostState.showSnackbar("導入失敗：${it.message}") }
                    }
                }) { Text("合併") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { importStaged = null }) {
                        Text("取消")
                    }
                    TextButton(
                        onClick = {
                            val data = staged
                            importStaged = null
                            scope.launch {
                                runCatching { viewModel.commitImport(data, merge = false) }
                                    .onSuccess { (inserted, _) -> snackbarHostState.showSnackbar("已覆蓋，導入 $inserted 筆記錄") }
                                    .onFailure { snackbarHostState.showSnackbar("導入失敗：${it.message}") }
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("覆蓋") }
                }
            }
        )
    }

    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) onSaved()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("推論後端", style = MaterialTheme.typography.titleMedium)

            // ── Backend mode selection ─────────────────────────────────────────
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

            // ── Local Gemma model ──────────────────────────────────────────────
            Text("本地 AI 模型", style = MaterialTheme.typography.titleMedium)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Gemma 4 E2B", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "離線運行 · 保護隱私 · 約 2 GB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (uiState.isGemmaInstalled) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "已安裝",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    when {
                        uiState.isGemmaInstalled && downloadState !is ModelDownloadManager.DownloadState.Downloading -> {
                            Text(
                                "✓ 模型已安裝，可離線使用",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        downloadState is ModelDownloadManager.DownloadState.Downloading -> {
                            val dl = downloadState as ModelDownloadManager.DownloadState.Downloading
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "${dl.downloadedMb} / ${if (dl.totalMb > 0) "${dl.totalMb} MB" else "計算中"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "${(dl.progress * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { dl.progress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        downloadState is ModelDownloadManager.DownloadState.Done -> {
                            Text(
                                "✓ 下載完成！請儲存設定並重新啟動",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        downloadState is ModelDownloadManager.DownloadState.Failed -> {
                            Text(
                                "⚠️ ${(downloadState as ModelDownloadManager.DownloadState.Failed).error}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> {}
                    }

                    val isDownloading = downloadState is ModelDownloadManager.DownloadState.Downloading
                    OutlinedButton(
                        onClick = if (isDownloading) viewModel.modelDownload::cancelDownload
                                  else viewModel.modelDownload::startDownload,
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(10.dp),
                        enabled  = !uiState.isGemmaInstalled || isDownloading
                    ) {
                        Icon(
                            if (isDownloading) Icons.Default.Cancel else Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                isDownloading            -> "取消下載"
                                uiState.isGemmaInstalled -> "已安裝"
                                downloadState is ModelDownloadManager.DownloadState.Failed -> "重新下載"
                                else                     -> "下載模型"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // ── Gemini settings ────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Gemini 設定", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value              = uiState.geminiApiKey,
                    onValueChange      = viewModel::setApiKey,
                    label              = { Text("API Key") },
                    placeholder        = { Text("AIza...") },
                    modifier           = Modifier.fillMaxWidth(),
                    singleLine         = true,
                    visualTransformation = if (showApiKey) VisualTransformation.None
                                          else PasswordVisualTransformation(),
                    keyboardOptions    = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon       = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                    }
                )

                ExposedDropdownMenuBox(
                    expanded          = modelMenuExpanded,
                    onExpandedChange  = { if (!uiState.modelsLoading) modelMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value         = uiState.geminiModelName,
                        onValueChange = {},
                        readOnly      = true,
                        label         = { Text("模型") },
                        trailingIcon  = {
                            if (uiState.modelsLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                ExposedDropdownMenuDefaults.TrailingIcon(modelMenuExpanded)
                            }
                        },
                        modifier      = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded          = modelMenuExpanded,
                        onDismissRequest  = { modelMenuExpanded = false }
                    ) {
                        uiState.availableModels.forEach { name ->
                            DropdownMenuItem(
                                text    = { Text(name) },
                                onClick = { viewModel.setModelName(name); modelMenuExpanded = false }
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

            // ── Backup / Restore ───────────────────────────────────────────────
            Text("備份與還原", style = MaterialTheme.typography.titleMedium)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier            = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint     = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "將記帳紀錄導出為 JSON 檔案，或從備份還原",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runCatching { viewModel.exportToJson() }
                                        .onSuccess { json ->
                                            pendingExportJson = json
                                            exportLauncher.launch(
                                                "moneytalks_backup_${LocalDate.now()}.json"
                                            )
                                        }
                                        .onFailure {
                                            snackbarHostState.showSnackbar("備份失敗：${it.message}")
                                        }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape    = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                Icons.Default.Backup,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("導出備份", style = MaterialTheme.typography.bodyMedium)
                        }

                        OutlinedButton(
                            onClick  = {
                                importLauncher.launch(
                                    arrayOf("application/json", "text/plain", "*/*")
                                )
                            },
                            modifier = Modifier.weight(1f),
                            shape    = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                Icons.Default.Restore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("導入還原", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick  = viewModel::save,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("儲存並套用")
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier  = Modifier.align(Alignment.BottomCenter)
        )
    }
}
