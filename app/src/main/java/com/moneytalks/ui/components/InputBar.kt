package com.moneytalks.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.moneytalks.audio.AudioRecorder
import com.moneytalks.utils.ImageUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun InputBar(
    isGenerating: Boolean,
    onSendText: (String) -> Unit,
    onAudioRecorded: (ByteArray) -> Unit,
    onImageSelected: (Uri) -> Unit
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()

    var text by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var showImageOptions by remember { mutableStateOf(false) }
    var amplitude by remember { mutableStateOf(0f) }

    val audioRecorder = remember { AudioRecorder() }
    val recordingJob = remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            recordingJob.value?.cancel()
            audioRecorder.stopAndGetWav()
        }
    }

    fun stopRecording() {
        recordingJob.value?.cancel()
        recordingJob.value = null
        val wav = audioRecorder.stopAndGetWav()
        amplitude = 0f
        isListening = false
        wav?.let { onAudioRecorded(it) }
    }

    fun startRecording() {
        isListening = true
        recordingJob.value = coroutineScope.launch {
            audioRecorder.start { amp -> amplitude = amp }
        }
    }

    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val audioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startRecording() }

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

    val micScale by animateFloatAsState(
        targetValue = if (isListening) 1f + amplitude * 0.25f else 1f,
        label = "micScale"
    )

    Column {
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
                // Voice button — scales with mic amplitude while recording
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .scale(micScale)
                        .clip(CircleShape)
                        .background(
                            if (isListening) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.primaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = {
                        if (isListening) stopRecording()
                        else {
                            val hasPerm = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasPerm) startRecording()
                            else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }) {
                        Icon(
                            if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (isListening) "停止錄音" else "語音輸入",
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
