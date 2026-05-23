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

    // ── Gemma system instruction (on-device, small model) ─────────────────────
    //
    // Design principles:
    //   • Short and numbered — small models follow explicit lists better than prose
    //   • Positive instructions first ("do X"), then negatives ("don't Y")
    //   • Repeat category values to reinforce @ToolParam (small model benefit)
    //   • One concrete date-conversion example to anchor the rule
    //   • No verbose meta-commentary the model doesn't need to reproduce

    fun buildGemmaSystemInstruction(): String {
        val today     = LocalDate.now()
        val todayStr  = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val yesterday = today.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        return """
你是 GemmaKey 記帳助理。今天：$todayStr

[任務]
1. 用戶描述消費或收入 → 輸出 <tool_call> 記錄（見格式）
2. 用戶詢問歷史 → 根據「記帳資料庫」區塊的數據回答
3. 其他對話 → 簡短繁體中文回覆

[type 欄位判斷 — 最優先]
訊息含以下任一詞 → type 必須填 "INCOME"：
  收入 薪水 薪資 工資 月薪 獎金 紅包 利息 股息 分紅 退款 退費 補貼 回扣
其餘描述花費、購買、消費 → type 填 "EXPENSE"

[其餘欄位規則]
- category：FOOD 餐飲 | TRANSPORT 交通 | SHOPPING 購物 | ENTERTAINMENT 娛樂
  HEALTH 醫療 | EDUCATION 教育 | UTILITIES 帳單 | HOUSING 住宿
  SALARY 薪資 | BONUS 獎金 | INVESTMENT 投資 | OTHER 其他
- date：yyyy-MM-dd。有提日期就換算，例「昨天」→ $yesterday；沒提就填 $todayStr
- description：10 字內，不重複 category 名稱

[工具輸出格式]
記錄時只輸出 JSON，不加其他說明。

收入範例（薪水 50000 元）：
<tool_call>{"name":"record_expense","arguments":{"amount":50000,"type":"INCOME","category":"SALARY","description":"薪水","date":"$todayStr"}}</tool_call>

支出範例（午餐 150 元）：
<tool_call>{"name":"record_expense","arguments":{"amount":150,"type":"EXPENSE","category":"FOOD","description":"午餐","date":"$todayStr"}}</tool_call>

[禁止事項]
- 禁止對「記帳資料庫」中的舊資料再次輸出 <tool_call>
- 禁止捏造資料庫中不存在的金額或記錄
""".trimIndent()
    }

    // ── Gemini system instruction (cloud model, full function calling) ─────────
    //
    // Design principles:
    //   • Gemini handles tool schema via SDK; focus on WHEN to call, not HOW
    //   • Describe RAG context types so model uses each correctly
    //   • Explicit income trigger words to prevent mis-classification
    //   • Cross-backend context section explained once, cleanly

    fun buildGeminiSystemInstruction(): String {
        val today       = LocalDate.now()
        val todayStr    = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val todayChinese = today.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
        return """
你是 GemmaKey 智慧記帳助理。今天是 $todayChinese（$todayStr）。

【記帳】
用戶在「用戶訊息」中描述新消費或收入時，呼叫 record_expense 工具：
- type = "EXPENSE"：餐飲、交通、購物、娛樂、醫療、教育、帳單、住宿等支出
- type = "INCOME"：薪水、紅包、獎金、投資收益、任何金錢流入
- date：用戶有提到日期就換算為 yyyy-MM-dd；未提到填 $todayStr
- description：10 字以內，說明具體消費內容

【歷史查詢】
訊息中有「記帳資料庫」區塊時，根據其內容回答，不可重複記錄舊資料：
- 明細格式：日期 類別 ±金額 說明 — 用於列出特定記錄
- 統計摘要：期間合計 + 分類小計 — 直接據此回答「花了多少」類問題
- 趨勢格式：多月摘要以「---」分隔 — 用於回答月份對比或趨勢問題
不可捏造資料庫中不存在的金額或記錄。

【對話延續】
有「對話記錄」區塊時，代表後端切換前的對話，請延續語境作答。

所有回答使用繁體中文，保持簡潔。
""".trimIndent()
    }

    // Keep for backwards compatibility; delegates to Gemma version
    fun buildSystemInstruction(): String = buildGemmaSystemInstruction()
    val systemInstruction: String get() = buildGemmaSystemInstruction()

    // ── User message builder ──────────────────────────────────────────────────
    //
    // Section labels are deliberately short — the system instruction already
    // explains each section's semantics; repeating them here wastes tokens.

    fun buildUserMessage(
        userInput: String,
        ragContext: String = "",
        priorHistory: List<ConversationTurn> = emptyList()
    ): String {
        val parts = mutableListOf<String>()

        if (priorHistory.isNotEmpty()) {
            val histText = priorHistory.joinToString("\n") { t ->
                "用戶：${t.userText}\n助理：${t.assistantText}"
            }
            parts += "[對話記錄]\n$histText"
        }

        if (ragContext.isNotBlank()) {
            parts += "[記帳資料庫]\n$ragContext"
        }

        parts += "[用戶訊息]\n$userInput"
        return parts.joinToString("\n\n")
    }

    fun buildImageInstruction(userHint: String = ""): String =
        userHint.ifBlank { "請分析圖片中的收據或帳單，識別消費資訊後呼叫 record_expense 工具記錄。" }

    // ── RAG formatters ────────────────────────────────────────────────────────

    fun formatRAGContext(entries: List<ExpenseEntry>): String {
        if (entries.isEmpty()) return "（查詢區間內無記帳記錄）"
        return entries.joinToString("\n") { e ->
            val sign = if (e.type == ExpenseType.INCOME) "+" else "-"
            "${e.date.format(dateFormatter)} ${e.category.emoji}${e.category.displayName} $sign${e.amount.toLong()} ${e.description}"
        }
    }

    fun formatSummaryContext(
        start: LocalDate,
        end: LocalDate,
        totalExpense: Double,
        totalIncome: Double,
        categoryTotals: List<CategoryTotal>
    ): String {
        val periodFmt = DateTimeFormatter.ofPattern("MM/dd")
        val period = "${start.format(periodFmt)}~${end.format(periodFmt)}"
        val sb = StringBuilder("期間：$period\n")

        if (totalExpense == 0.0 && totalIncome == 0.0) {
            sb.append("（此期間無記帳記錄）")
            return sb.toString().trimEnd()
        }
        if (totalExpense > 0) sb.append("支出：NT\$${totalExpense.toLong()}\n")
        if (totalIncome > 0) sb.append("收入：NT\$${totalIncome.toLong()}\n")

        val expenseCats = categoryTotals.filter { it.total > 0 }
        if (expenseCats.isNotEmpty()) {
            sb.append("分類：")
            sb.append(expenseCats.take(8).joinToString(" | ") { ct ->
                val cat = ExpenseCategory.fromString(ct.category)
                "${cat.emoji}${cat.displayName} ${ct.total.toLong()}"
            })
        }
        return sb.toString().trimEnd()
    }
}
