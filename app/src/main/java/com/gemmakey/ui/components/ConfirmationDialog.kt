package com.gemmakey.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gemmakey.model.ExpenseCategory
import com.gemmakey.model.ExpenseEntry
import com.gemmakey.model.ExpenseType
import com.gemmakey.model.ParsedExpense
import com.gemmakey.ui.theme.GreenIncome
import com.gemmakey.ui.theme.RedExpense
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmationDialog(
    parsed: ParsedExpense,
    rawInput: String,
    onConfirm: (ExpenseEntry) -> Unit,
    onDismiss: () -> Unit
) {
    var amount               by remember { mutableStateOf(parsed.amount?.toLong()?.toString() ?: "") }
    var description          by remember { mutableStateOf(parsed.description) }
    var type                 by remember { mutableStateOf(parsed.type) }
    var category             by remember { mutableStateOf(parsed.category) }
    var selectedDate         by remember { mutableStateOf(parsed.date) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var showDatePicker       by remember { mutableStateOf(false) }

    // DatePicker state — 以毫秒表示（UTC midnight）
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDate
            .toEpochDay() * 86_400_000L
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        selectedDate = LocalDate.ofEpochDay(millis / 86_400_000L)
                    }
                    showDatePicker = false
                }) { Text("確定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── 標題 ─────────────────────────────────────────────────────
                Text(
                    text = "確認記帳內容",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                // ── 支出 / 收入 toggle ────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ExpenseType.entries.forEach { t ->
                        val selected = type == t
                        val bg = when {
                            selected && t == ExpenseType.EXPENSE -> RedExpense
                            selected && t == ExpenseType.INCOME  -> GreenIncome
                            else -> Color.Transparent
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(bg)
                                .clickable { type = t }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (t == ExpenseType.EXPENSE) "支出" else "收入",
                                color = if (selected) Color.White
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                // ── 金額 ──────────────────────────────────────────────────────
                OutlinedTextField(
                    value = amount,
                    onValueChange = { v -> if (v.all { it.isDigit() || it == '.' }) amount = v },
                    label = { Text("金額 (NT$)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Text("$", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                )

                // ── 日期（可點擊開啟 DatePicker）────────────────────────────────
                val isToday = selectedDate == LocalDate.now()
                val dateLabel = buildString {
                    append(selectedDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日")))
                    if (isToday) append("（今天）")
                }
                Box {
                    OutlinedTextField(
                        value = dateLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("日期") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.CalendarMonth,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingIcon = {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor             = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor           = MaterialTheme.colorScheme.outline,
                            disabledLabelColor            = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledLeadingIconColor      = MaterialTheme.colorScheme.primary,
                            disabledTrailingIconColor     = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        enabled = false
                    )
                    // Transparent overlay ensures the click always reaches the date picker
                    Box(modifier = Modifier.matchParentSize().clickable { showDatePicker = true })
                }

                // ── 類別 ──────────────────────────────────────────────────────
                ExposedDropdownMenuBox(
                    expanded = categoryMenuExpanded,
                    onExpandedChange = { categoryMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = "${category.emoji} ${category.displayName}",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("類別") },
                        trailingIcon = {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                        },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = categoryMenuExpanded,
                        onDismissRequest = { categoryMenuExpanded = false }
                    ) {
                        ExpenseCategory.entries.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text("${cat.emoji} ${cat.displayName}") },
                                onClick = { category = cat; categoryMenuExpanded = false }
                            )
                        }
                    }
                }

                // ── 備註 ──────────────────────────────────────────────────────
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("備註") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // ── 按鈕 ──────────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) { Text("取消") }

                    Button(
                        onClick = {
                            val amt = amount.toDoubleOrNull() ?: return@Button
                            onConfirm(
                                ExpenseEntry(
                                    amount      = amt,
                                    type        = type,
                                    category    = category,
                                    description = description.ifBlank { category.displayName },
                                    date        = selectedDate,   // 使用（已可能被修改的）日期
                                    rawInput    = rawInput
                                )
                            )
                        },
                        enabled = amount.toDoubleOrNull() != null,
                        modifier = Modifier.weight(1f)
                    ) { Text("儲存", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
