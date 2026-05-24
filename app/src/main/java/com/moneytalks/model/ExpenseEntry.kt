package com.moneytalks.model

import java.time.LocalDate

enum class ExpenseType { EXPENSE, INCOME }

enum class ExpenseCategory(val displayName: String, val emoji: String) {
    FOOD("餐飲", "🍜"),
    TRANSPORT("交通", "🚌"),
    SHOPPING("購物", "🛍️"),
    ENTERTAINMENT("娛樂", "🎮"),
    HEALTH("醫療", "💊"),
    EDUCATION("教育", "📚"),
    UTILITIES("帳單", "💡"),
    HOUSING("住宿", "🏠"),
    SALARY("薪資", "💼"),
    BONUS("獎金", "🎁"),
    INVESTMENT("投資", "📈"),
    OTHER("其他", "📌");

    companion object {
        fun fromString(value: String): ExpenseCategory =
            entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) ||
                it.displayName == value
            } ?: OTHER
    }
}

data class ExpenseEntry(
    val id: Long = 0,
    val amount: Double,
    val type: ExpenseType,
    val category: ExpenseCategory,
    val description: String,
    val date: LocalDate = LocalDate.now(),
    val rawInput: String = ""
)
