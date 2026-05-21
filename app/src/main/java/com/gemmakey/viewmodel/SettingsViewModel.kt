package com.gemmakey.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gemmakey.ai.AppSettings
import com.gemmakey.ai.BackendMode
import com.gemmakey.ai.GemmaInferenceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

// Fallback list shown when the API key is absent or the fetch fails
val GEMINI_MODELS = listOf(
    "gemini-2.0-flash",
    "gemini-2.0-flash-lite",
    "gemini-1.5-flash",
    "gemini-1.5-flash-8b",
    "gemini-1.5-pro",
)

data class SettingsUiState(
    val backendMode: BackendMode = BackendMode.GEMMA_ONLY,
    val geminiApiKey: String = "",
    val geminiModelName: String = "gemini-2.0-flash",
    val isGemmaInstalled: Boolean = false,
    val availableModels: List<String> = GEMINI_MODELS,
    val modelsLoading: Boolean = false,
    val isSaved: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appSettings: AppSettings,
    private val gemma: GemmaInferenceManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            backendMode      = appSettings.backendMode,
            geminiApiKey     = appSettings.geminiApiKey,
            geminiModelName  = appSettings.geminiModelName,
            isGemmaInstalled = gemma.isModelInstalled()
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var modelFetchJob: Job? = null

    init {
        // If a key is already saved, fetch models on open
        val savedKey = appSettings.geminiApiKey
        if (savedKey.isNotBlank()) scheduleFetch(savedKey, debounceMs = 0)
    }

    fun setBackendMode(mode: BackendMode) {
        _uiState.update { it.copy(backendMode = mode, isSaved = false) }
    }

    fun setApiKey(key: String) {
        _uiState.update { it.copy(geminiApiKey = key, isSaved = false) }
        scheduleFetch(key, debounceMs = 800)
    }

    fun setModelName(model: String) {
        _uiState.update { it.copy(geminiModelName = model, isSaved = false) }
    }

    fun save() {
        val s = _uiState.value
        appSettings.backendMode     = s.backendMode
        appSettings.geminiApiKey    = s.geminiApiKey
        appSettings.geminiModelName = s.geminiModelName
        _uiState.update { it.copy(isSaved = true) }
    }

    // ── Model fetching ────────────────────────────────────────────────────────

    private fun scheduleFetch(apiKey: String, debounceMs: Long) {
        modelFetchJob?.cancel()
        if (apiKey.length < 10) {
            _uiState.update { it.copy(availableModels = GEMINI_MODELS, modelsLoading = false) }
            return
        }
        modelFetchJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            _uiState.update { it.copy(modelsLoading = true) }
            val models = withContext(Dispatchers.IO) { fetchGeminiModels(apiKey) }
            _uiState.update { state ->
                val current = state.geminiModelName
                val list = models ?: GEMINI_MODELS
                state.copy(
                    availableModels = list,
                    modelsLoading   = false,
                    geminiModelName = if (current in list) current else list.first()
                )
            }
        }
    }

    /** Returns sorted list of generateContent-capable Gemini models, or null on failure. */
    private fun fetchGeminiModels(apiKey: String): List<String>? = runCatching {
        val conn = URL(
            "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
        ).openConnection() as HttpURLConnection
        conn.requestMethod  = "GET"
        conn.connectTimeout = 10_000
        conn.readTimeout    = 10_000

        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            Log.w("SettingsViewModel", "fetchModels HTTP ${conn.responseCode}")
            conn.disconnect()
            return@runCatching null
        }

        val json = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        val arr = JSONObject(json).getJSONArray("models")
        (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .filter { model ->
                val methods = model.optJSONArray("supportedGenerationMethods") ?: return@filter false
                val name    = model.optString("name", "")
                val hasGenerate = (0 until methods.length()).any { methods.getString(it) == "generateContent" }
                hasGenerate && name.contains("gemini") && !name.contains("embedding")
            }
            .map { it.optString("name").removePrefix("models/") }
            .filter { it.isNotBlank() }
            .sorted()
            .ifEmpty { null }
    }.onFailure { Log.w("SettingsViewModel", "fetchModels failed", it) }
     .getOrNull()
}
