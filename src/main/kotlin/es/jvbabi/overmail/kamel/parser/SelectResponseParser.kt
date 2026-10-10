package es.jvbabi.overmail.kamel.parser

import es.jvbabi.overmail.kamel.FolderState

/**
 * Collects the state of a folder from the untagged responses to its `SELECT`, e.g.
 * `* 172 EXISTS` and `* OK [UIDVALIDITY 3857529045] UIDs valid`.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3501#section-6.3.1">RFC 3501 - 6.3.1. SELECT Command</a>
 */
internal class SelectResponseParser {
    private var uidValidity: Long? = null
    private var uidNext: Long? = null

    /** The number of messages in the folder, `null` if the server did not report it. */
    var exists: Int? = null
        private set

    fun add(line: String) {
        EXISTS_REGEX.find(line)?.let { exists = it.groupValues[1].toIntOrNull() }
        UID_VALIDITY_REGEX.find(line)?.let { uidValidity = it.groupValues[1].toLongOrNull() }
        UID_NEXT_REGEX.find(line)?.let { uidNext = it.groupValues[1].toLongOrNull() }
    }

    /**
     * @throws IllegalStateException if the server left out what every `SELECT` has to report
     */
    fun state(folder: String) = FolderState(
        uidValidity = checkNotNull(uidValidity) { "The server did not report UIDVALIDITY for $folder" },
        uidNext = uidNext,
        exists = checkNotNull(exists) { "The server did not report EXISTS for $folder" },
    )

    companion object {
        private val EXISTS_REGEX = Regex("""^\* (\d+) EXISTS\s*$""", RegexOption.IGNORE_CASE)
        private val UID_VALIDITY_REGEX = Regex("""^\* OK \[UIDVALIDITY (\d+)]""", RegexOption.IGNORE_CASE)
        private val UID_NEXT_REGEX = Regex("""^\* OK \[UIDNEXT (\d+)]""", RegexOption.IGNORE_CASE)
    }
}
