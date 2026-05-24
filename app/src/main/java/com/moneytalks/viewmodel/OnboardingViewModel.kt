package com.moneytalks.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneytalks.ai.AppSettings
import com.moneytalks.ai.BackendMode
import com.moneytalks.ai.GemmaInferenceManager
import com.moneytalks.ai.ModelDownloadManager
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
        val apiKey: String = "",
        val modelName: String = ""
    )

    private val _uiState = MutableStateFlow(
        UiState(
            apiKey    = appSettings.geminiApiKey,
            modelName = appSettings.geminiModelName
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val isModelInstalled: Boolean get() = gemma.isModelInstalled()

    init {
        viewModelScope.launch {
            modelDownload.state.collect { /* stay on page so user sees success state */ }
        }
    }

    fun nextPage()  = _uiState.update { it.copy(page = it.page + 1) }
    fun prevPage()  = _uiState.update { it.copy(page = (it.page - 1).coerceAtLeast(0)) }

    fun setApiKey(key: String)    = _uiState.update { it.copy(apiKey = key) }
    fun setModelName(name: String) = _uiState.update { it.copy(modelName = name) }

    fun complete() {
        val key       = _uiState.value.apiKey.trim()
        val modelName = _uiState.value.modelName.ifBlank { "gemini-2.0-flash" }
        if (key.isNotBlank()) appSettings.geminiApiKey = key
        appSettings.geminiModelName = modelName

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
