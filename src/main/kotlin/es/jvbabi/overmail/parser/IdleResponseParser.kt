package es.jvbabi.overmail.parser

import es.jvbabi.overmail.core.Email

internal sealed interface IdleEvent {
    data class NewMessage(val messageIndex: Long) : IdleEvent
    data class RemovedMessage(val messageIndex: Long) : IdleEvent
    data class FlagsChanged(val messageIndex: Long, val flags: List<Email.Flag>) : IdleEvent
}

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
                messageIndex = trimmed.substringAfter("* ").substringBefore(" FETCH").toLong(),
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
