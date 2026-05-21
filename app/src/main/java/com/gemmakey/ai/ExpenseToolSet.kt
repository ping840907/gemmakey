package com.gemmakey.ai

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.gemmakey.model.ExpenseCategory
import com.gemmakey.model.ExpenseType
import com.gemmakey.model.ParsedExpense
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class ExpenseToolSet : ToolSet {

    @Volatile var lastCall: ParsedExpense? = null
        private set

    @Tool(description = "記錄一筆支出或收入到記帳系統，並顯示確認表格讓用戶核對後儲存")
    fun record_expense(
        @ToolParam(description = "金額，正數數字，不含貨幣符號")
        amount: Double,

        @ToolParam(description = "交易類型：EXPENSE 為支出，INCOME 為收入")
        type: String,

        @ToolParam(
            description = "費用類別，從以下選擇：" +
                "FOOD（餐飲）、TRANSPORT（交通）、SHOPPING（購物）、" +
                "ENTERTAINMENT（娛樂）、HEALTH（醫療）、EDUCATION（教育）、" +
                "UTILITIES（帳單）、HOUSING（住宿）、SALARY（薪資）、" +
                "BONUS（獎金）、INVESTMENT（投資）、OTHER（其他）"
        )
        category: String,

        @ToolParam(description = "10 字以內的簡短說明")
        description: String,

        @ToolParam(
            description = "交易日期，格式 yyyy-MM-dd。" +
                "若用戶有提及日期（昨天、上週五、3月15日等），請換算為絕對日期；" +
                "若未提及日期，請填入今天的日期。"
        )
        date: String
    ): Map<String, String> {
        if (amount <= 0) {
            return mapOf("status" to "error", "message" to "金額必須大於零")
        }

        lastCall = ParsedExpense(
            amount      = amount,
            type        = if (type.trim().uppercase() == "INCOME") ExpenseType.INCOME
                          else ExpenseType.EXPENSE,
            category    = ExpenseCategory.fromString(category.trim()),
            description = description.trim(),
            date        = parseDate(date)
        )
        return mapOf(
            "status"  to "awaiting_confirmation",
            "message" to "已偵測到記帳內容，請用戶確認後儲存"
        )
    }

    fun clearLastCall() { lastCall = null }

    /**
     * Fallback parser for when LiteRT-LM native tool calling doesn't fire.
     * Handles Gemma 4's raw output format:
     *   <|tool_call|>call:record_expense{amount:120,category:"晚餐",date:"2026-05-19"}<|/tool_call|>
     * Special quote tokens like <|"|> are normalised to " before matching.
     */
    fun parseFromToolCallText(response: String): ParsedExpense? {
        val normalized = response.replace(Regex("""<\|["']\|>"""), "\"")

        // Match both record_expense and record_income (model sometimes uses wrong name for income)
        val match = Regex(
            """call\s*:\s*record_(expense|income)\s*\{([^}]+)\}""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(normalized) ?: return null

        val calledIncome = match.groupValues[1].equals("income", ignoreCase = true)
        val args = match.groupValues[2]

        fun str(key: String): String? =
            Regex(""""?$key"?\s*:\s*"?([^",}\n]+)"?""", RegexOption.IGNORE_CASE)
                .find(args)?.groupValues?.get(1)?.trim()?.trimEnd('"')

        val amount = str("amount")?.toDoubleOrNull()?.takeIf { it > 0 } ?: return null

        val typeRaw = str("type")?.uppercase() ?: ""
        val isIncome = calledIncome ||
                       typeRaw == "INCOME" ||
                       typeRaw.contains("收入") ||
                       typeRaw.contains("REVENUE")

        lastCall = ParsedExpense(
            amount      = amount,
            type        = if (isIncome) ExpenseType.INCOME else ExpenseType.EXPENSE,
            category    = ExpenseCategory.fromString(str("category") ?: ""),
            description = str("description")?.ifBlank { null } ?: str("category") ?: "",
            date        = parseDate(str("date") ?: "")
        )
        return lastCall
    }

    // ── 日期解析（支援 yyyy-MM-dd 及常見格式，失敗則 fallback 今日）─────────
    private fun parseDate(raw: String): LocalDate {
        val s = raw.trim()
        val today = LocalDate.now()

        // 有完整年份的格式
        val fullFormatters = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,           // yyyy-MM-dd（模型主要輸出格式）
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy年MM月dd日"),
            DateTimeFormatter.ofPattern("yyyy年M月d日"),
        )
        for (fmt in fullFormatters) {
            try { return LocalDate.parse(s, fmt) } catch (_: DateTimeParseException) {}
        }

        // 無年份：補入今年（例如模型輸出 "3月15日" 或 "03-15"）
        val noYearFormatters = listOf(
            DateTimeFormatter.ofPattern("M月d日"),
            DateTimeFormatter.ofPattern("MM月dd日"),
            DateTimeFormatter.ofPattern("MM-dd"),
            DateTimeFormatter.ofPattern("MM/dd"),
        )
        for (fmt in noYearFormatters) {
            try {
                val ta = fmt.parse(s)
                val month = ta.get(java.time.temporal.ChronoField.MONTH_OF_YEAR)
                val day   = ta.get(java.time.temporal.ChronoField.DAY_OF_MONTH)
                return LocalDate.of(today.year, month, day)
            } catch (_: Exception) {}
        }

        return today  // 完全無法解析時使用今日
    }
}
