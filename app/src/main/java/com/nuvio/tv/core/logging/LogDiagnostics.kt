package com.nuvio.tv.core.logging

fun Throwable.diagnosticSummary(): String {
    val parts = mutableListOf<String>()
    var current: Throwable? = this
    while (current != null && parts.size < 6) {
        val name = current.javaClass.simpleName.ifBlank { current.javaClass.name }
        parts.add("$name: ${current.message ?: "(null)"}")
        current = current.cause
    }
    return parts.joinToString(" <- ")
}
