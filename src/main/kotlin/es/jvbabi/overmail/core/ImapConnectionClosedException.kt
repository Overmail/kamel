package es.jvbabi.overmail.core

import java.io.IOException

/**
 * Thrown when a command runs into a connection that is gone: closed on this side, or dropped by
 * the server before it completed its response.
 *
 * An `IOException` rather than a status: there is no tagged completion to report, and a caller
 * reading the empty response as a result would take it for an empty folder or an empty mailbox.
 */
class ImapConnectionClosedException(message: String) : IOException(message)
