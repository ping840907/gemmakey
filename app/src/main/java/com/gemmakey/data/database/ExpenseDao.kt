package com.gemmakey.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface ExpenseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(expense: ExpenseEntity): Long

    @Update
    suspend fun update(expense: ExpenseEntity)

    @Delete
    suspend fun delete(expense: ExpenseEntity)

    @Query("SELECT * FROM expenses ORDER BY dateEpochDay DESC, id DESC LIMIT 500")
    fun observeAll(): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE dateEpochDay BETWEEN :from AND :to ORDER BY dateEpochDay DESC")
    fun observeByDateRange(from: Long, to: Long): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses ORDER BY dateEpochDay DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<ExpenseEntity>

    @Query("SELECT * FROM expenses WHERE dateEpochDay BETWEEN :from AND :to ORDER BY dateEpochDay DESC LIMIT :limit")
    suspend fun getByDateRange(from: Long, to: Long, limit: Int = 300): List<ExpenseEntity>

    @Query("SELECT * FROM expenses WHERE category = :category ORDER BY dateEpochDay DESC LIMIT :limit")
    suspend fun getByCategory(category: String, limit: Int = 20): List<ExpenseEntity>

    @Query("SELECT SUM(amount) FROM expenses WHERE type = 'EXPENSE' AND dateEpochDay BETWEEN :from AND :to")
    suspend fun getTotalExpense(from: Long, to: Long): Double?

    @Query("SELECT SUM(amount) FROM expenses WHERE type = 'INCOME' AND dateEpochDay BETWEEN :from AND :to")
    suspend fun getTotalIncome(from: Long, to: Long): Double?

    @Query("""
        SELECT e.* FROM expenses e
        INNER JOIN expenses_fts fts ON e.rowid = fts.rowid
        WHERE expenses_fts MATCH :query
        ORDER BY e.dateEpochDay DESC
        LIMIT :limit
    """)
    suspend fun searchFts(query: String, limit: Int = 20): List<ExpenseEntity>

    @Query("SELECT * FROM expenses WHERE dateEpochDay >= :fromDay ORDER BY dateEpochDay DESC LIMIT :limit")
    suspend fun getRecentFromDate(fromDay: Long, limit: Int = 30): List<ExpenseEntity>

    @Query("SELECT category, SUM(amount) as total FROM expenses WHERE type = 'EXPENSE' AND dateEpochDay BETWEEN :from AND :to GROUP BY category ORDER BY total DESC")
    suspend fun getCategoryTotals(from: Long, to: Long): List<CategoryTotal>
}

data class CategoryTotal(val category: String, val total: Double)
