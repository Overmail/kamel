package es.jvbabi.overmail.parser

/**
 * Completion status of a tagged IMAP response.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3501#section-7.1">RFC 3501 - 7.1. Server Responses - Status Responses</a>
 */
internal enum class ImapStatus {
    OK,
    NO,
    BAD
}

/**
 * Detects the tagged completion line of a command, e.g. `A001 OK SELECT completed`.
 */
internal object TaggedResponseParser {

    /**
     * Returns the status of [line] if it is the tagged completion of [tag], or `null` if [line]
     * belongs to the ongoing response.
     */
    fun parse(line: String, tag: String): ImapStatus? {
        if (!line.startsWith("$tag ", ignoreCase = true)) return null
        val status = line.drop(tag.length + 1).trimStart().substringBefore(' ').uppercase()
        return ImapStatus.entries.firstOrNull { it.name == status }
    }
}
