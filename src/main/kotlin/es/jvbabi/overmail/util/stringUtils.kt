package es.jvbabi.overmail.util

import java.security.MessageDigest

internal fun String.substringAfterIgnoreCase(delimiter: String): String {
    if (this.lowercase().startsWith(delimiter.lowercase())) return this.drop(delimiter.length)
    return this
}

/**
 * Wraps the string in an IMAP quoted string, escaping backslashes and quotes. Required for every
 * mailbox name, since unquoted names with a space are read as two command arguments.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3501#section-4.3">RFC 3501 - 4.3. String</a>
 */
internal fun String.quoteImap(): String {
    val escaped = this.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}

internal fun String.sha1(): String {
    val bytes = this.toByteArray(Charsets.UTF_8)
    val md = MessageDigest.getInstance("SHA-1")
    val digest = md.digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}