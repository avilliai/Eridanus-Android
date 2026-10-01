package com.eridanus.assistant.ui

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val text: String,
    val imageUrls: List<String> = emptyList(),
    val localImageBitmap: String? = null,
    val isFromUser: Boolean,
    val timeStr: String = ""
)