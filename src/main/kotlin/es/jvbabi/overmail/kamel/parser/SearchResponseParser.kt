package es.jvbabi.overmail.kamel.parser

import es.jvbabi.overmail.kamel.util.substringAfterIgnoreCase

/**
 * Parses untagged `SEARCH` responses, e.g. `* SEARCH 1 2 3`.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3501#section-7.2.5">RFC 3501 - 7.2.5. SEARCH Response</a>
 */
internal object SearchResponseParser {

    /**
     * Returns the numbers contained in [line], or `null` if [line] is not an untagged SEARCH response.
     * Sequence numbers for `SEARCH`, UIDs for `UID SEARCH`: a UID can be above [Int.MAX_VALUE].
     */
    fun parse(line: String): List<Long>? {
        if (!line.uppercase().startsWith("* SEARCH")) return null
        return line
            .substringAfterIgnoreCase("* SEARCH ")
            .split(" ")
            .mapNotNull { it.toLongOrNull() }
    }
}
