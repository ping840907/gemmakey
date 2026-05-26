package com.moneytalks.ai

import android.graphics.Bitmap
import android.util.Log
import com.moneytalks.model.ExpenseCategory
import com.moneytalks.model.ExpenseType
import com.moneytalks.model.ParsedExpense
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.FunctionCallPart
import com.google.ai.client.generativeai.type.FunctionResponsePart
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.Schema
import com.google.ai.client.generativeai.type.Tool
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.defineFunction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GeminiChat"

@Singleton
class GeminiChatManager @Inject constructor(
    private val promptBuilder: PromptBuilder
) {
    private var model: GenerativeModel? = null
    private var chat: com.google.ai.client.generativeai.Chat? = null

    @Volatile var lastToolCall: ParsedExpense? = null
        private set

    var state: InferenceState = InferenceState()
        private set

    // ── 初始化（需要 API key）────────────────────────────────────────────────

    fun initialize(apiKey: String, modelName: String = "gemini-2.0-flash"): InferenceState {
        if (apiKey.isBlank()) {
            state = InferenceState(error = "請先在設定中輸入 Gemini API Key")
            return state
        }
        return try {
            val expenseTool = buildExpenseTool()
            val sysInstruction = content { text(promptBuilder.buildGeminiSystemInstruction()) }

            model = GenerativeModel(
                modelName = modelName,
                apiKey = apiKey,
                tools = listOf(expenseTool),
                systemInstruction = sysInstruction,
                safetySettings = listOf(
                    SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.ONLY_HIGH),
                    SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.ONLY_HIGH),
                    SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.ONLY_HIGH),
                    SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.ONLY_HIGH),
                )
            )
            startNewSession()
            // backend field is Gemma-specific hardware; for Gemini we leave it as default CPU
            state = InferenceState(isReady = true)
            Log.i(TAG, "Gemini initialized: $modelName")
            state
        } catch (e: Exception) {
            Log.e(TAG, "Gemini init failed", e)
            state = InferenceState(error = "Gemini 初始化失敗：${e.message}")
            state
        }
    }

    fun startNewSession() {
        chat = model?.startChat()
    }

    /**
     * Rebuild the chat session preserving prior conversation turns.
     * Called when switching from Gemma → Gemini so the cloud model
     * sees the full cross-backend dialogue history.
     */
    fun rebuildSession(history: List<ConversationTurn>) {
        val m = model ?: return
        if (history.isEmpty()) {
            chat = m.startChat()
            return
        }
        val contents = history.flatMap { turn ->
            listOf(
                content("user")  { text(turn.userText) },
                content("model") { text(turn.assistantText) }
            )
        }
        chat = m.startChat(history = contents)
        Log.d(TAG, "Session rebuilt with ${history.size} prior turn(s)")
    }

    fun isReady(): Boolean = model != null && state.isReady

    // ── 串流推論 ──────────────────────────────────────────────────────────────

    fun generateStream(prompt: String, bitmap: Bitmap? = null): Flow<String> = callbackFlow {
        val c = chat ?: run { trySend("[Gemini 未初始化]"); close(); return@callbackFlow }
        lastToolCall = null

        try {
            val inputContent = if (bitmap != null) {
                content("user") {
                    image(bitmap)
                    text(prompt)
                }
            } else {
                content("user") { text(prompt) }
            }

            val stream = c.sendMessageStream(inputContent)

            stream.collect { response ->
                response.text?.let { trySend(it) }

                // Native function call detection (fires when model invokes record_expense)
                response.candidates?.firstOrNull()?.content?.parts
                    ?.filterIsInstance<FunctionCallPart>()
                    ?.firstOrNull()
                    ?.let { parseFunctionCall(it) }
            }

            close()
        } catch (e: Throwable) {
            Log.e(TAG, "Stream error", e)
            val msg = if (e is OutOfMemoryError) "❌ 圖片過大，記憶體不足" else "❌ Gemini API 錯誤：${e.message}"
            trySend(msg)
            close(e)
        }
        awaitClose()
    }.flowOn(Dispatchers.IO)

    fun generateStreamWithAudio(wavBytes: ByteArray, prompt: String): Flow<String> = callbackFlow {
        val c = chat ?: run { trySend("[Gemini 未初始化]"); close(); return@callbackFlow }
        lastToolCall = null
        try {
            val inputContent = content("user") {
                blob("audio/wav", wavBytes)
                if (prompt.isNotBlank()) text(prompt)
            }
            val stream = c.sendMessageStream(inputContent)
            stream.collect { response ->
                response.text?.let { trySend(it) }
                response.candidates?.firstOrNull()?.content?.parts
                    ?.filterIsInstance<FunctionCallPart>()
                    ?.firstOrNull()
                    ?.let { parseFunctionCall(it) }
            }
            close()
        } catch (e: Throwable) {
            Log.e(TAG, "Audio stream error", e)
            trySend("❌ Gemini 語音 API 錯誤：${e.message}")
            close(e)
        }
        awaitClose()
    }.flowOn(Dispatchers.IO)

    // ── 單次推論（確認訊息用）────────────────────────────────────────────────

    suspend fun sendFunctionResponse(functionName: String, result: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val c = chat ?: return@withContext ""
            try {
                val jsonObj = JSONObject(result as Map<*, *>)
                val responseContent = content("function") {
                    part(FunctionResponsePart(functionName, jsonObj))
                }
                val response = c.sendMessage(responseContent)
                response.text?.trim() ?: ""
            } catch (e: Exception) {
                Log.e(TAG, "Function response error", e)
                ""
            }
        }

    fun clearToolCall() { lastToolCall = null }

    fun close() {
        chat = null
        model = null
        state = InferenceState()
    }

    // ── Function call 解析 ────────────────────────────────────────────────────

    private fun parseFunctionCall(funcCall: FunctionCallPart) {
        if (funcCall.name != "record_expense") return
        val args = funcCall.args ?: return

        fun argStr(key: String): String? = args[key]?.let {
            // args values may be JsonPrimitive, Number, String etc.
            it.toString().trim('"', ' ')
        }

        val amount = argStr("amount")?.toDoubleOrNull()?.takeIf { it > 0 } ?: return
        val typeRaw = argStr("type")?.uppercase() ?: ""
        val isIncome = typeRaw == "INCOME" || typeRaw.contains("收入")

        lastToolCall = ParsedExpense(
            amount      = amount,
            type        = if (isIncome) ExpenseType.INCOME else ExpenseType.EXPENSE,
            category    = ExpenseCategory.fromString(argStr("category") ?: ""),
            description = argStr("description")?.ifBlank { null } ?: argStr("category") ?: "",
            date        = parseDate(argStr("date") ?: "")
        )
        Log.d(TAG, "Tool call parsed: $lastToolCall")
    }

    // ── 工具定義 ──────────────────────────────────────────────────────────────

    private fun buildExpenseTool(): Tool {
        val fn = defineFunction(
            name        = "record_expense",
            description = "記錄一筆消費或收入到記帳系統。支出與收入均使用此工具，透過 type 欄位區分。",
            parameters  = listOf(
                Schema.double(name = "amount",      description = "金額，正數"),
                Schema.str(name    = "type",        description = "EXPENSE（支出）或 INCOME（收入）"),
                Schema.str(name    = "category",    description = "類別：FOOD/TRANSPORT/SHOPPING/ENTERTAINMENT/HEALTH/EDUCATION/UTILITIES/HOUSING/SALARY/BONUS/INVESTMENT/OTHER"),
                Schema.str(name    = "description", description = "10 字以內的簡短說明"),
                Schema.str(name    = "date",        description = "交易日期，格式 yyyy-MM-dd")
            ),
            requiredParameters = listOf("amount", "type", "category", "description", "date")
        )
        return Tool(listOf(fn))
    }

    // ── 日期解析 ──────────────────────────────────────────────────────────────

    private fun parseDate(raw: String): LocalDate {
        val s = raw.trim()
        val today = LocalDate.now()
        val fullFmts = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy年MM月dd日"),
            DateTimeFormatter.ofPattern("yyyy年M月d日"),
        )
        for (fmt in fullFmts) {
            try { return LocalDate.parse(s, fmt) } catch (_: DateTimeParseException) {}
        }
        return today
    }
}
