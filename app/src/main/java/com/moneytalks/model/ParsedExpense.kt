package com.moneytalks.model

import java.time.LocalDate

data class ParsedExpense(
    val amount: Double? = null,
    val type: ExpenseType = ExpenseType.EXPENSE,
    val category: ExpenseCategory = ExpenseCategory.OTHER,
    val description: String = "",
    val date: LocalDate = LocalDate.now(),
    val confidence: Float = 1.0f
)
