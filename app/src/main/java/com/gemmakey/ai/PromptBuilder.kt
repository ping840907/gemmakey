package com.gemmakey.ai

import com.gemmakey.model.ExpenseCategory
import com.gemmakey.model.ExpenseEntry
import com.gemmakey.model.ExpenseType
import com.gemmakey.model.ParsedExpense
import org.json.JSONArray
import org.json.JSONObject
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

// ── Gemma 4 指令格式 ──────────────────────────────────────────────────────────
private const val BOS         = "<bos>"
private const val USER_START  = "<start_of_turn>user\n"
private const val USER_END    = "<end_of_turn>\n"
private const val MODEL_START = "<start_of_turn>model\n"
private const val MODEL_END   = "<end_of_turn>\n"

// ── Gemma 4 Function Calling 特殊 token ──────────────────────────────────────
// 訓練內建於所有 Gemma 4 IT 模型，無需 prompt engineering
private const val TOOL_DEF_START    = "<tool>"
private const val TOOL_DEF_END      = "</tool>"
private const val TOOL_CALL_START   = "<tool_call>"
private const val TOOL_CALL_END     = "</tool_call>"
private const val TOOL_RESULT_START = "<tool_response>"
private const val TOOL_RESULT_END   = "</tool_response>"
private const val STR_DELIM         = "<|\"|>"  // 字串值分隔符

// ── 工具定義（JSON Schema）────────────────────────────────────────────────────

/**
 * 定義給 Gemma 4 的工具列表。
 * record_expense：記錄支出或收入，觸發 App 的確認 dialog 預填。
 * query_history ：查詢歷史記帳，由 RAG 處理，無需 function calling。
 */
val TOOL_DEFINITIONS: String = buildString {
    append(TOOL_DEF_START)
    val schema = JSONObject().apply {
        put("name", "record_expense")
        put("description", "記錄一筆支出或收入到記帳系統，並預填確認表格讓用戶核對")
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("amount",      JSONObject().apply {
                    put("type", "number")
                    put("description", "金額，正數，不含貨幣符號")
                })
                put("type",        JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray().apply { put("EXPENSE"); put("INCOME") })
                    put("description", "EXPENSE=支出，INCOME=收入")
                })
                put("category",    JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray().apply {
                        ExpenseCategory.entries.forEach { put(it.name) }
                    })
                    put("description", "費用類別")
                })
                put("description", JSONObject().apply {
                    put("type", "string")
                    put("description", "簡短說明，10 字以內")
                })
            })
            put("required", JSONArray().apply {
                put("amount"); put("type"); put("category"); put("description")
            })
        })
    }
    append(schema.toString(2))
    append(TOOL_DEF_END)
}

@Singleton
class PromptBuilder @Inject constructor() {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")

    // ── 系統指令（含工具定義注入）────────────────────────────────────────────

    private val systemPrompt = """
你是 GemmaKey 智慧記帳助理。

可用工具：
$TOOL_DEFINITIONS

規則：
1. 當用戶描述任何消費或收入時，必須呼叫 record_expense 工具。
2. 當用戶詢問歷史記帳問題時，根據提供的資料庫記錄直接回答，不呼叫工具。
3. 所有回答使用繁體中文。
""".trimIndent()

    // ── 建立對話 Prompt ────────────────────────────────────────────────────────

    fun buildChatPrompt(
        userMessage: String,
        ragContext: String = "",
        history: List<Pair<String, String>> = emptyList()
    ): String {
        val contextBlock = if (ragContext.isNotBlank())
            "\n【近期記帳資料】\n$ragContext\n" else ""
        return buildTurn(
            userMessage = "$systemPrompt$contextBlock\n$userMessage",
            priorTurns = history
        )
    }

    fun buildImagePrompt(userInstruction: String = ""): String {
        val instruction = userInstruction.ifBlank {
            "請分析這張圖片（收據或帳單），識別消費資訊後呼叫 record_expense 工具。"
        }
        return buildTurn(
            userMessage = "$systemPrompt\n$instruction",
            priorTurns = emptyList()
        )
    }

    // ── 工具結果回注（讓模型繼續對話）────────────────────────────────────────

    fun buildToolResultPrompt(
        originalPrompt: String,
        toolCallRaw: String,
        resultMessage: String
    ): String = buildString {
        append(originalPrompt)
        // 附上模型產生的 tool_call
        append(toolCallRaw)
        append(MODEL_END)
        // 注入工具執行結果
        append(USER_START)
        append(TOOL_RESULT_START)
        append(resultMessage)
        append(TOOL_RESULT_END)
        append(USER_END)
        append(MODEL_START)
    }

    // ── 解析 Function Call 輸出 ────────────────────────────────────────────────

    /**
     * 從模型輸出中提取 <tool_call> 區塊並解析為 ParsedExpense。
     * 若無工具呼叫則回傳 null（代表模型是在回答問題，非記帳）。
     */
    fun tryParseFunctionCall(modelOutput: String): ParsedExpense? {
        val callBody = extractBetween(modelOutput, TOOL_CALL_START, TOOL_CALL_END)
            ?: return null

        return runCatching {
            // Gemma 4 tool_call 格式：函式名稱在 JSON 的 "name" 欄位
            // 有些版本直接是 JSON，有些包含函式名稱
            val json = JSONObject(
                if (callBody.trimStart().startsWith("{")) callBody
                else extractBetween(callBody, "{", "}")?.let { "{$it}" } ?: callBody
            )

            // 支援兩種格式：
            // 格式 A: {"name":"record_expense","arguments":{...}}
            // 格式 B: {"amount":150,"type":"EXPENSE",...}
            val args: JSONObject = if (json.has("arguments"))
                json.getJSONObject("arguments")
            else
                json

            val funcName = json.optString("name", "record_expense")
            if (funcName != "record_expense") return null

            ParsedExpense(
                amount = args.optDouble("amount").takeIf { !it.isNaN() && it > 0 },
                type = when (args.optString("type").uppercase()) {
                    "INCOME" -> ExpenseType.INCOME
                    else     -> ExpenseType.EXPENSE
                },
                category = ExpenseCategory.fromString(args.optString("category", "OTHER")),
                description = args.optString("description", ""),
                confidence = 1.0f
            )
        }.getOrNull()
    }

    /** 相容舊版 JSON 輸出（model 未觸發 tool_call 但仍輸出 JSON 記帳資訊） */
    fun tryParseJsonFallback(modelOutput: String): ParsedExpense? {
        val jsonStr = extractBetween(modelOutput, "{", "}") ?: return null
        return runCatching {
            val json = JSONObject("{$jsonStr}")
            if (json.optString("action") != "record") return null
            ParsedExpense(
                amount = json.optDouble("amount").takeIf { !it.isNaN() },
                type = if (json.optString("type") == "INCOME") ExpenseType.INCOME else ExpenseType.EXPENSE,
                category = ExpenseCategory.fromString(json.optString("category", "OTHER")),
                description = json.optString("description", "")
            )
        }.getOrNull()
    }

    // ── RAG 上下文格式化 ──────────────────────────────────────────────────────

    fun formatRAGContext(entries: List<ExpenseEntry>): String {
        if (entries.isEmpty()) return ""
        return entries.joinToString("\n") { e ->
            val sign = if (e.type == ExpenseType.INCOME) "+" else "-"
            "${e.date.format(dateFormatter)} ${e.category.emoji}${e.category.displayName} $sign${e.amount.toLong()} ${e.description}"
        }
    }

    // ── 工具呼叫提取（供 ChatViewModel 判斷是否暫停等待確認）─────────────────

    fun containsToolCall(output: String): Boolean =
        TOOL_CALL_START in output

    fun extractToolCallRaw(output: String): String? =
        if (TOOL_CALL_START in output)
            output.substringFrom(TOOL_CALL_START)
        else null

    // ── 內部工具 ──────────────────────────────────────────────────────────────

    private fun extractBetween(text: String, start: String, end: String): String? {
        val s = text.indexOf(start).takeIf { it >= 0 }?.plus(start.length) ?: return null
        val e = text.indexOf(end, s).takeIf { it >= 0 } ?: text.length
        return text.substring(s, e).trim()
    }

    private fun String.substringFrom(marker: String): String =
        substring(indexOf(marker))

    private fun buildTurn(
        userMessage: String,
        priorTurns: List<Pair<String, String>>
    ): String = buildString {
        append(BOS)
        priorTurns.forEach { (user, assistant) ->
            append(USER_START); append(user); append(USER_END)
            append(MODEL_START); append(assistant); append(MODEL_END)
        }
        append(USER_START); append(userMessage); append(USER_END)
        append(MODEL_START)
    }
}
