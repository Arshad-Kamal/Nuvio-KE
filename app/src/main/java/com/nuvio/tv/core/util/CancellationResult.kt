package com.nuvio.tv.core.util

import kotlinx.coroutines.CancellationException

/** runCatching around a suspend call must not swallow cancellation (KT-CONC-03). */
internal fun <T> Result<T>.getOrNullUnlessCancelled(): T? = getOrElse { error ->
    if (error is CancellationException) throw error
    null
}
