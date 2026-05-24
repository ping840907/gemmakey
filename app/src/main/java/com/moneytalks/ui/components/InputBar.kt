package com.moneytalks.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.moneytalks.utils.ImageUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun InputBar(
    isGenerating: Boolean,
    onSendText: (String) -> Unit,
    onVoiceResult: (String) -> Unit,
    onImageSelected: (Uri) -> Unit
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()

    var text by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var showImageOptions by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf<String?>(null) }

    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val speechRecognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context))
            SpeechRecognizer.createSpeechRecognizer(context)
        else null
    }
    DisposableEffect(Unit) { onDispose { speechRecognizer?.destroy() } }

    val audioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            isListening = true
            startListening(
                recognizer = speechRecognizer,
                context = context,
                onResult = { result -> isListening = false; onVoiceResult(result) },
                onError = { msg ->
                    isListening = false
                    voiceError = msg
                    coroutineScope.launch { delay(3000); voiceError = null }
                }
            )
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success -> if (success) cameraUri?.let { onImageSelected(it) } }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) cameraUri?.let { cameraLauncher.launch(it) }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { onImageSelected(it) } }

    Column {
        // Voice error strip
        AnimatedVisibility(
            visible = voiceError != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    voiceError ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // Image option row (expandable)
        AnimatedVisibility(visible = showImageOptions) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ImageOptionChip(icon = Icons.Default.CameraAlt, label = "拍照") {
                    showImageOptions = false
                    val file = ImageUtils.createImageFile(context)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    cameraUri = uri
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED) {
                        cameraLauncher.launch(uri)
                    } else {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                }
                ImageOptionChip(icon = Icons.Default.Photo, label = "相簿") {
                    showImageOptions = false
                    galleryLauncher.launch("image/*")
                }
            }
        }

        // Main input row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = { showImageOptions = !showImageOptions }) {
                Icon(
                    Icons.Default.AddPhotoAlternate,
                    contentDescription = "附加圖片",
                    tint = if (showImageOptions) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("輸入消費或詢問…", style = MaterialTheme.typography.bodyMedium) },
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedContainerColor   = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedIndicatorColor   = Color.Transparent
                ),
                shape = RoundedCornerShape(24.dp),
                maxLines = 4
            )

            if (text.isBlank()) {
                // Voice button
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (isListening) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.primaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = {
                        if (isListening) {
                            speechRecognizer?.stopListening()
                            isListening = false
                        } else {
                            val hasPerm = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasPerm) {
                                isListening = true
                                startListening(
                                    recognizer = speechRecognizer,
                                    context = context,
                                    onResult = { result -> isListening = false; onVoiceResult(result) },
                                    onError = { msg ->
                                        isListening = false
                                        voiceError = msg
                                        coroutineScope.launch { delay(3000); voiceError = null }
                                    }
                                )
                            } else {
                                audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    }) {
                        Icon(
                            if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (isListening) "停止" else "語音輸入",
                            tint = if (isListening) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else {
                // Send button
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (isGenerating) MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.primary
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = {
                            if (!isGenerating) {
                                onSendText(text)
                                text = ""
                                keyboardController?.hide()
                            }
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "傳送",
                            tint = if (isGenerating) MaterialTheme.colorScheme.onSurfaceVariant
                                   else Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageOptionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) }
    )
}

private fun startListening(
    recognizer: SpeechRecognizer?,
    context: android.content.Context,
    onResult: (String) -> Unit,
    onError: (String) -> Unit = {}
) {
    recognizer ?: return
    val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
    recognizer.setRecognitionListener(object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            onResult(matches?.firstOrNull() ?: "")
        }
        override fun onError(error: Int) {
            val msg = when (error) {
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "網路錯誤，請檢查連線"
                SpeechRecognizer.ERROR_NO_MATCH        -> "未偵測到語音，請再試一次"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT  -> "語音逾時，請再試一次"
                SpeechRecognizer.ERROR_AUDIO           -> "麥克風錯誤"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "語音辨識忙碌中"
                else                                   -> "語音辨識失敗"
            }
            onError(msg)
        }
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    })
    recognizer.startListening(intent)
}
