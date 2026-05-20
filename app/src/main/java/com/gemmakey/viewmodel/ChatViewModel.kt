package com.gemmakey.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gemmakey.ai.ExpenseToolSet
import com.gemmakey.ai.GemmaInferenceManager
import com.gemmakey.ai.InferenceState
import com.gemmakey.ai.PromptBuilder
import com.gemmakey.ai.RAGManager
import com.gemmakey.data.repository.ExpenseRepository
import com.gemmakey.model.ChatMessage
import com.gemmakey.model.ExpenseEntry
import com.gemmakey.model.MessageRole
import com.gemmakey.model.ParsedExpense
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ToolProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // 每次對話的工具集，持有最新 function call 結果
    private val toolSet = ExpenseToolSet()
    // 帶工具的持久 Conversation（跨多輪使用，節省 KV cache 重建成本）
    private var conversation: Conversation? = null

    init {
        viewModelScope.launch { initModel() }
    }

    // ── Model 初始化 ──────────────────────────────────────────────────────────

    private suspend fun initModel() {
        _uiState.update { it.copy(inferenceState = InferenceState(isLoading = true)) }
        val state = gemma.initialize()
        _uiState.update { it.copy(inferenceState = state) }

        if (state.isReady) {
            // 建立帶有 systemInstruction 和 record_expense 工具的對話。
            // ToolSet 在 LiteRT-LM 0.11.0 與 ToolProvider 是不同型別；KSP 可能在執行期
            // 產生橋接實作，因此透過 Any 轉型並在失敗時 fallback 到空工具清單。
            conversation = withContext(Dispatchers.IO) {
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    val toolProviders = listOf(toolSet as Any as ToolProvider)
                    gemma.createConversation(
                        systemInstruction = promptBuilder.systemInstruction,
                        tools = toolProviders
                    )
                }.recoverCatching {
                    // ToolSet 無法轉型時退回無工具模式；LLM 仍可用文字回應
                    gemma.createConversation(systemInstruction = promptBuilder.systemInstruction)
                }.getOrNull()
            }
        }
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
        appendUserMessage(text = userHint.ifBlank { "📷 分析圖片記帳…" }, bitmap = bitmap)
        processInput(
            userText = promptBuilder.buildImageInstruction(userHint),
            bitmap = bitmap,
            rawInput = userHint.ifBlank { "圖片輸入" }
        )
    }

    // ── 核心推論流程 ──────────────────────────────────────────────────────────

    private fun processInput(userText: String, bitmap: Bitmap?, rawInput: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true) }
            val loadingId = System.currentTimeMillis()
            appendLoadingMessage(loadingId)

            try {
                val ragContext = ragManager.buildContext(userText)
                val messageText = promptBuilder.buildUserMessage(userText, ragContext)
                toolSet.clearLastCall()

                val responseBuilder = StringBuilder()
                val conv = conversation

                val stream = if (bitmap != null)
                    gemma.generateStreamWithImage(messageText, bitmap, conv)
                else
                    gemma.generateStream(messageText, conv)

                stream.collect { token ->
                    responseBuilder.append(token)
                    updateLoadingMessage(loadingId, responseBuilder.toString())
                }

                // 推論完成後，先嘗試 LiteRT-LM 原生 tool calling；
                // 若未觸發（ToolSet/ToolProvider 型別不符時的 fallback），改以文字解析
                if (toolSet.lastCall == null) {
                    toolSet.parseFromToolCallText(responseBuilder.toString())
                }
                val toolCallResult = toolSet.lastCall
                if (toolCallResult != null) {
                    val previewText = buildPreviewText(toolCallResult)
                    finaliseMessage(loadingId, previewText)
                    _uiState.update {
                        it.copy(pendingExpense = toolCallResult, pendingRawInput = rawInput)
                    }
                    toolSet.clearLastCall()
                } else {
                    finaliseMessage(loadingId, responseBuilder.toString().trim().ifBlank { "（無回應）" })
                }

            } catch (e: Exception) {
                finaliseMessage(loadingId, "❌ 發生錯誤：${e.message}")
            } finally {
                _uiState.update { it.copy(isGenerating = false) }
            }
        }
    }

    private fun buildPreviewText(p: ParsedExpense): String {
        val sign   = if (p.type.name == "INCOME") "收入 +" else "支出 -"
        val amount = p.amount?.let { "NT\$ ${it.toLong()}" } ?: "金額未識別"
        return "📊 偵測到記帳內容：\n${p.category.emoji} ${p.category.displayName}｜$sign$amount\n${p.description}\n\n👆 請在下方確認"
    }

    // ── 確認 Dialog 回呼 ──────────────────────────────────────────────────────

    fun confirmSave(entry: ExpenseEntry) {
        viewModelScope.launch {
            val savedId = repository.save(entry)
            _uiState.update { it.copy(pendingExpense = null, pendingRawInput = "") }

            // 讓模型輸出確認訊息（不影響主 conversation 的 KV cache）
            val confirmMsg = withContext(Dispatchers.IO) {
                runCatching {
                    gemma.generate(
                        "工具執行成功：已儲存「${entry.category.displayName} NT\$${entry.amount.toLong()}」(id=$savedId)。請用一句話確認。",
                        conversation
                    )
                }.getOrDefault("✅ 已儲存：${entry.category.emoji} ${entry.description} NT\$${entry.amount.toLong()}")
            }
            appendAssistantMessage(confirmMsg)
        }
    }

    fun dismissConfirmation() {
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

    override fun onCleared() {
        super.onCleared()
        conversation?.close()
        gemma.close()
    }
}
