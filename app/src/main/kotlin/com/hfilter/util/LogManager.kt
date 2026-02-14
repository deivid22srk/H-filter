package com.hfilter.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DnsLogEntry(
    val id: Long = System.nanoTime(),
    val timestamp: Long = System.currentTimeMillis(),
    val domain: String,
    val blocked: Boolean
)

object LogManager {
    private val _logs = MutableStateFlow<List<DnsLogEntry>>(emptyList())
    val logs: StateFlow<List<DnsLogEntry>> = _logs

    fun addLog(domain: String, blocked: Boolean) {
        val entry = DnsLogEntry(domain = domain, blocked = blocked)
        val current = _logs.value.toMutableList()
        current.add(0, entry)
        if (current.size > 200) {
            current.removeAt(current.size - 1)
        }
        _logs.value = current
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
