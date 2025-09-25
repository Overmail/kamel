package com.overmail.util

sealed class Optional<T: Any?> {
    class Empty<T: Any?>(): Optional<T>()
    data class Set<T: Any?>(val value: T): Optional<T>()

    fun getOrNull(): T? = when (this) {
        is Empty -> null
        is Set -> value
    }
}