package es.jvbabi.overmail.kamel

/**
 * What the server reports about a folder when it is selected.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3501#section-6.3.1">RFC 3501 - 6.3.1. SELECT Command</a>
 */
data class FolderState(
    /**
     * Changes when the UIDs of the folder were reassigned. UIDs stored under another value do not
     * belong to the messages they used to.
     */
    val uidValidity: Long,
    /** The UID the next new message will get at least. `null` if the server does not predict it. */
    val uidNext: Long?,
    /** The number of messages in the folder. */
    val exists: Int,
)
