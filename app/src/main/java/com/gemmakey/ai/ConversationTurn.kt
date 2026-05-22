package com.gemmakey.ai

/** One complete exchange used for cross-backend context sharing. */
data class ConversationTurn(
    val userText: String,      // raw user input (without RAG/history prefixes)
    val assistantText: String  // assistant reply text (not tool-call preview)
)
