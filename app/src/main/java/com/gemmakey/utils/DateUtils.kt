package com.gemmakey.utils

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object DateUtils {
    private val display = DateTimeFormatter.ofPattern("MM/dd")
    private val full    = DateTimeFormatter.ofPattern("yyyy/MM/dd")

    fun LocalDate.toDisplay(): String = when {
        this == LocalDate.now()           -> "今天"
        this == LocalDate.now().minusDays(1) -> "昨天"
        else -> format(display)
    }

    fun LocalDate.toFull(): String = format(full)

    fun LocalDate.weekdayChinese(): String =
        dayOfWeek.getDisplayName(TextStyle.FULL, Locale.TRADITIONAL_CHINESE)

    fun Double.toFormattedAmount(): String = String.format("%,d", this.toLong())
}
