package com.gemmakey.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gemmakey.data.database.CategoryTotal
import com.gemmakey.model.ExpenseCategory
import com.gemmakey.ui.components.MonthNavBar
import com.gemmakey.ui.theme.GreenIncome
import com.gemmakey.ui.theme.RedExpense
import com.gemmakey.viewmodel.HistoryViewModel
import java.time.YearMonth

@Composable
fun StatisticsScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            // Month selector
            MonthNavBar(
                label = "${state.currentMonth.year}年 ${state.currentMonth.monthValue}月",
                onPrev = viewModel::prevMonth,
                onNext = viewModel::nextMonth,
                canGoNext = state.currentMonth < YearMonth.now()
            )
        }

        item {
            // Income vs Expense donut-like summary
            IncomeExpenseSummary(
                totalExpense = state.totalExpense,
                totalIncome  = state.totalIncome
            )
        }

        if (state.categoryTotals.isNotEmpty()) {
            item {
                Text(
                    "支出分類",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            items(state.categoryTotals) { catTotal ->
                CategoryBar(catTotal = catTotal, maxAmount = state.categoryTotals.first().total)
            }
        }
    }
}

@Composable
private fun IncomeExpenseSummary(totalExpense: Double, totalIncome: Double) {
    val balance = totalIncome - totalExpense
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(20.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        FinancialBlock(label = "收入", amount = totalIncome, color = GreenIncome)

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("結餘", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${if (balance >= 0) "+" else ""}NT\$ ${balance.toLong()}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (balance >= 0) GreenIncome else RedExpense
            )
        }

        FinancialBlock(label = "支出", amount = totalExpense, color = RedExpense)
    }
}

@Composable
private fun FinancialBlock(label: String, amount: Double, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "NT\$ ${amount.toLong()}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

@Composable
private fun CategoryBar(catTotal: CategoryTotal, maxAmount: Double) {
    val category = ExpenseCategory.fromString(catTotal.category)
    val ratio = if (maxAmount > 0) (catTotal.total / maxAmount).coerceIn(0.0, 1.0) else 0.0

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(category.emoji, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(28.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(category.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text("NT\$ ${catTotal.total.toLong()}", style = MaterialTheme.typography.bodyMedium,
                    color = RedExpense, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            // Progress bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(ratio.toFloat())
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(RedExpense.copy(alpha = 0.7f))
                )
            }
        }
    }
}
