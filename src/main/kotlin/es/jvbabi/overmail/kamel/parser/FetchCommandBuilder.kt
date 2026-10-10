package es.jvbabi.overmail.kamel.parser

import es.jvbabi.overmail.kamel.FetchRequest
import es.jvbabi.overmail.kamel.FetchRequest.Selection

/**
 * Builds the `FETCH` or `UID FETCH` commands for the selection and the items of [request].
 *
 * One command, unless a set of single messages is too long for one line; none if nothing is
 * selected.
 *
 * @param exists the number of messages in the folder, to leave out the sequence numbers beyond
 * it. `null` sends a selection by sequence number as it is.
 */
internal fun buildFetchCommands(request: FetchRequest, exists: Int? = null): List<String> {
    val items = buildList {
        if (request.flags) add("FLAGS")
        if (request.envelope) add("ENVELOPE")
        if (request.uid) add("UID")
    }.joinToString(" ", prefix = "(", postfix = ")")

    return when (val selection = request.selection) {
        is Selection.IdRange -> when {
            exists == null -> listOf("FETCH ${selection.from}:${selection.to ?: "*"} $items")
            selection.from > exists -> emptyList()
            // Never "*": it is the last mail even if that lies before the range.
            else -> listOf("FETCH ${selection.from}:${minOf(selection.to ?: Long.MAX_VALUE, exists.toLong())} $items")
        }
        is Selection.Ids -> sequenceSets(selection.ids.filter { exists == null || it <= exists }).map { "FETCH $it $items" }
        is Selection.UidRange -> listOf("UID FETCH ${selection.from}:${selection.to ?: "*"} $items")
        is Selection.Uids -> sequenceSets(selection.uids).map { "UID FETCH $it $items" }
    }
}

/**
 * Writes [numbers] as sequence sets, e.g. `4,9,12:15`, each of them at most [maxLength] characters
 * long as far as its ranges allow.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc2683#section-3.2.1.5">RFC 2683 - 3.2.1.5. Long Command Lines</a>
 */
internal fun sequenceSets(numbers: Collection<Long>, maxLength: Int = 900): List<String> {
    val ranges = mutableListOf<String>()
    var start: Long? = null
    var end = 0L
    for (number in numbers.toSortedSet()) {
        if (start != null && number == end + 1) {
            end = number
            continue
        }
        if (start != null) ranges += if (start == end) "$start" else "$start:$end"
        start = number
        end = number
    }
    if (start != null) ranges += if (start == end) "$start" else "$start:$end"

    val sets = mutableListOf<String>()
    val current = StringBuilder()
    for (range in ranges) {
        if (current.isNotEmpty() && current.length + 1 + range.length > maxLength) {
            sets += current.toString()
            current.clear()
        }
        if (current.isNotEmpty()) current.append(',')
        current.append(range)
    }
    if (current.isNotEmpty()) sets += current.toString()
    return sets
}
