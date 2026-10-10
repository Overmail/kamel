package es.jvbabi.overmail.kamel.parser

import es.jvbabi.overmail.kamel.Email
import es.jvbabi.overmail.kamel.IdleEvent

/**
 * Parses the untagged responses a server sends while the connection is in `IDLE`.
 */
internal object IdleResponseParser {

    /**
     * Returns the event described by [line], or `null` if [line] carries no event we act on.
     */
    fun parse(line: String): IdleEvent? {
        val trimmed = line.trim()
        return when {
            trimmed.startsWith("* ") && trimmed.endsWith(" EXISTS") -> IdleEvent.NewMessage(
                trimmed.substringAfter("* ").substringBefore(" EXISTS").toLong()
            )

            trimmed.startsWith("* ") && trimmed.endsWith(" EXPUNGE") -> IdleEvent.RemovedMessage(
                trimmed.substringAfter("* ").substringBefore(" EXPUNGE").toLong()
            )

            // e.g. * 5 FETCH (FLAGS (\Seen \Flagged))
            trimmed.startsWith("* ") && "FETCH" in trimmed && "FLAGS" in trimmed -> IdleEvent.FlagsChanged(
                sequenceNumber = trimmed.substringAfter("* ").substringBefore(" FETCH").toLong(),
                flags = trimmed
                    .substringAfter("FLAGS (")
                    .substringBefore(")")
                    .split(" ")
                    .filter { it.isNotBlank() }
                    .map { Email.Flag.fromString(it) }
            )

            else -> null
        }
    }
}
