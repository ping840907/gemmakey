package com.gemmakey.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gemmakey.ai.AppSettings
import com.gemmakey.ai.BackendMode
import com.gemmakey.ai.BackendType
import com.gemmakey.ai.ConversationTurn
import com.gemmakey.ai.ExpenseToolSet
import com.gemmakey.ai.GeminiChatManager
import com.gemmakey.ai.GemmaInferenceManager
import com.gemmakey.ai.InferenceState
import com.gemmakey.ai.NetworkMonitor
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

private const val MAX_HISTORY_TURNS    = 10  // max turns kept for cross-backend sharing
private const val MAX_HISTORY_TO_GEMMA = 5   // turns injected as text prefix into Gemma

private val WELCOME_MESSAGE = ChatMessage(
    role = MessageRole.ASSISTANT,
    text = "你好！我是 GemmaKey 記帳助理 ✨\n\n你可以：\n• 直接說「午餐花了 150 元」讓我記錄\n• 拍下收據或帳單讓我辨識\n• 問我「這個月花了多少」等問題"
)

data class ChatUiState(
    val messages: List<ChatMessage> = listOf(WELCOME_MESSAGE),
    val isGenerating: Boolean = false,
    val inferenceState: InferenceState = InferenceState(),
    val backendType: BackendType = BackendType.GEMMA_LOCAL,
    val backendMode: BackendMode = BackendMode.GEMMA_ONLY,
    val isOnline: Boolean = false,
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
    private val appSettings: AppSettings,
    private val networkMonitor: NetworkMonitor
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // Gemma per-session state
    private val toolSet = ExpenseToolSet()
    private var conversation: Conversation? = null

    // Cross-backend conversation history (raw text, no RAG prefixes)
    private val conversationHistory = mutableListOf<ConversationTurn>()

    // History to inject into Gemma on the next message after a backend switch
    private var pendingHistoryForGemma: List<ConversationTurn> = emptyList()

    init {
        viewModelScope.launch { initModel() }
        viewModelScope.launch { observeNetwork() }
    }

    // ── Network observation ───────────────────────────────────────────────────

    private suspend fun observeNetwork() {
        networkMonitor.isOnline.collect { online ->
            _uiState.update { it.copy(isOnline = online) }
            if (appSettings.backendMode == BackendMode.SMART) {
                handleSmartSwitch(online)
            }
        }
    }

    private fun handleSmartSwitch(online: Boolean) {
        val target = if (online) BackendType.GEMINI_API else BackendType.GEMMA_LOCAL
        if (_uiState.value.backendType == target) return

        if (_uiState.value.isGenerating) {
            // Defer: will be checked at the end of the current generation
            return
        }
        viewModelScope.launch { performSwitch(target, notify = true) }
    }

    // ── Initialisation ────────────────────────────────────────────────────────

    private suspend fun initModel() {
        val mode   = appSettings.backendMode
        val online = networkMonitor.isOnline.value
        val target = resolveActiveBackend(mode, online)

        _uiState.update { it.copy(
            inferenceState = InferenceState(isLoading = true),
            backendType    = target,
            backendMode    = mode,
            isOnline       = online
        ) }

        var activeTarget = target
        var state = when (target) {
            BackendType.GEMMA_LOCAL -> initGemma()
            BackendType.GEMINI_API  -> initGemini()
        }

        // Primary backend failed on startup → try the other one so the app remains usable
        var fallbackMsg: String? = null
        if (!state.isReady) {
            when (target) {
                BackendType.GEMINI_API -> {
                    // Gemini failed → fall back to Gemma
                    val gemmaState = initGemma()
                    if (gemmaState.isReady) {
                        activeTarget = BackendType.GEMMA_LOCAL
                        state = gemmaState
                        fallbackMsg = "⚠️ Gemini 無法使用，已自動切換至本機 Gemma"
                    }
                }
                BackendType.GEMMA_LOCAL -> {
                    // Gemma failed (model not installed etc.) → fall back to Gemini if key is set
                    if (appSettings.geminiApiKey.isNotBlank()) {
                        val geminiState = initGemini()
                        if (geminiState.isReady) {
                            activeTarget = BackendType.GEMINI_API
                            state = geminiState
                            fallbackMsg = "⚠️ Gemma 模型未就緒，已自動切換至 Gemini API"
                        }
                    }
                }
            }
        }

        _uiState.update { it.copy(inferenceState = state, backendType = activeTarget, backendMode = mode) }
        when {
            fallbackMsg != null                   -> appendAssistantMessage(fallbackMsg)
            !state.isReady && state.error != null -> appendAssistantMessage("⚠️ ${state.error}")
        }
    }

    private suspend fun initGemma(): InferenceState {
        val s = gemma.initialize()
        if (s.isReady) conversation = withContext(Dispatchers.IO) { createNewConversation() }
        return s
    }

    private fun initGemini(): InferenceState =
        gemini.initialize(appSettings.geminiApiKey, appSettings.geminiModelName)

    // ── Explicit reinitialise (called from Settings after save) ───────────────

    fun reinitialize() {
        if (_uiState.value.isGenerating) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                conversation?.close(); conversation = null
                gemma.close()
                gemini.close()
            }
            toolSet.clearLastCall()
            conversationHistory.clear()
            pendingHistoryForGemma = emptyList()
            _uiState.update {
                it.copy(messages = listOf(WELCOME_MESSAGE), pendingExpense = null, pendingRawInput = "")
            }
            initModel()
        }
    }

    // ── Smart backend switching ───────────────────────────────────────────────

    private suspend fun performSwitch(target: BackendType, notify: Boolean) {
        val currentBackend = _uiState.value.backendType
        if (currentBackend == target) return

        when (target) {
            BackendType.GEMINI_API -> {
                // Ensure Gemini is initialised (may already be from a previous switch)
                if (!gemini.isReady()) {
                    _uiState.update { it.copy(inferenceState = InferenceState(isLoading = true)) }
                    val state = withContext(Dispatchers.IO) { initGemini() }
                    if (!state.isReady) {
                        _uiState.update { it.copy(inferenceState = state) }
                        return
                    }
                }
                // Rebuild Gemini chat with shared history so it continues the same conversation
                withContext(Dispatchers.IO) { gemini.rebuildSession(conversationHistory) }
                _uiState.update { it.copy(backendType = BackendType.GEMINI_API, inferenceState = gemini.state) }
                if (notify) appendAssistantMessage("🌐 已連線，切換回 Gemini API 繼續對話")
            }

            BackendType.GEMMA_LOCAL -> {
                // Ensure Gemma is initialised
                if (!gemma.state.isReady) {
                    _uiState.update { it.copy(inferenceState = InferenceState(isLoading = true)) }
                    val state = withContext(Dispatchers.IO) { initGemma() }
                    if (!state.isReady) {
                        _uiState.update { it.copy(inferenceState = state) }
                        return
                    }
                } else if (conversation == null) {
                    conversation = withContext(Dispatchers.IO) { createNewConversation() }
                }
                // Schedule history injection for the next Gemma message
                if (conversationHistory.isNotEmpty()) {
                    pendingHistoryForGemma = conversationHistory.takeLast(MAX_HISTORY_TO_GEMMA)
                }
                _uiState.update { it.copy(backendType = BackendType.GEMMA_LOCAL, inferenceState = gemma.state) }
                if (notify) appendAssistantMessage("📱 網路離線，切換至本機 Gemma 繼續對話")
            }
        }
    }

    // ── Gemma conversation helpers ────────────────────────────────────────────

    private suspend fun createNewConversation(): Conversation? =
        runCatching {
            @Suppress("UNCHECKED_CAST")
            gemma.createConversation(
                systemInstruction = promptBuilder.buildGemmaSystemInstruction(),
                tools = listOf(toolSet as Any as ToolProvider)
            )
        }.recoverCatching {
            gemma.createConversation(systemInstruction = promptBuilder.buildGemmaSystemInstruction())
        }.getOrNull()

    // ── Clear conversation ────────────────────────────────────────────────────

    fun clearConversation() {
        if (_uiState.value.isGenerating) return
        toolSet.clearLastCall()
        gemini.clearToolCall()
        conversationHistory.clear()
        pendingHistoryForGemma = emptyList()
        _uiState.update {
            it.copy(messages = listOf(WELCOME_MESSAGE), pendingExpense = null, pendingRawInput = "")
        }
        viewModelScope.launch {
            when (_uiState.value.backendType) {
                BackendType.GEMMA_LOCAL -> {
                    conversation?.close(); conversation = null
                    if (gemma.state.isReady) {
                        conversation = withContext(Dispatchers.IO) { createNewConversation() }
                    }
                }
                BackendType.GEMINI_API -> gemini.startNewSession()
            }
        }
    }

    // ── User input ────────────────────────────────────────────────────────────

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

    // ── Core inference ────────────────────────────────────────────────────────

    private fun processInput(userText: String, bitmap: Bitmap?, rawInput: String) {
        val state = _uiState.value
        if (!state.inferenceState.isReady) {
            appendAssistantMessage(
                if (state.inferenceState.isLoading) "⏳ 模型載入中，請稍候…"
                else "⚠️ 推論引擎尚未就緒"
            )
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true) }
            val loadingId = System.currentTimeMillis()
            appendLoadingMessage(loadingId)

            try {
                val ragContext = ragManager.buildContext(userText)

                // Consume pending history injection (only once after a backend switch to Gemma)
                val history = pendingHistoryForGemma
                pendingHistoryForGemma = emptyList()

                val messageText = promptBuilder.buildUserMessage(userText, ragContext, history)

                val assistantReply = when (_uiState.value.backendType) {
                    BackendType.GEMMA_LOCAL -> processWithGemma(messageText, bitmap, loadingId, rawInput)
                    BackendType.GEMINI_API  -> {
                        try {
                            processWithGemini(messageText, bitmap, loadingId, rawInput)
                        } catch (e: Exception) {
                            if (appSettings.backendMode == BackendMode.SMART) {
                                // Gemini unavailable (API error, billing limit, etc.) → fall back to Gemma
                                updateLoadingMessage(loadingId, "⚠️ Gemini 無法使用，切換至本機 Gemma…")
                                performSwitch(BackendType.GEMMA_LOCAL, notify = false)
                                if (_uiState.value.inferenceState.isReady) {
                                    val fallbackHistory = pendingHistoryForGemma
                                    pendingHistoryForGemma = emptyList()
                                    val fallbackMsg = promptBuilder.buildUserMessage(userText, ragContext, fallbackHistory)
                                    processWithGemma(fallbackMsg, bitmap, loadingId, rawInput)
                                } else {
                                    finaliseMessage(loadingId, "❌ Gemini 無法使用，且 Gemma 也無法啟動")
                                    null
                                }
                            } else {
                                throw e
                            }
                        }
                    }
                }

                // Record the exchange in shared history (raw text only)
                if (assistantReply != null) {
                    conversationHistory += ConversationTurn(userText, assistantReply)
                    if (conversationHistory.size > MAX_HISTORY_TURNS) {
                        conversationHistory.removeAt(0)
                    }
                }
            } catch (e: Exception) {
                finaliseMessage(loadingId, "❌ 發生錯誤：${e.message}")
            } finally {
                _uiState.update { it.copy(isGenerating = false) }
                // Check if a smart switch was deferred during generation
                if (appSettings.backendMode == BackendMode.SMART) {
                    val online = networkMonitor.isOnline.value
                    val target = if (online) BackendType.GEMINI_API else BackendType.GEMMA_LOCAL
                    if (_uiState.value.backendType != target) {
                        performSwitch(target, notify = true)
                    }
                }
            }
        }
    }

    // Returns the final assistant text (for history), or null if it was a tool call preview
    private suspend fun processWithGemma(
        messageText: String, bitmap: Bitmap?, loadingId: Long, rawInput: String
    ): String? {
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

        if (toolSet.lastCall == null) toolSet.parseFromToolCallText(responseBuilder.toString())

        val toolResult = toolSet.lastCall
        return if (toolResult != null) {
            finaliseMessage(loadingId, buildPreviewText(toolResult))
            _uiState.update { it.copy(pendingExpense = toolResult, pendingRawInput = rawInput) }
            toolSet.clearLastCall()
            null // tool call: no text to add to history yet
        } else {
            val reply = responseBuilder.toString().trim().ifBlank { "（無回應）" }
            finaliseMessage(loadingId, reply)
            reply
        }
    }

    private suspend fun processWithGemini(
        messageText: String, bitmap: Bitmap?, loadingId: Long, rawInput: String
    ): String? {
        gemini.clearToolCall()
        val responseBuilder = StringBuilder()

        gemini.generateStream(messageText, bitmap).collect { chunk ->
            responseBuilder.append(chunk)
            updateLoadingMessage(loadingId, responseBuilder.toString())
        }

        val toolResult = gemini.lastToolCall
        return if (toolResult != null) {
            finaliseMessage(loadingId, buildPreviewText(toolResult))
            _uiState.update { it.copy(pendingExpense = toolResult, pendingRawInput = rawInput) }
            gemini.clearToolCall()
            null
        } else {
            val reply = responseBuilder.toString().trim().ifBlank { "（無回應）" }
            finaliseMessage(loadingId, reply)
            reply
        }
    }

    private fun buildPreviewText(p: ParsedExpense): String {
        val sign   = if (p.type.name == "INCOME") "收入 +" else "支出 -"
        val amount = p.amount?.let { "NT\$ ${it.toLong()}" } ?: "金額未識別"
        return "📊 偵測到記帳內容：\n${p.category.emoji} ${p.category.displayName}｜$sign$amount\n${p.description}\n\n👆 請在下方確認"
    }

    // ── Confirmation dialog callbacks ─────────────────────────────────────────

    fun confirmSave(entry: ExpenseEntry) {
        viewModelScope.launch {
            val savedId = repository.save(entry)
            _uiState.update { it.copy(pendingExpense = null, pendingRawInput = "") }

            val confirmMsg = withContext(Dispatchers.IO) {
                when (_uiState.value.backendType) {
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
            // Add the save confirmation to history
            conversationHistory += ConversationTurn(
                userText      = "（確認記帳）${entry.category.displayName} NT\$${entry.amount.toLong()}",
                assistantText = confirmMsg
            )
            if (conversationHistory.size > MAX_HISTORY_TURNS) conversationHistory.removeAt(0)
        }
    }

    fun dismissConfirmation() {
        _uiState.update { it.copy(pendingExpense = null, pendingRawInput = "") }
        if (_uiState.value.backendType == BackendType.GEMINI_API) {
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

    // ── Message list helpers ──────────────────────────────────────────────────

    private fun appendUserMessage(text: String, bitmap: Bitmap? = null) {
        _uiState.update { s ->
            s.copy(messages = s.messages + ChatMessage(role = MessageRole.USER, text = text, imageBitmap = bitmap))
        }
    }

    private fun appendAssistantMessage(text: String) {
        _uiState.update { s ->
            s.copy(messages = s.messages + ChatMessage(role = MessageRole.ASSISTANT, text = text))
        }
    }

    private fun appendLoadingMessage(id: Long) {
        _uiState.update { s ->
            s.copy(messages = s.messages + ChatMessage(id = id, role = MessageRole.ASSISTANT, text = "", isLoading = true))
        }
    }

    private fun updateLoadingMessage(id: Long, text: String) {
        _uiState.update { s ->
            s.copy(messages = s.messages.map { m -> if (m.id == id) m.copy(text = text) else m })
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

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun resolveActiveBackend(mode: BackendMode, online: Boolean): BackendType = when (mode) {
        BackendMode.GEMMA_ONLY  -> BackendType.GEMMA_LOCAL
        BackendMode.GEMINI_ONLY -> BackendType.GEMINI_API
        BackendMode.SMART       -> if (online) BackendType.GEMINI_API else BackendType.GEMMA_LOCAL
    }
}
