package com.example.gemmakey

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.gemmakey.ai.AIEngineFactory
import java.io.File

/**
 * One-time setup screen that guides the user through:
 *   1. Enabling the accessibility service
 *   2. Selecting GemmaKey as the input method
 *   3. Verifying the Gemma model file is present
 */
class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val statusText = findViewById<TextView>(R.id.tv_setup_status)
        val btnA11y = findViewById<Button>(R.id.btn_enable_accessibility)
        val btnIme = findViewById<Button>(R.id.btn_select_ime)

        val a11yEnabled = GemmaAccessibilityService.instance != null
        val imeEnabled = isGemmaKeySelected()
        val modelPresent = isModelPresent()
        val aicoreAvail = AIEngineFactory.isAICoreAvailable(this)

        val sb = StringBuilder()
        sb.appendLine(if (a11yEnabled) "✓ Accessibility service active" else "✗ Accessibility service NOT enabled")
        sb.appendLine(if (imeEnabled) "✓ GemmaKey is selected as keyboard" else "✗ GemmaKey not selected as keyboard")
        sb.appendLine(if (aicoreAvail) "✓ AICore detected → Gemini Nano will be used" else "○ No AICore → LiteRT/Gemma will be used")
        sb.appendLine(if (modelPresent) "✓ Gemma model file found" else "✗ Gemma model missing — see instructions below")

        if (!modelPresent && !aicoreAvail) {
            sb.appendLine()
            sb.appendLine("Place gemma-model.bin in:")
            sb.appendLine("  ${File(getExternalFilesDir(null), "gemma-model.bin").absolutePath}")
            sb.appendLine("  OR  ${File(filesDir, "models/gemma-model.bin").absolutePath}")
            sb.appendLine()
            sb.appendLine("Rename your downloaded file to gemma-model.bin")
            sb.appendLine()
            sb.appendLine("Download sources (LiteRT / TFLite format):")
            sb.appendLine("  Kaggle:  kaggle.com/models/google/gemma-3")
            sb.appendLine("           (select Framework: LiteRT)")
            sb.appendLine("  Kaggle:  kaggle.com/models/google/gemma-2")
            sb.appendLine("           (select Framework: LiteRT)")
            sb.appendLine("  HuggingFace: search \"gemma-3-1b-it-litert\"")
            sb.appendLine()
            sb.appendLine("Recommended: gemma-3-1b-it-litert (~800 MB)")
        }

        statusText.text = sb.toString()

        btnA11y.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnIme.setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showInputMethodPicker()
        }
    }

    private fun isGemmaKeySelected(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return current?.contains(packageName) == true
    }

    private fun isModelPresent(): Boolean {
        val paths = listOf(
            File(getExternalFilesDir(null), "gemma-model.bin"),
            File(filesDir, "models/gemma-model.bin")
        )
        return paths.any { it.exists() }
    }
}
