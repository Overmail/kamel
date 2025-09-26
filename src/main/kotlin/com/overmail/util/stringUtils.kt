package com.overmail.util

internal fun String.substringAfterIgnoreCasing(delimiter: String): String {
    if (this.lowercase().startsWith(delimiter.lowercase())) return this.drop(delimiter.length)
    return this
}