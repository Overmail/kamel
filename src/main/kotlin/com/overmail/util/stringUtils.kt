package com.overmail.util

import java.security.MessageDigest

internal fun String.substringAfterIgnoreCase(delimiter: String): String {
    if (this.lowercase().startsWith(delimiter.lowercase())) return this.drop(delimiter.length)
    return this
}

internal fun String.sha1(): String {
    val bytes = this.toByteArray(Charsets.UTF_8)
    val md = MessageDigest.getInstance("SHA-1")
    val digest = md.digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}