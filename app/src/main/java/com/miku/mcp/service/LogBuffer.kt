package com.miku.mcp.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// Process-wide log store: the tunnel thread writes lines, the UI observes them
object LogBuffer {
    private const val MAX_LINES = 500   // older lines are dropped so memory stays bounded

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    // Safe to call from any thread: update() applies the change atomically
    fun append(line: String) {
        _lines.update { current -> (current + line).takeLast(MAX_LINES) }
    }

    fun clear() {
        _lines.value = emptyList()
    }
}