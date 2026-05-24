package com.gemmakey.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gemmakey.ai.ModelDownloadManager
import com.gemmakey.viewmodel.OnboardingViewModel
import kotlinx.coroutines.delay

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val uiState       by viewModel.uiState.collectAsState()
    val downloadState by viewModel.modelDownload.state.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = uiState.page,
                transitionSpec = {
                    if (targetState > initialState)
                        (slideInHorizontally { it } + fadeIn(tween(280))) togetherWith
                        (slideOutHorizontally { -it } + fadeOut(tween(220)))
                    else
                        (slideInHorizontally { -it } + fadeIn(tween(280))) togetherWith
                        (slideOutHorizontally { it } + fadeOut(tween(220)))
                },
                label = "onboarding_page",
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> WelcomePage(onNext = viewModel::nextPage)
                    1 -> LocalModelPage(
                        downloadState      = downloadState,
                        isAlreadyInstalled = viewModel.isModelInstalled,
                        onDownload         = viewModel.modelDownload::startDownload,
                        onCancel           = viewModel.modelDownload::cancelDownload,
                        onBack             = viewModel::prevPage,
                        onNext             = viewModel::nextPage
                    )
                    2 -> ApiKeyPage(
                        apiKey         = uiState.apiKey,
                        onApiKeyChange = viewModel::setApiKey,
                        onBack         = viewModel::prevPage,
                        onComplete     = { viewModel.complete(); onComplete() }
                    )
                    else -> LaunchedEffect(Unit) { viewModel.complete(); onComplete() }
                }
            }

            PageIndicator(
                total    = 3,
                current  = uiState.page,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 20.dp)
            )
        }
    }
}

// ── Page indicator ─────────────────────────────────────────────────────────────

@Composable
private fun PageIndicator(total: Int, current: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { index ->
            val isActive = index == current
            val width by animateDpAsState(
                targetValue    = if (isActive) 24.dp else 8.dp,
                animationSpec  = tween(300),
                label          = "dot_width_$index"
            )
            val color by animateColorAsState(
                targetValue   = if (isActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                animationSpec = tween(300),
                label         = "dot_color_$index"
            )
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(width)
                    .background(color = color, shape = RoundedCornerShape(4.dp))
            )
        }
    }
}

// ── Page 0: Welcome ────────────────────────────────────────────────────────────

private data class FeatureItem(val emoji: String, val title: String, val desc: String)

@Composable
private fun WelcomePage(onNext: () -> Unit) {
    val features = remember {
        listOf(
            FeatureItem("💬", "對話式記帳", "說出或輸入消費，AI 自動解析金額與分類"),
            FeatureItem("🔒", "隱私優先", "支援本地 AI，帳目資料不離開你的手機"),
            FeatureItem("📊", "智慧統計", "自動整理收支明細，圖表一眼看懂趨勢"),
        )
    }

    var revealStep by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        delay(400)
        while (revealStep < features.size) {
            revealStep++
            delay(120)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Logo
        Box(
            modifier = Modifier
                .size(100.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary
                        )
                    ),
                    shape = RoundedCornerShape(28.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text("談", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        Spacer(Modifier.height(20.dp))

        Text(
            "談錢",
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "MoneyTalks",
            style = MaterialTheme.typography.titleMedium.copy(
                letterSpacing = 2.sp,
                fontWeight    = FontWeight.Normal
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(10.dp))

        Text(
            "跟我談錢，不傷感情",
            style     = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            color     = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(36.dp))

        Column(
            modifier            = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            features.forEachIndexed { index, feature ->
                AnimatedVisibility(
                    visible = revealStep > index,
                    enter   = fadeIn(tween(300)) + slideInHorizontally(tween(300)) { -40 }
                ) {
                    FeatureRow(feature)
                }
            }
        }

        Spacer(Modifier.height(48.dp))

        Button(
            onClick  = onNext,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape    = RoundedCornerShape(16.dp)
        ) {
            Text("開始使用", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ArrowForward, contentDescription = null)
        }

        // Space for page indicator
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun FeatureRow(feature: FeatureItem) {
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(14.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(feature.emoji, fontSize = 22.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                feature.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                feature.desc,
                style      = MaterialTheme.typography.bodySmall,
                color      = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}

// ── Page 1: Local Model ────────────────────────────────────────────────────────

@Composable
private fun LocalModelPage(
    downloadState: ModelDownloadManager.DownloadState,
    isAlreadyInstalled: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp)
            .padding(top = 8.dp, bottom = 88.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
        }

        Spacer(Modifier.height(8.dp))

        OnboardingPageHeader(
            icon     = Icons.Default.PhoneAndroid,
            title    = "設置離線 AI",
            subtitle = "在你的裝置上執行 Gemma 4\n記帳完全離線，資料不離開手機"
        )

        Spacer(Modifier.height(24.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            listOf("🔒 完全離線", "🛡️ 保護隱私", "⚡ 快速回應").forEach { label ->
                SuggestionChip(
                    onClick = {},
                    label   = { Text(label, style = MaterialTheme.typography.bodySmall) }
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier            = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Gemma 4 E2B", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Google 開源模型 · 約 2 GB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                when {
                    isAlreadyInstalled || downloadState is ModelDownloadManager.DownloadState.Done -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint     = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "模型已安裝，可離線使用",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    downloadState is ModelDownloadManager.DownloadState.Downloading -> {
                        val dl = downloadState
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier              = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "下載中… ${dl.downloadedMb} / ${
                                        if (dl.totalMb > 0) "${dl.totalMb} MB" else "計算中"
                                    }",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "${(dl.progress * 100).toInt()}%",
                                    style      = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            LinearProgressIndicator(
                                progress = { dl.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    downloadState is ModelDownloadManager.DownloadState.Failed -> {
                        Text(
                            "⚠️ ${downloadState.error}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    else -> {}
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        val isDone        = isAlreadyInstalled || downloadState is ModelDownloadManager.DownloadState.Done
        val isDownloading = downloadState is ModelDownloadManager.DownloadState.Downloading

        if (!isDone) {
            Button(
                onClick  = if (isDownloading) onCancel else onDownload,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape    = RoundedCornerShape(14.dp),
                colors   = if (isDownloading)
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else
                    ButtonDefaults.buttonColors()
            ) {
                Icon(
                    if (isDownloading) Icons.Default.Cancel else Icons.Default.Download,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        isDownloading -> "取消下載"
                        downloadState is ModelDownloadManager.DownloadState.Failed -> "重新下載"
                        else -> "下載 Gemma 模型（約 2 GB）"
                    },
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        OutlinedButton(
            onClick  = onNext,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape    = RoundedCornerShape(14.dp)
        ) {
            Text(if (isDone) "下一步" else "跳過，稍後設定", style = MaterialTheme.typography.titleSmall)
            if (isDone) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Default.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ── Page 2: API Key ────────────────────────────────────────────────────────────

@Composable
private fun ApiKeyPage(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    onBack: () -> Unit,
    onComplete: () -> Unit
) {
    var showKey by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp)
            .padding(top = 8.dp, bottom = 88.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
        }

        Spacer(Modifier.height(8.dp))

        OnboardingPageHeader(
            icon     = Icons.Default.Cloud,
            title    = "選用：Gemini 雲端 AI",
            subtitle = "速度更快、理解力更強\n需要網路連線"
        )

        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value                = apiKey,
            onValueChange        = onApiKeyChange,
            label                = { Text("Gemini API Key") },
            placeholder          = { Text("AIza...") },
            modifier             = Modifier.fillMaxWidth(),
            singleLine           = true,
            visualTransformation = if (showKey) VisualTransformation.None
                                   else PasswordVisualTransformation(),
            keyboardOptions      = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon         = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null
                    )
                }
            }
        )

        Spacer(Modifier.height(10.dp))

        Row(
            modifier          = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint     = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "aistudio.google.com 可免費取得 API Key",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier          = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint     = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp).padding(top = 1.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "注意：Google 免費方案的對話內容可能被用於改善 AI 服務。如有隱私顧慮，建議使用離線的 Gemma 模型，或升級為付費方案。",
                    style      = MaterialTheme.typography.bodySmall,
                    color      = MaterialTheme.colorScheme.onErrorContainer,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick  = onComplete,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape    = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.Check, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("完成設定，開始使用", style = MaterialTheme.typography.titleSmall)
        }

        Spacer(Modifier.height(12.dp))

        TextButton(
            onClick  = onComplete,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("跳過", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── Shared header composable ───────────────────────────────────────────────────

@Composable
private fun OnboardingPageHeader(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(20.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint     = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            title,
            style     = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            subtitle,
            style      = MaterialTheme.typography.bodyLarge,
            textAlign  = TextAlign.Center,
            color      = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 24.sp
        )
    }
}
