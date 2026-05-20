package com.gemmakey.model

data class ParsedExpense(
    val amount: Double?,
    val type: ExpenseType,
    val category: ExpenseCategory,
    val description: String,
    val confidence: Float = 1.0f
)
