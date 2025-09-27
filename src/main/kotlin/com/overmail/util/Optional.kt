package com.overmail.util

sealed class Optional<T: Any?> {
    class Empty<T: Any?>(): Optional<T>() {
        override fun toString(): String {
            return "Empty()"
        }
    }
    data class Set<T: Any?>(val value: T): Optional<T>() {
        override fun toString(): String {
            return "Set(value=$value)"
        }
    }

    fun getOrNull(): T? = when (this) {
        is Empty -> null
        is Set -> value
    }
}