package com.nuvio.tv.core.stream

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stream URLs the runtime-mismatch guard rejected ("Pick another stream", Spec 42 P2 / F-2) during
 * this app process. Auto-select skips them, so a wrong file the guard caught is not picked again
 * for the same title. In memory only: the set is tiny (one entry per rejection) and after a
 * restart the guard still catches the file.
 */
@Singleton
class RejectedStreamUrls @Inject constructor() {
    private val urls: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun add(url: String) {
        if (url.isNotBlank()) urls += url
    }

    operator fun contains(url: String): Boolean = url in urls

    fun snapshot(): Set<String> = urls.toSet()
}
