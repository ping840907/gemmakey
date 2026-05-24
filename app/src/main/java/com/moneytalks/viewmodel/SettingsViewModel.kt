package com.moneytalks.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneytalks.ai.AppSettings
import com.moneytalks.ai.BackendMode
import com.moneytalks.ai.GemmaInferenceManager
import com.moneytalks.ai.ModelDownloadManager
import com.moneytalks.data.repository.ExpenseRepository
import com.moneytalks.model.ExpenseCategory
import com.moneytalks.model.ExpenseEntry
import com.moneytalks.model.ExpenseType
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import javax.inject.Inject

// Fallback list shown when the API key is absent or the fetch fails.
// Enter your API key in Settings to auto-fetch the current live model list.
val GEMINI_MODELS = listOf(
    "gemini-2.5-flash-preview-05-20",
    "gemini-2.5-pro-preview-05-06",
    "gemini-2.0-flash",
    "gemini-2.0-flash-lite",
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

data class ImportStagedData(
    val entries: List<ExpenseEntry>,
    val existingCount: Int
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appSettings: AppSettings,
    private val gemma: GemmaInferenceManager,
    val modelDownload: ModelDownloadManager,
    private val repository: ExpenseRepository
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
        val savedKey = appSettings.geminiApiKey
        if (savedKey.isNotBlank()) scheduleFetch(savedKey, debounceMs = 0)

        viewModelScope.launch {
            modelDownload.state.collect { state ->
                if (state is ModelDownloadManager.DownloadState.Done) {
                    _uiState.update { it.copy(isGemmaInstalled = gemma.isModelInstalled()) }
                }
            }
        }
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

    // ── Backup / Restore ─────────────────────────────────────────────────────

    suspend fun exportToJson(): String = withContext(Dispatchers.IO) {
        val entries = repository.getAllEntries()
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("amount",      e.amount)
                put("type",        e.type.name)
                put("category",    e.category.name)
                put("description", e.description)
                put("date",        e.date.toString())
                put("rawInput",    e.rawInput)
            })
        }
        JSONObject().apply {
            put("version",    1)
            put("exportedAt", LocalDate.now().toString())
            put("count",      entries.size)
            put("records",    arr)
        }.toString(2)
    }

    suspend fun prepareImport(json: String): ImportStagedData = withContext(Dispatchers.IO) {
        val obj     = JSONObject(json)
        val records = obj.getJSONArray("records")
        val entries = (0 until records.length()).map { i ->
            val r = records.getJSONObject(i)
            ExpenseEntry(
                id          = 0,
                amount      = r.getDouble("amount"),
                type        = ExpenseType.valueOf(r.getString("type")),
                category    = ExpenseCategory.fromString(r.getString("category")),
                description = r.getString("description"),
                date        = LocalDate.parse(r.getString("date")),
                rawInput    = r.optString("rawInput", "")
            )
        }
        ImportStagedData(entries, repository.count())
    }

    // Returns Pair(inserted, skipped). For overwrite, skipped is always 0.
    suspend fun commitImport(data: ImportStagedData, merge: Boolean): Pair<Int, Int> =
        withContext(Dispatchers.IO) {
            if (!merge) {
                repository.replaceAll(data.entries)
                data.entries.size to 0
            } else {
                // Count-based dedup: two entries with identical fields consume one "slot" each,
                // so duplicate legitimate records (e.g. two lunches on the same day) are preserved.
                val existingCounts = mutableMapOf<String, Int>()
                repository.getAllEntries().forEach { e ->
                    val key = "${e.date}|${e.category.name}|${e.amount}|${e.description}"
                    existingCounts[key] = (existingCounts[key] ?: 0) + 1
                }
                val toInsert = mutableListOf<ExpenseEntry>()
                data.entries.forEach { e ->
                    val key       = "${e.date}|${e.category.name}|${e.amount}|${e.description}"
                    val remaining = existingCounts[key] ?: 0
                    if (remaining > 0) {
                        existingCounts[key] = remaining - 1   // consume one matching slot
                    } else {
                        toInsert.add(e)
                    }
                }
                repository.insertAll(toInsert)
                toInsert.size to (data.entries.size - toInsert.size)
            }
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
            "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200&key=$apiKey"
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
