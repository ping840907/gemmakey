package com.gemmakey.model

import java.time.LocalDate

data class ParsedExpense(
    val amount: Double?,
    val type: ExpenseType,
    val category: ExpenseCategory,
    val description: String,
    val date: LocalDate = LocalDate.now(),  // 從輸入萃取；未提及則預設今日
    val confidence: Float = 1.0f
)
