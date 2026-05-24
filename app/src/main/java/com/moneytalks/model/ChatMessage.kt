package com.moneytalks.model

import android.graphics.Bitmap

enum class MessageRole { USER, ASSISTANT, SYSTEM }

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val role: MessageRole,
    val text: String,
    val imageBitmap: Bitmap? = null,
    val isLoading: Boolean = false,
    val relatedEntry: ExpenseEntry? = null
)
