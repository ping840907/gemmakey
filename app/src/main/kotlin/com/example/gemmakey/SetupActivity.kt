package com.example.gemmakey

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.gemmakey.ai.AIEngineFactory
import com.example.gemmakey.ai.LiteRTEngine
import java.io.File

/**
 * One-time setup screen that guides the user through:
 *   1. Granting the RECORD_AUDIO runtime permission
 *   2. Enabling the accessibility service
 *   3. Selecting GemmaKey as the input method
 *   4. Verifying the Gemma model file is present
 */
class SetupActivity : AppCompatActivity() {

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                // User chose "Don't ask again" — open app settings so they can grant it manually.
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                })
            }
            refresh()
        }

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
        val btnMic = findViewById<Button>(R.id.btn_grant_mic)
        val btnA11y = findViewById<Button>(R.id.btn_enable_accessibility)
        val btnIme = findViewById<Button>(R.id.btn_select_ime)

        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val a11yEnabled = GemmaAccessibilityService.instance != null
        val imeEnabled = isGemmaKeySelected()
        val modelPresent = isModelPresent()
        val aicoreAvail = AIEngineFactory.isAICoreAvailable(this)

        val sb = StringBuilder()
        sb.appendLine(if (micGranted) "✓ Microphone permission granted" else "✗ Microphone permission NOT granted")
        sb.appendLine(if (a11yEnabled) "✓ Accessibility service active" else "✗ Accessibility service NOT enabled")
        sb.appendLine(if (imeEnabled) "✓ GemmaKey is selected as keyboard" else "✗ GemmaKey not selected as keyboard")
        sb.appendLine(if (aicoreAvail) "✓ AICore detected → Gemini Nano will be used" else "○ No AICore → LiteRT/Gemma will be used")
        sb.appendLine(if (modelPresent) "✓ Gemma model file found" else "✗ Gemma model missing — see instructions below")

        if (!modelPresent && !aicoreAvail) {
            val modelFile = LiteRTEngine.MODEL_FILENAME
            sb.appendLine()
            sb.appendLine("Place the model file here:")
            sb.appendLine("  ${File(getExternalFilesDir(null), modelFile).absolutePath}")
            sb.appendLine("  OR")
            sb.appendLine("  ${File(filesDir, "models/$modelFile").absolutePath}")
            sb.appendLine()
            sb.appendLine("Download (Hugging Face):")
            sb.appendLine("  huggingface.co/litert-community/gemma-4-E2B-it-litert-lm")
            sb.appendLine("  → download gemma-4-E2B-it-int4.litertlm (~1-2 GB)")
            sb.appendLine()
            sb.appendLine("The file must keep its original name:")
            sb.appendLine("  $modelFile")
        }

        statusText.text = sb.toString()

        btnMic.visibility = if (micGranted) View.GONE else View.VISIBLE
        btnMic.setOnClickListener {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
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
        val name = LiteRTEngine.MODEL_FILENAME
        return listOf(
            File(getExternalFilesDir(null), name),
            File(filesDir, "models/$name")
        ).any { it.exists() }
    }
}
