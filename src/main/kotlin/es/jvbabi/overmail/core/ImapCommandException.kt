package es.jvbabi.overmail.core

/**
 * Thrown when the server completes a command with a tagged `NO` or `BAD` response.
 *
 * @param command the command that was sent, including its tag
 * @param response the tagged completion line returned by the server
 */
class ImapCommandException(
    val command: String,
    val response: String
) : Exception("Command failed: $command -> $response")
