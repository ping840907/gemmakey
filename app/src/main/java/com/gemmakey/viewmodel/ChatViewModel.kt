package com.gemmakey.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gemmakey.ai.AppSettings
import com.gemmakey.ai.BackendType
import com.gemmakey.ai.ExpenseToolSet
import com.gemmakey.ai.GeminiChatManager
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

private val WELCOME_MESSAGE = ChatMessage(
    role = MessageRole.ASSISTANT,
    text = "你好！我是 GemmaKey 記帳助理 ✨\n\n你可以：\n• 直接說「午餐花了 150 元」讓我記錄\n• 拍下收據或帳單讓我辨識\n• 問我「這個月花了多少」等問題"
)

data class ChatUiState(
    val messages: List<ChatMessage> = listOf(WELCOME_MESSAGE),
    val isGenerating: Boolean = false,
    val inferenceState: InferenceState = InferenceState(),
    val backendType: BackendType = BackendType.GEMMA_LOCAL,
    val pendingExpense: ParsedExpense? = null,
    val pendingRawInput: String = ""
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val gemma: GemmaInferenceManager,
    private val gemini: GeminiChatManager,
    private val promptBuilder: PromptBuilder,
    private val ragManager: RAGManager,
    private val repository: ExpenseRepository,
    private val appSettings: AppSettings
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val toolSet = ExpenseToolSet()
    private var conversation: Conversation? = null

    init {
        viewModelScope.launch { initModel() }
    }

    // ── 初始化 ────────────────────────────────────────────────────────────────

    private suspend fun initModel() {
        val backend = appSettings.backendType
        _uiState.update { it.copy(
            inferenceState = InferenceState(isLoading = true),
            backendType = backend
        ) }

        val state = when (backend) {
            BackendType.GEMMA_LOCAL -> {
                val s = gemma.initialize()
                if (s.isReady) {
                    conversation = withContext(Dispatchers.IO) { createNewConversation() }
                }
                s
            }
            BackendType.GEMINI_API -> gemini.initialize(
                apiKey    = appSettings.geminiApiKey,
                modelName = appSettings.geminiModelName
            )
        }

        _uiState.update { it.copy(inferenceState = state, backendType = backend) }
        if (state.error != null) appendAssistantMessage("⚠️ ${state.error}")
    }

    fun reinitialize() {
        if (_uiState.value.isGenerating) return
        viewModelScope.launch {
            // Fully tear down both backends before switching — prevents engine leaks
            // when the user switches between GEMMA_LOCAL and GEMINI_API.
            withContext(Dispatchers.IO) {
                conversation?.close()
                conversation = null
                gemma.close()   // no-op if not initialized; must close to free ~2 GB RAM
                gemini.close()  // no-op if not initialized
            }
            toolSet.clearLastCall()
            _uiState.update {
                it.copy(messages = listOf(WELCOME_MESSAGE), pendingExpense = null, pendingRawInput = "")
            }
            initModel()
        }
    }

    // ── Gemma conversation helpers ────────────────────────────────────────────

    private suspend fun createNewConversation(): Conversation? =
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val toolProviders = listOf(toolSet as Any as ToolProvider)
            gemma.createConversation(systemInstruction = promptBuilder.systemInstruction, tools = toolProviders)
        }.recoverCatching {
            gemma.createConversation(systemInstruction = promptBuilder.systemInstruction)
        }.getOrNull()

    // ── 清除對話 ──────────────────────────────────────────────────────────────

    fun clearConversation() {
        if (_uiState.value.isGenerating) return
        toolSet.clearLastCall()
        gemini.clearToolCall()
        _uiState.update {
            it.copy(messages = listOf(WELCOME_MESSAGE), pendingExpense = null, pendingRawInput = "")
        }
        viewModelScope.launch {
            when (appSettings.backendType) {
                BackendType.GEMMA_LOCAL -> {
                    conversation?.close()
                    conversation = null
                    if (gemma.state.isReady) {
                        conversation = withContext(Dispatchers.IO) { createNewConversation() }
                    }
                }
                BackendType.GEMINI_API -> gemini.startNewSession()
            }
        }
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
            bitmap   = bitmap,
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

                when (appSettings.backendType) {
                    BackendType.GEMMA_LOCAL -> processWithGemma(messageText, bitmap, loadingId, rawInput)
                    BackendType.GEMINI_API  -> processWithGemini(messageText, bitmap, loadingId, rawInput)
                }
            } catch (e: Exception) {
                finaliseMessage(loadingId, "❌ 發生錯誤：${e.message}")
            } finally {
                _uiState.update { it.copy(isGenerating = false) }
            }
        }
    }

    private suspend fun processWithGemma(
        messageText: String,
        bitmap: Bitmap?,
        loadingId: Long,
        rawInput: String
    ) {
        toolSet.clearLastCall()
        val responseBuilder = StringBuilder()
        val stream = if (bitmap != null)
            gemma.generateStreamWithImage(messageText, bitmap, conversation)
        else
            gemma.generateStream(messageText, conversation)

        stream.collect { token ->
            responseBuilder.append(token)
            updateLoadingMessage(loadingId, responseBuilder.toString())
        }

        if (toolSet.lastCall == null) {
            toolSet.parseFromToolCallText(responseBuilder.toString())
        }
        val toolResult = toolSet.lastCall
        if (toolResult != null) {
            finaliseMessage(loadingId, buildPreviewText(toolResult))
            _uiState.update { it.copy(pendingExpense = toolResult, pendingRawInput = rawInput) }
            toolSet.clearLastCall()
        } else {
            finaliseMessage(loadingId, responseBuilder.toString().trim().ifBlank { "（無回應）" })
        }
    }

    private suspend fun processWithGemini(
        messageText: String,
        bitmap: Bitmap?,
        loadingId: Long,
        rawInput: String
    ) {
        gemini.clearToolCall()
        val responseBuilder = StringBuilder()

        gemini.generateStream(messageText, bitmap).collect { chunk ->
            responseBuilder.append(chunk)
            updateLoadingMessage(loadingId, responseBuilder.toString())
        }

        val toolResult = gemini.lastToolCall
        if (toolResult != null) {
            finaliseMessage(loadingId, buildPreviewText(toolResult))
            _uiState.update { it.copy(pendingExpense = toolResult, pendingRawInput = rawInput) }
            gemini.clearToolCall()
        } else {
            finaliseMessage(loadingId, responseBuilder.toString().trim().ifBlank { "（無回應）" })
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

            val confirmMsg = withContext(Dispatchers.IO) {
                when (appSettings.backendType) {
                    BackendType.GEMMA_LOCAL -> runCatching {
                        gemma.generate(
                            "工具執行成功：已儲存「${entry.category.displayName} NT\$${entry.amount.toLong()}」(id=$savedId)。請用一句話確認。",
                            conversation
                        )
                    }.getOrDefault("✅ 已儲存：${entry.category.emoji} ${entry.description} NT\$${entry.amount.toLong()}")

                    BackendType.GEMINI_API -> {
                        val text = gemini.sendFunctionResponse(
                            "record_expense",
                            mapOf(
                                "status"  to "success",
                                "message" to "已儲存「${entry.category.displayName} NT\$${entry.amount.toLong()}」(id=$savedId)"
                            )
                        )
                        text.ifBlank { "✅ 已儲存：${entry.category.emoji} ${entry.description} NT\$${entry.amount.toLong()}" }
                    }
                }
            }
            appendAssistantMessage(confirmMsg)
        }
    }

    fun dismissConfirmation() {
        _uiState.update { it.copy(pendingExpense = null, pendingRawInput = "") }
        if (appSettings.backendType == BackendType.GEMINI_API) {
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        gemini.sendFunctionResponse(
                            "record_expense",
                            mapOf("status" to "cancelled", "message" to "用戶取消了記帳")
                        )
                    }
                }
            }
        }
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
        gemini.close()
    }
}
