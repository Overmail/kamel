package com.overmail.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

context(scope: CoroutineScope)
suspend fun <T, R> Collection<T>.mapAsync(block: suspend (T) -> R): Collection<R> {
    return this.map { scope.async { block(it) } }.awaitAll()
}