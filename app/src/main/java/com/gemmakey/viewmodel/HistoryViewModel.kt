package com.gemmakey.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gemmakey.data.database.CategoryTotal
import com.gemmakey.data.repository.ExpenseRepository
import com.gemmakey.model.ExpenseEntry
import com.gemmakey.model.ExpenseType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class HistoryUiState(
    val entries: List<ExpenseEntry> = emptyList(),
    val currentMonth: YearMonth = YearMonth.now(),
    val totalExpense: Double = 0.0,
    val totalIncome: Double = 0.0,
    val categoryTotals: List<CategoryTotal> = emptyList(),
    val isLoading: Boolean = false,
    val pendingDelete: ExpenseEntry? = null
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: ExpenseRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init { loadMonth(YearMonth.now()) }

    fun loadMonth(month: YearMonth) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, currentMonth = month) }
            val start = month.atDay(1)
            val end = month.atEndOfMonth()

            repository.observeByMonth(month.year, month.monthValue).collect { entries ->
                val (expense, income) = repository.getTotals(start, end)
                val catTotals = repository.getCategoryTotals(start, end)
                _uiState.update {
                    it.copy(
                        entries = entries,
                        totalExpense = expense,
                        totalIncome = income,
                        categoryTotals = catTotals,
                        isLoading = false
                    )
                }
            }
        }
    }

    fun prevMonth() = loadMonth(_uiState.value.currentMonth.minusMonths(1))
    fun nextMonth() = loadMonth(_uiState.value.currentMonth.plusMonths(1))

    fun deleteEntry(entry: ExpenseEntry) {
        viewModelScope.launch {
            _uiState.update { it.copy(pendingDelete = entry) }
            repository.delete(entry)
        }
    }

    fun undoDelete() {
        viewModelScope.launch {
            val entry = _uiState.value.pendingDelete ?: return@launch
            _uiState.update { it.copy(pendingDelete = null) }
            repository.save(entry)
        }
    }

    fun clearPendingDelete() {
        _uiState.update { it.copy(pendingDelete = null) }
    }

    fun saveEntry(entry: ExpenseEntry) {
        viewModelScope.launch { repository.save(entry) }
    }
}
