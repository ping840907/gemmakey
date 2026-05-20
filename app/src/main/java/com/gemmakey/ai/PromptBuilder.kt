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

當用戶描述任何消費或收入時，呼叫 record_expense 工具記錄它。
呼叫工具時，date 欄位規則：
- 若用戶明確提及日期（如「昨天」「上週五」「3月15日」「2024/12/25」），請換算為 yyyy-MM-dd 格式填入。
- 若用戶未提及任何日期，請填入今天的日期：$todayStr。

當用戶詢問歷史記帳問題時，根據提供的資料庫記錄直接回答。
所有回答使用繁體中文，保持簡潔。
""".trimIndent()
    }

    // 向後相容的 val（chatViewModel 改呼叫 buildSystemInstruction()）
    val systemInstruction: String get() = buildSystemInstruction()

    // ── 使用者訊息建構 ────────────────────────────────────────────────────────

    fun buildUserMessage(userInput: String, ragContext: String = ""): String {
        if (ragContext.isBlank()) return userInput
        return "【近期記帳資料】\n$ragContext\n\n$userInput"
    }

    fun buildImageInstruction(userHint: String = ""): String =
        userHint.ifBlank { "請分析圖片（收據或帳單），識別消費資訊後呼叫 record_expense 工具。" }

    // ── RAG 上下文格式化 ──────────────────────────────────────────────────────

    fun formatRAGContext(entries: List<ExpenseEntry>): String {
        if (entries.isEmpty()) return ""
        return entries.joinToString("\n") { e ->
            val sign = if (e.type == ExpenseType.INCOME) "+" else "-"
            "${e.date.format(dateFormatter)} ${e.category.emoji}${e.category.displayName} $sign${e.amount.toLong()} ${e.description}"
        }
    }
}
