package com.gemmakey.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class BackendType { GEMMA_LOCAL, GEMINI_API }

@Singleton
class AppSettings @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("gemmakey_settings", Context.MODE_PRIVATE)

    private val _backendType = MutableStateFlow(loadBackendType())
    val backendTypeFlow: StateFlow<BackendType> = _backendType.asStateFlow()

    var backendType: BackendType
        get() = _backendType.value
        set(value) {
            prefs.edit().putString("backend_type", value.name).apply()
            _backendType.value = value
        }

    var geminiApiKey: String
        get() = prefs.getString("gemini_api_key", "") ?: ""
        set(value) { prefs.edit().putString("gemini_api_key", value).apply() }

    var geminiModelName: String
        get() = prefs.getString("gemini_model", "gemini-2.0-flash") ?: "gemini-2.0-flash"
        set(value) { prefs.edit().putString("gemini_model", value).apply() }

    private fun loadBackendType(): BackendType =
        try {
            BackendType.valueOf(prefs.getString("backend_type", BackendType.GEMMA_LOCAL.name)!!)
        } catch (_: Exception) {
            BackendType.GEMMA_LOCAL
        }
}
