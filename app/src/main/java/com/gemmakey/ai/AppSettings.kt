package com.gemmakey.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User-visible backend preference.
 * SMART       = auto-switch based on connectivity (Gemini when online, Gemma when offline)
 * GEMMA_ONLY  = always run on-device; never contacts cloud (privacy mode)
 * GEMINI_ONLY = always use Gemini API; no automatic offline fallback
 */
enum class BackendMode { SMART, GEMMA_ONLY, GEMINI_ONLY }

/** Currently-active inference backend (derived at runtime from BackendMode + connectivity). */
enum class BackendType { GEMMA_LOCAL, GEMINI_API }

@Singleton
class AppSettings @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("moneytalks_settings", Context.MODE_PRIVATE)

    // ── BackendMode (user preference) ────────────────────────────────────────

    private val _backendMode = MutableStateFlow(loadBackendMode())
    val backendModeFlow: StateFlow<BackendMode> = _backendMode.asStateFlow()

    var backendMode: BackendMode
        get() = _backendMode.value
        set(value) {
            prefs.edit().putString("backend_mode", value.name).apply()
            _backendMode.value = value
        }

    // ── Gemini credentials ───────────────────────────────────────────────────

    var geminiApiKey: String
        get() = prefs.getString("gemini_api_key", "") ?: ""
        set(value) { prefs.edit().putString("gemini_api_key", value).apply() }

    var geminiModelName: String
        get() = prefs.getString("gemini_model", "gemini-2.0-flash") ?: "gemini-2.0-flash"
        set(value) { prefs.edit().putString("gemini_model", value).apply() }

    // ── Onboarding ───────────────────────────────────────────────────────────

    var onboardingCompleted: Boolean
        get() = prefs.getBoolean("onboarding_completed", false)
        set(value) { prefs.edit().putBoolean("onboarding_completed", value).apply() }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun loadBackendMode(): BackendMode {
        // Migrate old "backend_type" key to new "backend_mode"
        val legacy = prefs.getString("backend_type", null)
        if (legacy != null && !prefs.contains("backend_mode")) {
            val migrated = if (legacy == "GEMINI_API") BackendMode.GEMINI_ONLY else BackendMode.GEMMA_ONLY
            prefs.edit().putString("backend_mode", migrated.name).remove("backend_type").apply()
            return migrated
        }
        return try {
            BackendMode.valueOf(prefs.getString("backend_mode", BackendMode.GEMMA_ONLY.name)!!)
        } catch (_: Exception) {
            BackendMode.GEMMA_ONLY
        }
    }
}
