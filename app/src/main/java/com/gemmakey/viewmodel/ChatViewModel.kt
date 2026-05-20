package com.gemmakey.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gemmakey.ai.GemmaInferenceManager
import com.gemmakey.ai.InferenceState
import com.gemmakey.ai.PromptBuilder
import com.gemmakey.ai.RAGManager
import com.gemmakey.data.repository.ExpenseRepository
import com.gemmakey.model.ChatMessage
import com.gemmakey.model.ExpenseEntry
import com.gemmakey.model.MessageRole
import com.gemmakey.model.ParsedExpense
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class ChatUiState(
    val messages: List<ChatMessage> = listOf(
        ChatMessage(
            role = MessageRole.ASSISTANT,
            text = "你好！我是 GemmaKey 記帳助理 ✨\n\n你可以：\n• 直接說出消費，例如「午餐花了 150 元」\n• 拍下收據或帳單讓我辨識\n• 問我「這個月花了多少」等問題"
        )
    ),
    val isGenerating: Boolean = false,
    val inferenceState: InferenceState = InferenceState(),
    val pendingExpense: ParsedExpense? = null,   // triggers confirmation dialog
    val pendingRawInput: String = ""
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val gemma: GemmaInferenceManager,
    private val promptBuilder: PromptBuilder,
    private val ragManager: RAGManager,
    private val repository: ExpenseRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // Conversation history kept for multi-turn context (last 6 turns)
    private val conversationHistory = ArrayDeque<Pair<String, String>>()

    init {
        viewModelScope.launch { initModel() }
    }

    // ── Model initialisation ─────────────────────────────────────────────────

    private suspend fun initModel() {
        _uiState.update { it.copy(inferenceState = InferenceState(isLoading = true)) }
        val state = gemma.initialize()
        _uiState.update { it.copy(inferenceState = state) }
        if (state.error != null) {
            appendAssistantMessage("⚠️ ${state.error}")
        }
    }

    // ── Text input ───────────────────────────────────────────────────────────

    fun sendTextMessage(text: String) {
        if (text.isBlank() || _uiState.value.isGenerating) return
        appendUserMessage(text)
        processInput(userText = text, bitmap = null, rawInput = text)
    }

    // ── Voice input (already transcribed by SpeechRecognizer) ───────────────

    fun sendVoiceResult(transcribed: String) {
        if (transcribed.isBlank()) return
        appendUserMessage("🎤 $transcribed")
        processInput(userText = transcribed, bitmap = null, rawInput = transcribed)
    }

    // ── Image input ──────────────────────────────────────────────────────────

    fun sendImageMessage(bitmap: Bitmap, userHint: String = "") {
        appendUserMessage(
            text = if (userHint.isNotBlank()) userHint else "📷 分析圖片記帳…",
            bitmap = bitmap
        )
        processInput(
            userText = promptBuilder.buildImagePrompt(userHint),
            bitmap = bitmap,
            rawInput = userHint.ifBlank { "圖片輸入" }
        )
    }

    // ── Core processing ──────────────────────────────────────────────────────

    private fun processInput(userText: String, bitmap: Bitmap?, rawInput: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true) }
            val loadingId = System.currentTimeMillis()
            appendLoadingMessage(loadingId)

            try {
                val ragContext = ragManager.buildContext(userText)
                val prompt = if (bitmap != null) {
                    promptBuilder.buildImagePrompt(userText)
                } else {
                    promptBuilder.buildChatPrompt(
                        userMessage = userText,
                        ragContext = ragContext,
                        history = conversationHistory.toList()
                    )
                }

                val responseBuilder = StringBuilder()

                val flow = if (bitmap != null)
                    gemma.generateStreamWithImage(prompt, bitmap)
                else
                    gemma.generateStream(prompt)

                flow.collect { token ->
                    responseBuilder.append(token)
                    updateLoadingMessage(loadingId, responseBuilder.toString())
                }

                val fullResponse = responseBuilder.toString().trim()
                finaliseResponse(loadingId, fullResponse, rawInput)
                updateHistory(userText, fullResponse)

            } catch (e: Exception) {
                finaliseResponse(loadingId, "❌ 發生錯誤：${e.message}", rawInput)
            } finally {
                _uiState.update { it.copy(isGenerating = false) }
            }
        }
    }

    private fun finaliseResponse(loadingId: Long, response: String, rawInput: String) {
        val parsed = promptBuilder.tryParseExpense(response)
        val displayText = if (parsed != null) {
            buildConfirmationText(parsed)
        } else {
            response
        }

        _uiState.update { state ->
            val updated = state.messages.map { msg ->
                if (msg.id == loadingId) msg.copy(text = displayText, isLoading = false) else msg
            }
            state.copy(
                messages = updated,
                pendingExpense = parsed,
                pendingRawInput = if (parsed != null) rawInput else ""
            )
        }
    }

    private fun buildConfirmationText(p: ParsedExpense): String {
        val sign = if (p.type.name == "INCOME") "收入 +" else "支出 -"
        val amount = p.amount?.let { "NT\$ ${it.toLong()}" } ?: "金額未識別"
        return "📊 偵測到記帳內容：\n${p.category.emoji} ${p.category.displayName} | $sign$amount\n${p.description}\n\n請確認是否儲存 👆"
    }

    // ── Confirmation dialog callbacks ────────────────────────────────────────

    fun confirmSave(entry: ExpenseEntry) {
        viewModelScope.launch {
            repository.save(entry)
            _uiState.update { it.copy(pendingExpense = null, pendingRawInput = "") }
            appendAssistantMessage("✅ 已儲存：${entry.category.emoji} ${entry.description} NT\$${entry.amount.toLong()}")
        }
    }

    fun dismissConfirmation() {
        _uiState.update { it.copy(pendingExpense = null, pendingRawInput = "") }
    }

    // ── Message helpers ──────────────────────────────────────────────────────

    private fun appendUserMessage(text: String, bitmap: Bitmap? = null) {
        _uiState.update { state ->
            state.copy(messages = state.messages + ChatMessage(
                role = MessageRole.USER, text = text, imageBitmap = bitmap
            ))
        }
    }

    private fun appendAssistantMessage(text: String) {
        _uiState.update { state ->
            state.copy(messages = state.messages + ChatMessage(
                role = MessageRole.ASSISTANT, text = text
            ))
        }
    }

    private fun appendLoadingMessage(id: Long) {
        _uiState.update { state ->
            state.copy(messages = state.messages + ChatMessage(
                id = id, role = MessageRole.ASSISTANT, text = "", isLoading = true
            ))
        }
    }

    private fun updateLoadingMessage(id: Long, text: String) {
        _uiState.update { state ->
            state.copy(messages = state.messages.map { msg ->
                if (msg.id == id) msg.copy(text = text) else msg
            })
        }
    }

    private fun updateHistory(user: String, assistant: String) {
        conversationHistory.addLast(user to assistant)
        while (conversationHistory.size > 6) conversationHistory.removeFirst()
    }

    override fun onCleared() {
        super.onCleared()
        gemma.close()
    }
}
