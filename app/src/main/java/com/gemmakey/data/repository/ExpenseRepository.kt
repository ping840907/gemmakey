package com.gemmakey.data.repository

import com.gemmakey.data.database.CategoryTotal
import com.gemmakey.data.database.ExpenseDao
import com.gemmakey.data.database.ExpenseEntity
import com.gemmakey.model.ExpenseCategory
import com.gemmakey.model.ExpenseEntry
import com.gemmakey.model.ExpenseType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExpenseRepository @Inject constructor(private val dao: ExpenseDao) {

    val allExpenses: Flow<List<ExpenseEntry>> = dao.observeAll().map { list ->
        list.map { it.toDomain() }
    }

    fun observeByMonth(year: Int, month: Int): Flow<List<ExpenseEntry>> {
        val start = LocalDate.of(year, month, 1)
        val end = start.withDayOfMonth(start.lengthOfMonth())
        return dao.observeByDateRange(start.toEpochDay(), end.toEpochDay())
            .map { list -> list.map { it.toDomain() } }
    }

    suspend fun save(entry: ExpenseEntry): Long =
        dao.insert(entry.toEntity())

    suspend fun update(entry: ExpenseEntry) =
        dao.update(entry.toEntity())

    suspend fun delete(entry: ExpenseEntry) =
        dao.delete(entry.toEntity())

    suspend fun getRecent(limit: Int = 50): List<ExpenseEntry> =
        dao.getRecent(limit).map { it.toDomain() }

    suspend fun searchByKeyword(query: String): List<ExpenseEntry> =
        dao.searchFts(query).map { it.toDomain() }

    suspend fun getByDateRange(start: LocalDate, end: LocalDate): List<ExpenseEntry> =
        dao.getByDateRange(start.toEpochDay(), end.toEpochDay()).map { it.toDomain() }

    suspend fun getTotals(start: LocalDate, end: LocalDate): Pair<Double, Double> {
        val expense = dao.getTotalExpense(start.toEpochDay(), end.toEpochDay()) ?: 0.0
        val income = dao.getTotalIncome(start.toEpochDay(), end.toEpochDay()) ?: 0.0
        return expense to income
    }

    suspend fun getCategoryTotals(start: LocalDate, end: LocalDate): List<CategoryTotal> =
        dao.getCategoryTotals(start.toEpochDay(), end.toEpochDay())

    suspend fun getContextForRAG(limit: Int = 30): List<ExpenseEntry> {
        val thirtyDaysAgo = LocalDate.now().minusDays(30).toEpochDay()
        return dao.getRecentFromDate(thirtyDaysAgo, limit).map { it.toDomain() }
    }

    // ── Backup / Restore ─────────────────────────────────────────────────────

    suspend fun getAllEntries(): List<ExpenseEntry> =
        dao.getAll().map { it.toDomain() }

    suspend fun count(): Int = dao.count()

    suspend fun insertAll(entries: List<ExpenseEntry>) =
        dao.insertAll(entries.map { it.toEntity().copy(id = 0) })

    suspend fun replaceAll(entries: List<ExpenseEntry>) =
        dao.replaceAll(entries.map { it.toEntity().copy(id = 0) })

    private fun ExpenseEntity.toDomain() = ExpenseEntry(
        id = id,
        amount = amount,
        type = ExpenseType.valueOf(type),
        category = ExpenseCategory.fromString(category),
        description = description,
        date = LocalDate.ofEpochDay(dateEpochDay),
        rawInput = rawInput
    )

    private fun ExpenseEntry.toEntity() = ExpenseEntity(
        id = id,
        amount = amount,
        type = type.name,
        category = category.name,
        description = description,
        dateEpochDay = date.toEpochDay(),
        rawInput = rawInput
    )
}
