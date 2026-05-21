package com.gemmakey.ai

import com.gemmakey.data.database.CategoryTotal
import com.gemmakey.model.ExpenseCategory
import com.gemmakey.model.ExpenseEntry
import com.gemmakey.model.ExpenseType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PromptBuilder @Inject constructor() {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")

    // ── System Instruction ────────────────────────────────────────────────────

    fun buildSystemInstruction(): String {
        val today = java.time.LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val todayChinese = today.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
        return """
你是 GemmaKey 智慧記帳助理。今天是 $todayChinese（$todayStr）。

【資料庫記錄說明】
當用戶詢問歷史記錄時，訊息中會附上「歷史記帳資料庫」區塊，這些是已儲存的資料，僅用於回答查詢，絕對不可重複記錄。
若無「歷史記帳資料庫」區塊，代表本次訊息是新記帳請求，直接處理即可。
只有「用戶新訊息」區塊才是用戶當前的新請求。
若有「之前的對話記錄」區塊，代表本次對話因網路切換而延續，請依此語境回答。

【記帳規則】
當用戶在「用戶新訊息」中描述新的消費或收入時，呼叫 record_expense 工具記錄。
無論是支出還是收入，一律使用 record_expense 工具，透過 type 欄位區分：
- 支出：type = "EXPENSE"
- 收入：type = "INCOME"（薪水、紅包、獎金等均為收入）

date 欄位規則：
- 若用戶明確提及日期（如「昨天」「上週五」「3月15日」），請換算為 yyyy-MM-dd 格式填入。
- 若未提及日期，填入今天：$todayStr。

當用戶詢問歷史問題時，根據「歷史記帳資料庫」區塊的記錄或統計摘要回答，不要捏造未存在的數字。
「歷史記帳資料庫」可能包含：
- 個別明細記錄（日期、類別、金額、說明）
- 統計摘要（期間合計 + 分類小計）— 可直接據此回答花費總額與分類問題
- 多月趨勢資料（多個「---」分隔的月份摘要）— 可用來回答趨勢問題
所有回答使用繁體中文，保持簡潔。
""".trimIndent()
    }

    val systemInstruction: String get() = buildSystemInstruction()

    // ── User message builder ──────────────────────────────────────────────────

    /**
     * Builds the prompt sent to either backend.
     * [priorHistory] is injected only on the first message after a backend switch,
     * so Gemma's KV-cache gets the cross-backend context without re-injecting every turn.
     */
    fun buildUserMessage(
        userInput: String,
        ragContext: String = "",
        priorHistory: List<ConversationTurn> = emptyList()
    ): String {
        val parts = mutableListOf<String>()

        if (priorHistory.isNotEmpty()) {
            val histText = priorHistory.joinToString("\n") { t ->
                "[用戶]：${t.userText}\n[助理]：${t.assistantText}"
            }
            parts += "【之前的對話記錄（網路切換前，請延續語境）】\n$histText"
        }

        if (ragContext.isNotBlank()) {
            parts += "【歷史記帳資料庫（已儲存資料，僅供查詢，禁止重複記錄）】\n$ragContext"
        }

        parts += "【用戶新訊息】\n$userInput"
        return parts.joinToString("\n\n")
    }

    fun buildImageInstruction(userHint: String = ""): String =
        userHint.ifBlank { "請分析圖片（收據或帳單），識別消費資訊後呼叫 record_expense 工具。" }

    // ── RAG formatters ────────────────────────────────────────────────────────

    /** Formats individual expense records (for detail queries). */
    fun formatRAGContext(entries: List<ExpenseEntry>): String {
        if (entries.isEmpty()) return "（查詢區間內無記帳記錄）"
        return entries.joinToString("\n") { e ->
            val sign = if (e.type == ExpenseType.INCOME) "+" else "-"
            "${e.date.format(dateFormatter)} ${e.category.emoji}${e.category.displayName} $sign${e.amount.toLong()} ${e.description}"
        }
    }

    /**
     * Formats aggregated totals for summary queries (e.g. "本月花了多少").
     * Much more token-efficient than listing individual records.
     */
    fun formatSummaryContext(
        start: LocalDate,
        end: LocalDate,
        totalExpense: Double,
        totalIncome: Double,
        categoryTotals: List<CategoryTotal>
    ): String {
        val periodFmt = DateTimeFormatter.ofPattern("MM/dd")
        val period = "${start.format(periodFmt)} ~ ${end.format(periodFmt)}"
        val sb = StringBuilder("期間：$period\n")

        if (totalExpense > 0) sb.append("支出合計：NT\$ ${totalExpense.toLong()}\n")
        if (totalIncome > 0) sb.append("收入合計：NT\$ ${totalIncome.toLong()}\n")
        if (totalExpense == 0.0 && totalIncome == 0.0) {
            sb.append("（此期間無記帳記錄）")
            return sb.toString().trimEnd()
        }

        val expenseCats = categoryTotals.filter { it.total > 0 }
        if (expenseCats.isNotEmpty()) {
            sb.append("分類統計：\n")
            expenseCats.take(8).forEach { ct ->
                val cat = ExpenseCategory.fromString(ct.category)
                sb.append("  ${cat.emoji}${cat.displayName} NT\$ ${ct.total.toLong()}\n")
            }
        }
        return sb.toString().trimEnd()
    }
}
