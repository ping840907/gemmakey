package com.gemmakey.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ExpenseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(expense: ExpenseEntity): Long

    @Update
    abstract suspend fun update(expense: ExpenseEntity)

    @Delete
    abstract suspend fun delete(expense: ExpenseEntity)

    @Query("SELECT * FROM expenses ORDER BY dateEpochDay DESC, id DESC LIMIT 500")
    abstract fun observeAll(): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE dateEpochDay BETWEEN :from AND :to ORDER BY dateEpochDay DESC")
    abstract fun observeByDateRange(from: Long, to: Long): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses ORDER BY dateEpochDay DESC LIMIT :limit")
    abstract suspend fun getRecent(limit: Int = 50): List<ExpenseEntity>

    @Query("SELECT * FROM expenses WHERE dateEpochDay BETWEEN :from AND :to ORDER BY dateEpochDay DESC LIMIT :limit")
    abstract suspend fun getByDateRange(from: Long, to: Long, limit: Int = 300): List<ExpenseEntity>

    @Query("SELECT * FROM expenses WHERE category = :category ORDER BY dateEpochDay DESC LIMIT :limit")
    abstract suspend fun getByCategory(category: String, limit: Int = 20): List<ExpenseEntity>

    @Query("SELECT SUM(amount) FROM expenses WHERE type = 'EXPENSE' AND dateEpochDay BETWEEN :from AND :to")
    abstract suspend fun getTotalExpense(from: Long, to: Long): Double?

    @Query("SELECT SUM(amount) FROM expenses WHERE type = 'INCOME' AND dateEpochDay BETWEEN :from AND :to")
    abstract suspend fun getTotalIncome(from: Long, to: Long): Double?

    @Query("""
        SELECT e.* FROM expenses e
        INNER JOIN expenses_fts fts ON e.rowid = fts.rowid
        WHERE expenses_fts MATCH :query
        ORDER BY e.dateEpochDay DESC
        LIMIT :limit
    """)
    abstract suspend fun searchFts(query: String, limit: Int = 20): List<ExpenseEntity>

    @Query("SELECT * FROM expenses WHERE dateEpochDay >= :fromDay ORDER BY dateEpochDay DESC LIMIT :limit")
    abstract suspend fun getRecentFromDate(fromDay: Long, limit: Int = 30): List<ExpenseEntity>

    @Query("SELECT category, SUM(amount) as total FROM expenses WHERE type = 'EXPENSE' AND dateEpochDay BETWEEN :from AND :to GROUP BY category ORDER BY total DESC")
    abstract suspend fun getCategoryTotals(from: Long, to: Long): List<CategoryTotal>

    // ── Backup / Restore ─────────────────────────────────────────────────────

    @Query("SELECT * FROM expenses ORDER BY dateEpochDay DESC")
    abstract suspend fun getAll(): List<ExpenseEntity>

    @Query("SELECT COUNT(*) FROM expenses")
    abstract suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(expenses: List<ExpenseEntity>)

    @Query("DELETE FROM expenses")
    abstract suspend fun deleteAll()

    @Transaction
    open suspend fun replaceAll(expenses: List<ExpenseEntity>) {
        deleteAll()
        insertAll(expenses)
    }
}

data class CategoryTotal(val category: String, val total: Double)
