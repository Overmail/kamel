package es.jvbabi.overmail.parser

import es.jvbabi.overmail.core.FetchRequest

/**
 * Builds the `FETCH` command for the id range [from]`:`[to] and the items requested in [request].
 */
internal fun buildFetchCommand(request: FetchRequest, from: Int, to: Int): String {
    val command = StringBuilder()
    command.append("FETCH $from:$to (")
    if (request.flags) command.append("FLAGS ")
    if (request.envelope) command.append("ENVELOPE ")
    if (request.uid) command.append("UID ")
    if (command.last() == ' ') command.deleteCharAt(command.lastIndex)
    command.append(")")
    return command.toString()
}
