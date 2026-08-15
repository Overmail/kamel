package es.jvbabi.overmail.parser

import es.jvbabi.overmail.util.substringAfterIgnoreCase

/**
 * Parses untagged `SEARCH` responses, e.g. `* SEARCH 1 2 3`.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3501#section-7.2.5">RFC 3501 - 7.2.5. SEARCH Response</a>
 */
internal object SearchResponseParser {

    /**
     * Returns the message ids contained in [line], or `null` if [line] is not an untagged SEARCH response.
     */
    fun parseIds(line: String): List<Int>? {
        if (!line.uppercase().startsWith("* SEARCH")) return null
        return line
            .substringAfterIgnoreCase("* SEARCH ")
            .split(" ")
            .mapNotNull { it.toIntOrNull() }
    }
}
