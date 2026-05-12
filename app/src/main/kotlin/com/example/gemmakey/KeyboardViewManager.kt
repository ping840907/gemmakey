package com.example.gemmakey

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.RequiresApi

/**
 * Inflates and manages the keyboard UI.
 *
 * The layout is intentionally minimal — this is a voice-first keyboard.
 * The mic button is the primary interaction target.
 */
class KeyboardViewManager(
    private val context: Context,
    private val listener: KeyboardActionListener
) {

    interface KeyboardActionListener {
        fun onMicDown()
        fun onMicUp()
        fun onDeletePressed()
        fun onEnterPressed()
    }

    enum class State { IDLE, RECORDING, PROCESSING, ERROR }

    private lateinit var rootView: View
    private lateinit var micButton: ImageButton
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var previewText: TextView
    private lateinit var deleteButton: ImageButton
    private lateinit var enterButton: ImageButton

    @SuppressLint("InflateParams")
    fun createView(editorInfo: EditorInfo?): View {
        rootView = LayoutInflater.from(context)
            .inflate(R.layout.keyboard_layout, null)

        micButton = rootView.findViewById(R.id.btn_mic)
        statusText = rootView.findViewById(R.id.tv_status)
        progressBar = rootView.findViewById(R.id.progress_bar)
        previewText = rootView.findViewById(R.id.tv_preview)
        deleteButton = rootView.findViewById(R.id.btn_delete)
        enterButton = rootView.findViewById(R.id.btn_enter)

        @SuppressLint("ClickableViewAccessibility")
        micButton.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> { listener.onMicDown(); true }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> { listener.onMicUp(); true }
                else -> false
            }
        }

        deleteButton.setOnClickListener { listener.onDeletePressed() }
        enterButton.setOnClickListener { listener.onEnterPressed() }

        return rootView
    }

    fun setState(state: State, message: String? = null) {
        rootView.post {
            when (state) {
                State.IDLE -> {
                    micButton.isEnabled = true
                    micButton.setColorFilter(Color.WHITE)
                    progressBar.visibility = View.INVISIBLE
                    statusText.text = message ?: context.getString(R.string.status_idle)
                }
                State.RECORDING -> {
                    micButton.isEnabled = true
                    micButton.setColorFilter(Color.RED)
                    progressBar.visibility = View.INVISIBLE
                    statusText.text = context.getString(R.string.status_recording)
                }
                State.PROCESSING -> {
                    micButton.isEnabled = false
                    micButton.setColorFilter(Color.GRAY)
                    progressBar.visibility = View.VISIBLE
                    statusText.text = message ?: context.getString(R.string.status_processing)
                }
                State.ERROR -> {
                    micButton.isEnabled = true
                    micButton.setColorFilter(Color.YELLOW)
                    progressBar.visibility = View.INVISIBLE
                    statusText.text = message ?: context.getString(R.string.status_error)
                }
            }
        }
    }

    fun showPreview(text: String) {
        rootView.post { previewText.text = text }
    }

    fun clearPreview() {
        rootView.post { previewText.text = "" }
    }
}
