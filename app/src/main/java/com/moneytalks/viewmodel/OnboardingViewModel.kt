package com.moneytalks.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneytalks.ai.AppSettings
import com.moneytalks.ai.BackendMode
import com.moneytalks.ai.GemmaInferenceManager
import com.moneytalks.ai.ModelDownloadManager
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

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val appSettings: AppSettings,
    private val gemma: GemmaInferenceManager,
    val modelDownload: ModelDownloadManager
) : ViewModel() {

    data class UiState(
        val page: Int = 0,
        val apiKey: String = "",
        val modelName: String = "",
        val availableModels: List<String> = GEMINI_MODELS,
        val modelsLoading: Boolean = false
    )

    private val _uiState = MutableStateFlow(
        UiState(
            apiKey    = appSettings.geminiApiKey,
            modelName = appSettings.geminiModelName
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var modelFetchJob: Job? = null

    val isModelInstalled: Boolean get() = gemma.isModelInstalled()

    init {
        viewModelScope.launch {
            modelDownload.state.collect { /* stay on page so user sees success state */ }
        }
        val savedKey = appSettings.geminiApiKey
        if (savedKey.isNotBlank()) scheduleFetch(savedKey, debounceMs = 0)
    }

    fun nextPage()  = _uiState.update { it.copy(page = it.page + 1) }
    fun prevPage()  = _uiState.update { it.copy(page = (it.page - 1).coerceAtLeast(0)) }

    fun setApiKey(key: String) {
        _uiState.update { it.copy(apiKey = key) }
        scheduleFetch(key, debounceMs = 800)
    }

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
                val current = state.modelName
                val list = models ?: GEMINI_MODELS
                state.copy(
                    availableModels = list,
                    modelsLoading   = false,
                    modelName       = if (current in list) current else list.first()
                )
            }
        }
    }

    private fun fetchGeminiModels(apiKey: String): List<String>? = runCatching {
        val conn = URL(
            "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200&key=$apiKey"
        ).openConnection() as HttpURLConnection
        conn.requestMethod  = "GET"
        conn.connectTimeout = 10_000
        conn.readTimeout    = 10_000

        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            Log.w("OnboardingViewModel", "fetchModels HTTP ${conn.responseCode}")
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
    }.onFailure { Log.w("OnboardingViewModel", "fetchModels failed", it) }
     .getOrNull()
}
