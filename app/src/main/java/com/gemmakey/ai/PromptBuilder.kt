package com.gemmakey.ai

import com.gemmakey.model.ExpenseCategory
import com.gemmakey.model.ExpenseEntry
import com.gemmakey.model.ExpenseType
import com.gemmakey.model.ParsedExpense
import org.json.JSONObject
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

// Gemma instruction-tuned format:
// <start_of_turn>user\n{message}<end_of_turn>\n<start_of_turn>model\n
private const val BOS = "<bos>"
private const val USER_START = "<start_of_turn>user\n"
private const val USER_END   = "<end_of_turn>\n"
private const val MODEL_START = "<start_of_turn>model\n"
private const val MODEL_END   = "<end_of_turn>\n"

@Singleton
class PromptBuilder @Inject constructor() {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")

    // ── System context injected before every turn ────────────────────────────
    private val systemContext = """
你是一個智慧記帳助理。你的工作有兩種：
1. 當用戶描述消費或收入時，從描述中提取記帳資訊，以 JSON 格式回應。
2. 當用戶詢問記帳歷史或統計時，根據提供的資料庫記錄回答問題。

JSON 格式（僅在識別到記帳內容時使用）：
{"action":"record","amount":數字,"type":"EXPENSE或INCOME","category":"類別英文名稱","description":"簡短說明"}

支援的類別：FOOD, TRANSPORT, SHOPPING, ENTERTAINMENT, HEALTH, EDUCATION, UTILITIES, HOUSING, SALARY, BONUS, INVESTMENT, OTHER

若用戶是在詢問問題而非記錄，直接用繁體中文回答，不要輸出 JSON。
""".trimIndent()

    fun buildExpenseParsePrompt(userInput: String, ragContext: String = ""): String {
        val context = if (ragContext.isNotBlank()) "\n\n【近期記帳資料】\n$ragContext\n" else ""
        return buildTurn(
            userMessage = "$systemContext$context\n\n用戶說：$userInput",
            priorTurns = emptyList()
        )
    }

    fun buildChatPrompt(
        userMessage: String,
        ragContext: String,
        history: List<Pair<String, String>>  // (user, assistant) pairs
    ): String {
        val contextBlock = if (ragContext.isNotBlank())
            "【近期記帳資料供參考】\n$ragContext\n\n" else ""
        return buildTurn(
            userMessage = "$systemContext\n\n$contextBlock$userMessage",
            priorTurns = history
        )
    }

    fun buildImagePrompt(userInstruction: String = ""): String {
        val instruction = userInstruction.ifBlank {
            "請分析這張圖片（可能是收據、帳單或消費截圖），提取記帳資訊並以 JSON 格式回應。"
        }
        return buildTurn(
            userMessage = "$systemContext\n\n$instruction",
            priorTurns = emptyList()
        )
    }

    // ── Parse LLM response ───────────────────────────────────────────────────

    fun tryParseExpense(llmResponse: String): ParsedExpense? = runCatching {
        val jsonStr = extractJson(llmResponse) ?: return null
        val json = JSONObject(jsonStr)
        if (json.optString("action") != "record") return null

        ParsedExpense(
            amount = json.optDouble("amount").takeIf { !it.isNaN() },
            type = if (json.optString("type") == "INCOME") ExpenseType.INCOME else ExpenseType.EXPENSE,
            category = ExpenseCategory.fromString(json.optString("category", "OTHER")),
            description = json.optString("description", "")
        )
    }.getOrNull()

    private fun extractJson(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end < start) return null
        return text.substring(start, end + 1)
    }

    // ── RAG context formatter ────────────────────────────────────────────────

    fun formatRAGContext(entries: List<ExpenseEntry>): String {
        if (entries.isEmpty()) return ""
        return entries.joinToString("\n") { e ->
            val sign = if (e.type == ExpenseType.INCOME) "+" else "-"
            "${e.date.format(dateFormatter)} ${e.category.emoji}${e.category.displayName} $sign${formatAmount(e.amount)} ${e.description}"
        }
    }

    private fun formatAmount(amount: Double): String =
        if (amount == amount.toLong().toDouble())
            "${amount.toLong()}"
        else String.format("%.0f", amount)

    // ── Gemma prompt structure ───────────────────────────────────────────────

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
        append(MODEL_START)  // model begins its reply here
    }
}
