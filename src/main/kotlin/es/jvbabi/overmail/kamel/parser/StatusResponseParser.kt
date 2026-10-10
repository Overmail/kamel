package es.jvbabi.overmail.kamel.parser

import es.jvbabi.overmail.kamel.FolderState

/**
 * Parses the untagged response to `STATUS`, e.g.
 * `* STATUS "INBOX" (MESSAGES 231 UIDNEXT 44292 UIDVALIDITY 3857529045)`.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3501#section-7.2.4">RFC 3501 - 7.2.4. STATUS Response</a>
 */
internal object StatusResponseParser {

    /**
     * Returns the state [response] reports, or `null` if it is not a `STATUS` response.
     *
     * @param response the items of the response; more than one if the server sent the folder name
     * as a literal
     * @throws IllegalStateException if the response lacks `UIDVALIDITY` or `MESSAGES`
     */
    fun parse(response: List<String>): FolderState? {
        if (!response.first().startsWith("* STATUS ", ignoreCase = true)) return null
        // The attributes are the last thing in the response, so a folder name with a paren in it
        // cannot be taken for them.
        val attributes = ATTRIBUTES_REGEX.find(response.last())?.groupValues?.get(1) ?: return null
        val values = attributes.trim().split(Regex("\\s+")).chunked(2)
            .filter { it.size == 2 }
            .associate { (name, value) -> name.uppercase() to value.toLongOrNull() }

        return FolderState(
            uidValidity = checkNotNull(values["UIDVALIDITY"]) { "The server did not report UIDVALIDITY: ${response.last()}" },
            uidNext = values["UIDNEXT"],
            exists = checkNotNull(values["MESSAGES"]) { "The server did not report MESSAGES: ${response.last()}" }.toInt(),
        )
    }

    private val ATTRIBUTES_REGEX = Regex("""\(([^()]*)\)\s*$""")
}
