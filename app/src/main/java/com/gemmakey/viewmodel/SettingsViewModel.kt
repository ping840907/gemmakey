package com.gemmakey.viewmodel

import androidx.lifecycle.ViewModel
import com.gemmakey.ai.AppSettings
import com.gemmakey.ai.BackendType
import com.gemmakey.ai.GeminiChatManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class SettingsUiState(
    val backendType: BackendType = BackendType.GEMMA_LOCAL,
    val geminiApiKey: String = "",
    val geminiModelName: String = "gemini-2.0-flash",
    val isSaved: Boolean = false
)

val GEMINI_MODELS = listOf(
    "gemini-2.0-flash",
    "gemini-2.0-flash-lite",
    "gemini-1.5-flash",
    "gemini-1.5-flash-8b",
    "gemini-1.5-pro",
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appSettings: AppSettings,
    private val geminiChatManager: GeminiChatManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            backendType    = appSettings.backendType,
            geminiApiKey   = appSettings.geminiApiKey,
            geminiModelName = appSettings.geminiModelName
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun setBackendType(type: BackendType) {
        _uiState.update { it.copy(backendType = type, isSaved = false) }
    }

    fun setApiKey(key: String) {
        _uiState.update { it.copy(geminiApiKey = key, isSaved = false) }
    }

    fun setModelName(model: String) {
        _uiState.update { it.copy(geminiModelName = model, isSaved = false) }
    }

    fun save() {
        val s = _uiState.value
        appSettings.backendType    = s.backendType
        appSettings.geminiApiKey   = s.geminiApiKey
        appSettings.geminiModelName = s.geminiModelName
        _uiState.update { it.copy(isSaved = true) }
    }
}
