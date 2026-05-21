package com.gemmakey.ai

import com.gemmakey.data.database.CategoryTotal
import com.gemmakey.data.repository.ExpenseRepository
import com.gemmakey.model.ExpenseCategory
import com.gemmakey.model.ExpenseEntry
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RAGManager @Inject constructor(
    private val repository: ExpenseRepository,
    private val promptBuilder: PromptBuilder
) {
    private enum class QueryIntent { RECORD, QUERY_SUMMARY, QUERY_DETAIL, GENERAL }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns a context string for the given user query, or empty string when
     * no historical context is needed (recording, general chat).
     *
     * Intent routing:
     *   RECORD        → ""  (no pollution of new-entry prompts)
     *   QUERY_SUMMARY → aggregated totals (compact: ~5 lines)
     *   QUERY_DETAIL  → targeted records matching date/keyword (no baseline dump)
     *   GENERAL       → ""
     */
    suspend fun buildContext(userQuery: String): String = when (classifyIntent(userQuery)) {
        QueryIntent.RECORD, QueryIntent.GENERAL -> ""
        QueryIntent.QUERY_SUMMARY               -> buildSummaryContext(userQuery)
        QueryIntent.QUERY_DETAIL                -> buildDetailContext(userQuery)
    }

    // ── Intent classification ─────────────────────────────────────────────────

    private fun classifyIntent(query: String): QueryIntent {
        // Summary query: user wants aggregated amounts
        val summaryWords = listOf(
            "多少", "幾元", "幾塊", "合計", "總計", "統計", "共花", "花了多少",
            "總共", "收支", "結餘", "花費多", "消費多", "支出多"
        )
        if (summaryWords.any { query.contains(it) }) return QueryIntent.QUERY_SUMMARY

        // Detail query: user wants to see specific records
        val detailWords = listOf(
            "哪些", "列出", "明細", "查看", "有沒有", "什麼時候", "哪天",
            "幾筆", "記錄了", "查詢", "看看", "顯示"
        )
        if (detailWords.any { query.contains(it) }) return QueryIntent.QUERY_DETAIL

        // Time reference without an amount → detail query
        val timeWords = listOf("今天", "昨天", "本週", "這週", "上週", "本月", "這個月", "上個月", "最近", "近期")
        val hasTimeRef = timeWords.any { query.contains(it) }
        val hasAmount  = Regex("""\d+\s*[元塊圓]""").containsMatchIn(query)

        if (hasTimeRef && !hasAmount) return QueryIntent.QUERY_DETAIL

        // Amount + expense/income verb → recording a new entry
        val expenseVerbs = listOf("花了", "買了", "付了", "消費", "花費", "吃了", "喝了", "搭了", "租了", "繳了")
        val incomeVerbs  = listOf("收到", "收入", "薪水", "發薪", "獎金", "賺了", "領了")
        val hasExpenseVerb = (expenseVerbs + incomeVerbs).any { query.contains(it) }

        if (hasAmount && hasExpenseVerb) return QueryIntent.RECORD
        if (hasAmount) return QueryIntent.RECORD   // amount alone is likely recording

        return QueryIntent.GENERAL
    }

    // ── Summary context (aggregated totals) ──────────────────────────────────

    private suspend fun buildSummaryContext(query: String): String {
        val now = LocalDate.now()
        val (start, end) = extractDateRange(query) ?: (now.withDayOfMonth(1) to now)

        val (totalExpense, totalIncome) = repository.getTotals(start, end)
        val categoryTotals = repository.getCategoryTotals(start, end)

        return promptBuilder.formatSummaryContext(start, end, totalExpense, totalIncome, categoryTotals)
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

        // No baseline "recent N records" — only inject what actually matches
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

        // Raw Chinese segments as FTS terms
        query.split(Regex("[\\s,，。？?！!、]+"))
            .filter { it.length in 2..8 }
            .let { result.addAll(it) }

        return result.distinct()
    }

    // ── Date range extraction ─────────────────────────────────────────────────

    private fun extractDateRange(query: String): Pair<LocalDate, LocalDate>? {
        val now = LocalDate.now()
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
            query.contains("最近") || query.contains("近期") ->
                now.minusDays(30) to now
            else -> null
        }
    }
}
