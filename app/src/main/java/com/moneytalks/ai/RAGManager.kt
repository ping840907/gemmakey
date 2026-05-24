package com.moneytalks.ai

import com.moneytalks.data.database.CategoryTotal
import com.moneytalks.data.repository.ExpenseRepository
import com.moneytalks.model.ExpenseEntry
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RAGManager @Inject constructor(
    private val repository: ExpenseRepository,
    private val promptBuilder: PromptBuilder
) {
    private enum class QueryIntent { RECORD, QUERY_SUMMARY, QUERY_DETAIL, GENERAL }

    // Amount pattern: explicit number followed by currency unit
    private val amountPattern = Regex("""\d+\s*[元塊圓]""")

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns a context string appropriate for the user's query intent, or empty
     * when no historical data is needed (recording a new entry, general chat).
     *
     *   RECORD        → ""                 (never inject old data into new-entry prompts)
     *   QUERY_SUMMARY → aggregated totals  (period / income / expense / category breakdown)
     *   QUERY_DETAIL  → targeted records   (date range + FTS match, no baseline dump)
     *   GENERAL       → ""
     */
    suspend fun buildContext(userQuery: String): String = when (classifyIntent(userQuery)) {
        QueryIntent.RECORD, QueryIntent.GENERAL -> ""
        QueryIntent.QUERY_SUMMARY               -> buildSummaryContext(userQuery)
        QueryIntent.QUERY_DETAIL                -> buildDetailContext(userQuery)
    }

    // ── Intent classification ─────────────────────────────────────────────────

    private fun classifyIntent(query: String): QueryIntent {
        val hasAmount = amountPattern.containsMatchIn(query)

        // ── If a concrete amount is present, it's almost always a recording ──
        // Exception: explicit query words like "多少" override (e.g. "這週花了多少元？")
        val explicitQueryWords = listOf("多少", "幾元", "幾塊", "合計", "總計", "統計", "總共", "共花")
        val hasExplicitQuery = explicitQueryWords.any { query.contains(it) }
        if (hasAmount && !hasExplicitQuery) return QueryIntent.RECORD

        // ── Summary / analytics intent ────────────────────────────────────────
        // These words appear in "asking about spending" sentences, not recording ones.
        // RECORD is already ruled out above if amount is present.
        val summaryWords = listOf(
            // Quantity queries
            "多少", "幾元", "幾塊", "合計", "總計", "統計", "共花", "總共", "收支", "結餘",
            // Analytics / situation
            "花費", "消費", "支出", "趨勢", "分析", "狀況", "情形", "概況", "費用",
            // Income queries
            "收入", "薪水收了多"
        )
        if (summaryWords.any { query.contains(it) }) return QueryIntent.QUERY_SUMMARY

        // ── Detail / record-lookup intent ─────────────────────────────────────
        val detailWords = listOf(
            "哪些", "列出", "明細", "查看", "有沒有", "什麼時候", "哪天",
            "幾筆", "記錄了", "查詢", "看看", "顯示", "找", "有哪"
        )
        if (detailWords.any { query.contains(it) }) return QueryIntent.QUERY_DETAIL

        // Time reference without amount → assume detail lookup
        val timeWords = listOf(
            "今天", "昨天", "本週", "這週", "上週", "本月", "這個月",
            "上個月", "最近", "近期", "今年", "去年"
        )
        if (timeWords.any { query.contains(it) }) return QueryIntent.QUERY_DETAIL

        return QueryIntent.GENERAL
    }

    // ── Summary context (aggregated totals) ───────────────────────────────────

    private suspend fun buildSummaryContext(query: String): String {
        val now = LocalDate.now()
        // Trend / multi-month: show last 3 months when "趨勢" or "幾個月" is mentioned
        if (query.contains("趨勢") || query.contains("幾個月") || query.contains("近幾個月")) {
            return buildTrendContext(3)
        }

        val (start, end) = extractDateRange(query) ?: (now.withDayOfMonth(1) to now)

        val (totalExpense, totalIncome) = repository.getTotals(start, end)
        val categoryTotals = repository.getCategoryTotals(start, end)
        return promptBuilder.formatSummaryContext(start, end, totalExpense, totalIncome, categoryTotals)
    }

    /** Returns month-by-month summary for the last [months] months. */
    private suspend fun buildTrendContext(months: Int): String {
        val now = YearMonth.now()
        val sb = StringBuilder()
        repeat(months) { offset ->
            val ym = now.minusMonths(offset.toLong())
            val start = ym.atDay(1)
            val end   = if (offset == 0) LocalDate.now() else ym.atEndOfMonth()
            val (exp, inc) = repository.getTotals(start, end)
            val catTotals  = repository.getCategoryTotals(start, end)
            sb.append(promptBuilder.formatSummaryContext(start, end, exp, inc, catTotals))
            sb.append("\n---\n")
        }
        return sb.toString().trimEnd('-', '\n', ' ')
    }

    // ── Detail context (targeted record retrieval) ────────────────────────────

    private suspend fun buildDetailContext(query: String): String {
        val seen     = mutableSetOf<Long>()
        val combined = mutableListOf<ExpenseEntry>()

        // 1. Date range (most specific signal)
        extractDateRange(query)?.let { (start, end) ->
            repository.getByDateRange(start, end).forEach { e ->
                if (seen.add(e.id)) combined.add(e)
            }
        }

        // 2. FTS keyword search
        extractKeywords(query).filter { it.length >= 2 }.forEach { keyword ->
            runCatching { repository.searchByKeyword(keyword) }.getOrNull()
                ?.forEach { e -> if (seen.add(e.id)) combined.add(e) }
        }

        // No baseline "recent N records" — only what actually matches
        val entries = combined.sortedByDescending { it.date }.take(25)
        return promptBuilder.formatRAGContext(entries)
    }

    // ── Keyword extraction ────────────────────────────────────────────────────

    private fun extractKeywords(query: String): List<String> {
        val result = mutableListOf<String>()
        val categoryMap = mapOf(
            "餐" to "FOOD", "食" to "FOOD", "吃" to "FOOD", "飲" to "FOOD",
            "咖啡" to "FOOD", "早餐" to "FOOD", "午餐" to "FOOD", "晚餐" to "FOOD",
            "交通" to "TRANSPORT", "計程" to "TRANSPORT", "捷運" to "TRANSPORT",
            "公車" to "TRANSPORT", "加油" to "TRANSPORT", "停車" to "TRANSPORT",
            "購物" to "SHOPPING", "買" to "SHOPPING", "超市" to "SHOPPING",
            "娛樂" to "ENTERTAINMENT", "電影" to "ENTERTAINMENT", "遊戲" to "ENTERTAINMENT",
            "醫" to "HEALTH", "藥" to "HEALTH", "健康" to "HEALTH",
            "學" to "EDUCATION", "書" to "EDUCATION", "課" to "EDUCATION",
            "水電" to "UTILITIES", "電費" to "UTILITIES", "網路" to "UTILITIES",
            "房" to "HOUSING", "租" to "HOUSING",
            "薪" to "SALARY", "薪水" to "SALARY",
        )
        categoryMap.forEach { (keyword, category) ->
            if (query.contains(keyword)) result.add(category)
        }
        query.split(Regex("[\\s,，。？?！!、]+"))
            .filter { it.length in 2..8 }
            .let { result.addAll(it) }
        return result.distinct()
    }

    // ── Date range extraction ─────────────────────────────────────────────────

    private fun extractDateRange(query: String): Pair<LocalDate, LocalDate>? {
        val now = LocalDate.now()
        // Specific month mention: N月 (e.g. "5月", "三月")
        val monthNum = Regex("""(\d{1,2})月""").find(query)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            query.contains("今天") || query.contains("今日") ->
                now to now
            query.contains("昨天") || query.contains("昨日") ->
                now.minusDays(1) to now.minusDays(1)
            query.contains("本週") || query.contains("這週") || query.contains("本周") ->
                now.minusDays(now.dayOfWeek.value.toLong() - 1) to now
            query.contains("上週") || query.contains("上周") ->
                now.minusDays(now.dayOfWeek.value.toLong() + 6) to
                now.minusDays(now.dayOfWeek.value.toLong())
            query.contains("本月") || query.contains("這個月") ->
                now.withDayOfMonth(1) to now
            query.contains("上個月") || query.contains("上月") ->
                now.minusMonths(1).withDayOfMonth(1) to
                now.minusMonths(1).withDayOfMonth(now.minusMonths(1).lengthOfMonth())
            query.contains("今年") ->
                now.withDayOfYear(1) to now
            query.contains("去年") ->
                now.minusYears(1).withDayOfYear(1) to
                now.minusYears(1).withDayOfYear(now.minusYears(1).lengthOfYear())
            query.contains("最近") || query.contains("近期") ->
                now.minusDays(30) to now
            monthNum != null && monthNum in 1..12 -> {
                // Specific month in current year (e.g. "5月花了多少")
                val targetYear = if (monthNum > now.monthValue) now.year - 1 else now.year
                val ym = YearMonth.of(targetYear, monthNum)
                ym.atDay(1) to ym.atEndOfMonth()
            }
            else -> null
        }
    }
}
