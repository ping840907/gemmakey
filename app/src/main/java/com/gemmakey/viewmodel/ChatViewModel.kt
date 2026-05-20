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
            text = "你好！我是 GemmaKey 記帳助理 ✨\n\n你可以：\n• 直接說「午餐花了 150 元」讓我記錄\n• 拍下收據或帳單讓我辨識\n• 問我「這個月花了多少」等問題"
        )
    ),
    val isGenerating: Boolean = false,
    val inferenceState: InferenceState = InferenceState(),
    // 當模型發出 function call，暫停等待使用者在 dialog 確認
    val pendingExpense: ParsedExpense? = null,
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

    // 多輪對話歷史（最多 6 輪，避免 context 過長）
    private val history = ArrayDeque<Pair<String, String>>()
    // 暫存：待工具確認後需要回注結果的 prompt
    private var pendingToolPrompt: String? = null
    private var pendingToolCallRaw: String? = null

    init {
        viewModelScope.launch { initModel() }
    }

    // ── Model 初始化 ──────────────────────────────────────────────────────────

    private suspend fun initModel() {
        _uiState.update { it.copy(inferenceState = InferenceState(isLoading = true)) }
        val state = gemma.initialize()
        _uiState.update { it.copy(inferenceState = state) }
        if (state.error != null) appendAssistantMessage("⚠️ ${state.error}")
    }

    // ── 使用者輸入 ────────────────────────────────────────────────────────────

    fun sendTextMessage(text: String) {
        if (text.isBlank() || _uiState.value.isGenerating) return
        appendUserMessage(text)
        processInput(userText = text, bitmap = null, rawInput = text)
    }

    fun sendVoiceResult(transcribed: String) {
        if (transcribed.isBlank()) return
        appendUserMessage("🎤 $transcribed")
        processInput(userText = transcribed, bitmap = null, rawInput = transcribed)
    }

    fun sendImageMessage(bitmap: Bitmap, userHint: String = "") {
        appendUserMessage(
            text = userHint.ifBlank { "📷 分析圖片記帳…" },
            bitmap = bitmap
        )
        processInput(
            userText = promptBuilder.buildImagePrompt(userHint),
            bitmap = bitmap,
            rawInput = userHint.ifBlank { "圖片輸入" }
        )
    }

    // ── 核心處理流程 ──────────────────────────────────────────────────────────

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
                        history = history.toList()
                    )
                }

                val responseBuilder = StringBuilder()
                val flow = if (bitmap != null)
                    gemma.generateStreamWithImage(prompt, bitmap)
                else
                    gemma.generateStream(prompt)

                flow.collect { token ->
                    responseBuilder.append(token)
                    // 即時更新串流泡泡（隱藏 tool_call token，僅顯示前置文字）
                    val displayText = stripToolTokens(responseBuilder.toString())
                    if (displayText.isNotBlank()) updateLoadingMessage(loadingId, displayText)
                }

                handleModelOutput(
                    loadingId = loadingId,
                    fullOutput = responseBuilder.toString(),
                    prompt = prompt,
                    rawInput = rawInput
                )
                updateHistory(userText, responseBuilder.toString())

            } catch (e: Exception) {
                finaliseMessage(loadingId, "❌ 發生錯誤：${e.message}")
            } finally {
                _uiState.update { it.copy(isGenerating = false) }
            }
        }
    }

    // ── Function Call 解析與 Dialog 觸發 ─────────────────────────────────────

    private fun handleModelOutput(
        loadingId: Long,
        fullOutput: String,
        prompt: String,
        rawInput: String
    ) {
        // 優先嘗試 Gemma 4 原生 function call（<tool_call> token）
        val parsed = promptBuilder.tryParseFunctionCall(fullOutput)
            // 相容性 fallback：舊版 JSON 格式
            ?: promptBuilder.tryParseJsonFallback(fullOutput)

        if (parsed != null) {
            // 模型發出了 record_expense 工具呼叫 → 暫停，顯示確認 dialog
            pendingToolPrompt   = prompt
            pendingToolCallRaw  = promptBuilder.extractToolCallRaw(fullOutput)

            val previewText = buildPreviewText(parsed)
            finaliseMessage(loadingId, previewText)
            _uiState.update {
                it.copy(pendingExpense = parsed, pendingRawInput = rawInput)
            }
        } else {
            // 純文字回覆（回答問題，無記帳意圖）
            finaliseMessage(loadingId, fullOutput.trim().ifBlank { "（模型無輸出）" })
        }
    }

    private fun buildPreviewText(p: ParsedExpense): String {
        val sign   = if (p.type == com.gemmakey.model.ExpenseType.INCOME) "收入 +" else "支出 -"
        val amount = p.amount?.let { "NT\$ ${it.toLong()}" } ?: "金額未識別"
        return "📊 偵測到記帳內容：\n${p.category.emoji} ${p.category.displayName}｜$sign$amount\n${p.description}\n\n👆 請在下方確認"
    }

    // ── 確認 Dialog 回呼 ──────────────────────────────────────────────────────

    /**
     * 使用者在確認 dialog 按下「儲存」：
     * 1. 將記帳存入資料庫
     * 2. 把「工具執行成功」結果回注給模型，讓模型繼續對話回應
     */
    fun confirmSave(entry: ExpenseEntry) {
        viewModelScope.launch {
            val savedId = repository.save(entry)
            _uiState.update { it.copy(pendingExpense = null, pendingRawInput = "") }

            // 告知模型工具已執行，讓模型給出確認回覆
            val toolResult = """{"status":"success","id":$savedId,"message":"已記錄 ${entry.category.displayName} NT\$${entry.amount.toLong()}"}"""
            val continuationPrompt = pendingToolPrompt?.let { basePrompt ->
                promptBuilder.buildToolResultPrompt(
                    originalPrompt = basePrompt,
                    toolCallRaw    = pendingToolCallRaw ?: "",
                    resultMessage  = toolResult
                )
            }

            if (continuationPrompt != null) {
                // 讓模型給出確認訊息（不串流，一次性取得）
                val confirmMsg = gemma.generate(continuationPrompt)
                appendAssistantMessage(confirmMsg.ifBlank { "✅ 已儲存：${entry.category.emoji} ${entry.description} NT\$${entry.amount.toLong()}" })
            } else {
                appendAssistantMessage("✅ 已儲存：${entry.category.emoji} ${entry.description} NT\$${entry.amount.toLong()}")
            }

            pendingToolPrompt  = null
            pendingToolCallRaw = null
        }
    }

    fun dismissConfirmation() {
        pendingToolPrompt  = null
        pendingToolCallRaw = null
        _uiState.update { it.copy(pendingExpense = null, pendingRawInput = "") }
        appendAssistantMessage("已取消，如需重新記帳請再說一次。")
    }

    // ── 訊息管理 ──────────────────────────────────────────────────────────────

    private fun appendUserMessage(text: String, bitmap: Bitmap? = null) {
        _uiState.update { s ->
            s.copy(messages = s.messages + ChatMessage(
                role = MessageRole.USER, text = text, imageBitmap = bitmap
            ))
        }
    }

    private fun appendAssistantMessage(text: String) {
        _uiState.update { s ->
            s.copy(messages = s.messages + ChatMessage(
                role = MessageRole.ASSISTANT, text = text
            ))
        }
    }

    private fun appendLoadingMessage(id: Long) {
        _uiState.update { s ->
            s.copy(messages = s.messages + ChatMessage(
                id = id, role = MessageRole.ASSISTANT, text = "", isLoading = true
            ))
        }
    }

    private fun updateLoadingMessage(id: Long, text: String) {
        _uiState.update { s ->
            s.copy(messages = s.messages.map { m ->
                if (m.id == id) m.copy(text = text) else m
            })
        }
    }

    private fun finaliseMessage(id: Long, text: String) {
        _uiState.update { s ->
            s.copy(messages = s.messages.map { m ->
                if (m.id == id) m.copy(text = text, isLoading = false) else m
            })
        }
    }

    // 不讓使用者看到原始 <tool_call>...</tool_call> token
    private fun stripToolTokens(text: String): String {
        val idx = text.indexOf("<tool_call>")
        return if (idx >= 0) text.substring(0, idx).trim() else text
    }

    private fun updateHistory(user: String, assistant: String) {
        history.addLast(user to assistant)
        while (history.size > 6) history.removeFirst()
    }

    override fun onCleared() {
        super.onCleared()
        gemma.close()
    }
}
