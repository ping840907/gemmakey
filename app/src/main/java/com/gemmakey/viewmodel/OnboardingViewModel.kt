package com.gemmakey.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gemmakey.ai.AppSettings
import com.gemmakey.ai.BackendMode
import com.gemmakey.ai.GemmaInferenceManager
import com.gemmakey.ai.ModelDownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val appSettings: AppSettings,
    private val gemma: GemmaInferenceManager,
    val modelDownload: ModelDownloadManager
) : ViewModel() {

    data class UiState(
        val page: Int = 0,
        val apiKey: String = ""
    )

    private val _uiState = MutableStateFlow(UiState(apiKey = appSettings.geminiApiKey))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val isModelInstalled: Boolean get() = gemma.isModelInstalled()

    init {
        // When download finishes, automatically advance if user is still on model page
        viewModelScope.launch {
            modelDownload.state.collect { state ->
                if (state is ModelDownloadManager.DownloadState.Done &&
                    _uiState.value.page == 1) {
                    // Stay on page so user sees the success state before proceeding
                }
            }
        }
    }

    fun nextPage() = _uiState.update { it.copy(page = it.page + 1) }
    fun prevPage() = _uiState.update { it.copy(page = (it.page - 1).coerceAtLeast(0)) }

    fun setApiKey(key: String) = _uiState.update { it.copy(apiKey = key) }

    fun complete() {
        val key = _uiState.value.apiKey.trim()
        if (key.isNotBlank()) appSettings.geminiApiKey = key

        val hasModel = gemma.isModelInstalled()
        val hasKey   = appSettings.geminiApiKey.isNotBlank()
        appSettings.backendMode = when {
            hasModel && hasKey -> BackendMode.SMART
            hasKey             -> BackendMode.GEMINI_ONLY
            else               -> BackendMode.GEMMA_ONLY
        }
        appSettings.onboardingCompleted = true
    }
}
