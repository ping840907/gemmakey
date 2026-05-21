package com.gemmakey.ai

import com.gemmakey.model.ExpenseEntry
import com.gemmakey.model.ExpenseType
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 負責建構純文字的 system instruction 和 RAG 上下文。
 * Function calling 改由 ExpenseToolSet（@Tool 反射式）處理，
 * 不再需要手動注入 <tool> token。
 */
@Singleton
class PromptBuilder @Inject constructor() {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")

    // ── System Instruction（動態注入今日日期，每次對話開始時取得）───────────

    fun buildSystemInstruction(): String {
        val today = java.time.LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)  // yyyy-MM-dd
        val todayChinese = today.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
        return """
你是 GemmaKey 智慧記帳助理。今天是 $todayChinese（$todayStr）。

【資料庫記錄說明】
每次用戶訊息前會附上「歷史記帳資料庫」區塊，這些是已儲存的資料，僅用於回答查詢，絕對不可重複記錄。
只有「用戶新訊息」區塊才是用戶當前的新請求。

【記帳規則】
當用戶在「用戶新訊息」中描述新的消費或收入時，呼叫 record_expense 工具記錄。
無論是支出還是收入，一律使用 record_expense 工具，透過 type 欄位區分：
- 支出：type = "EXPENSE"
- 收入：type = "INCOME"（薪水、紅包、獎金等均為收入）

date 欄位規則：
- 若用戶明確提及日期（如「昨天」「上週五」「3月15日」），請換算為 yyyy-MM-dd 格式填入。
- 若未提及日期，填入今天：$todayStr。

當用戶詢問歷史問題時，根據「歷史記帳資料庫」區塊的記錄回答，不要捏造未存在的記錄。
所有回答使用繁體中文，保持簡潔。
""".trimIndent()
    }

    // 向後相容的 val（chatViewModel 改呼叫 buildSystemInstruction()）
    val systemInstruction: String get() = buildSystemInstruction()

    // ── 使用者訊息建構 ────────────────────────────────────────────────────────

    fun buildUserMessage(userInput: String, ragContext: String = ""): String {
        if (ragContext.isBlank()) return userInput
        return "【歷史記帳資料庫（已儲存，僅供查詢，禁止重複記錄）】\n$ragContext\n\n【用戶新訊息】\n$userInput"
    }

    fun buildImageInstruction(userHint: String = ""): String =
        userHint.ifBlank { "請分析圖片（收據或帳單），識別消費資訊後呼叫 record_expense 工具。" }

    // ── RAG 上下文格式化 ──────────────────────────────────────────────────────

    fun formatRAGContext(entries: List<ExpenseEntry>): String {
        // Always return a non-blank string so buildUserMessage always injects the database
        // section. Without this, an empty list → blank ragContext → no section injected →
        // model falls back to conversation history and can hallucinate unconfirmed entries.
        if (entries.isEmpty()) return "（資料庫目前無任何記帳記錄）"
        return entries.joinToString("\n") { e ->
            val sign = if (e.type == ExpenseType.INCOME) "+" else "-"
            "${e.date.format(dateFormatter)} ${e.category.emoji}${e.category.displayName} $sign${e.amount.toLong()} ${e.description}"
        }
    }
}
