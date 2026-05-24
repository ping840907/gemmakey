package com.moneytalks.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.moneytalks.model.ExpenseEntry
import com.moneytalks.model.ExpenseType
import com.moneytalks.model.ParsedExpense
import com.moneytalks.ui.components.ConfirmationDialog
import com.moneytalks.ui.components.MonthNavBar
import com.moneytalks.ui.theme.GreenIncome
import com.moneytalks.ui.theme.RedExpense
import com.moneytalks.utils.DateUtils.toDisplay
import com.moneytalks.utils.DateUtils.toFormattedAmount
import com.moneytalks.viewmodel.HistoryViewModel
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showAddDialog by remember { mutableStateOf(false) }

    if (showAddDialog) {
        ConfirmationDialog(
            parsed = ParsedExpense(),
            rawInput = "",
            onConfirm = { entry -> viewModel.saveEntry(entry); showAddDialog = false },
            onDismiss = { showAddDialog = false }
        )
    }

    LaunchedEffect(state.pendingDelete) {
        state.pendingDelete?.let { entry ->
            val result = snackbarHostState.showSnackbar(
                message = "已刪除「${entry.description}」",
                actionLabel = "復原",
                duration = SnackbarDuration.Short
            )
            when (result) {
                SnackbarResult.ActionPerformed -> viewModel.undoDelete()
                SnackbarResult.Dismissed       -> viewModel.clearPendingDelete()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

            MonthNavBar(
                label = "${state.currentMonth.year}年 ${state.currentMonth.monthValue}月",
                onPrev = viewModel::prevMonth,
                onNext = viewModel::nextMonth,
                canGoNext = state.currentMonth < YearMonth.now()
            )

            if (state.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            MonthlySummaryCard(
                totalExpense = state.totalExpense,
                totalIncome  = state.totalIncome
            )

            if (state.entries.isEmpty() && !state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("尚無記帳記錄", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val grouped = remember(state.entries) {
                    state.entries.groupBy { it.date }.toSortedMap(compareByDescending { it })
                }
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    grouped.forEach { (date, entries) ->
                        item(key = date.toEpochDay()) { DateHeader(date) }
                        items(entries, key = { it.id }) { entry ->
                            SwipeableExpenseRow(
                                entry = entry,
                                onDelete = { viewModel.deleteEntry(entry) }
                            )
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 72.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Default.Add, contentDescription = "手動新增", tint = MaterialTheme.colorScheme.onPrimary)
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun MonthlySummaryCard(totalExpense: Double, totalIncome: Double) {
    val balance = totalIncome - totalExpense
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        SummaryItem(label = "支出", amount = totalExpense, color = RedExpense)
        VerticalDivider(modifier = Modifier.height(36.dp))
        SummaryItem(label = "收入", amount = totalIncome, color = GreenIncome)
        VerticalDivider(modifier = Modifier.height(36.dp))
        SummaryItem(
            label = "結餘",
            amount = balance,
            color = if (balance >= 0) GreenIncome else RedExpense
        )
    }
}

@Composable
private fun SummaryItem(label: String, amount: Double, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(
            text = "NT\$ ${amount.toFormattedAmount()}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun DateHeader(date: LocalDate) {
    Text(
        text = date.toDisplay(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableExpenseRow(entry: ExpenseEntry, onDelete: () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { it * 0.4f }
    )

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) onDelete()
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val color by animateColorAsState(
                targetValue = if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart)
                    MaterialTheme.colorScheme.errorContainer else Color.Transparent,
                label = "swipe_bg"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(color),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "刪除",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(end = 20.dp)
                    )
                }
            }
        }
    ) {
        ExpenseRow(entry = entry, onDelete = onDelete)
    }
}

@Composable
private fun ExpenseRow(entry: ExpenseEntry, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(entry.category.emoji, style = MaterialTheme.typography.titleMedium)
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(entry.description, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(entry.category.displayName, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text(
                text = "${if (entry.type == ExpenseType.INCOME) "+" else "-"}NT\$${entry.amount.toFormattedAmount()}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (entry.type == ExpenseType.INCOME) GreenIncome else RedExpense
            )

            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "刪除",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
