package com.gemmakey.ai

import com.gemmakey.data.repository.ExpenseRepository
import com.gemmakey.model.ExpenseEntry
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RAGManager @Inject constructor(
    private val repository: ExpenseRepository,
    private val promptBuilder: PromptBuilder
) {
    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Build a RAG context string for the given user query.
     * Combines keyword FTS search + recent records + date-range extraction.
     */
    suspend fun buildContext(userQuery: String): String {
        val entries = retrieveRelevant(userQuery)
        return promptBuilder.formatRAGContext(entries)
    }

    // ── Retrieval logic ──────────────────────────────────────────────────────

    private suspend fun retrieveRelevant(query: String): List<ExpenseEntry> {
        val results = mutableSetOf<Long>() // de-duplicate by id
        val combined = mutableListOf<ExpenseEntry>()

        // 1. FTS keyword search
        val keywords = extractKeywords(query)
        for (keyword in keywords) {
            if (keyword.length < 2) continue
            try {
                repository.searchByKeyword(keyword).forEach { e ->
                    if (results.add(e.id)) combined.add(e)
                }
            } catch (_: Exception) {}
        }

        // 2. Date range extraction from query
        extractDateRange(query)?.let { (start, end) ->
            repository.getByDateRange(start, end).forEach { e ->
                if (results.add(e.id)) combined.add(e)
            }
        }

        // 3. Always include last 14 days as baseline context
        val recent = repository.getContextForRAG(20)
        recent.forEach { e ->
            if (results.add(e.id)) combined.add(e)
        }

        // Sort by date desc, cap at 40 entries to stay within context budget
        return combined.sortedByDescending { it.date }.take(40)
    }

    // ── Keyword extraction (Chinese-aware) ──────────────────────────────────

    private fun extractKeywords(query: String): List<String> {
        val result = mutableListOf<String>()

        // Category Chinese keywords
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

        for ((keyword, category) in categoryMap) {
            if (query.contains(keyword)) result.add(category)
        }

        // Add raw Chinese segments as keywords too
        val segments = query.split(Regex("[\\s,，。？?！!、]+"))
        result.addAll(segments.filter { it.length in 2..8 })

        return result.distinct()
    }

    // ── Date range extraction ────────────────────────────────────────────────

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
