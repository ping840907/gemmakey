package com.moneytalks.data.database

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

@Entity(tableName = "expenses")
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val type: String,        // "EXPENSE" | "INCOME"
    val category: String,
    val description: String,
    val dateEpochDay: Long,  // LocalDate.toEpochDay()
    val rawInput: String = ""
)

@Fts4(contentEntity = ExpenseEntity::class)
@Entity(tableName = "expenses_fts")
data class ExpenseEntityFts(
    val description: String,
    val category: String,
    val rawInput: String
)
