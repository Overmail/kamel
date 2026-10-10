package es.jvbabi.overmail.kamel

/**
 * A change the server reports while a folder is watched, see [IdleFolder.events].
 *
 * Messages are named by their sequence number, the position in the folder, not by their UID.
 */
sealed interface IdleEvent {
    /** The folder now holds [sequenceNumber] messages, which is also the position of the newest one. */
    data class NewMessage(val sequenceNumber: Long) : IdleEvent

    /** The message at [sequenceNumber] was removed; all later ones moved up by one. */
    data class RemovedMessage(val sequenceNumber: Long) : IdleEvent

    data class FlagsChanged(val sequenceNumber: Long, val flags: List<Email.Flag>) : IdleEvent
}
