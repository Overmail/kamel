package es.jvbabi.overmail.kamel.parser

import es.jvbabi.overmail.kamel.ImapCommand
import es.jvbabi.overmail.kamel.SearchCriterion
import kotlinx.datetime.LocalDate

/**
 * Builds the `UID SEARCH` command for [criterion]. Strings that cannot be quoted go out as
 * literals; as soon as one of them is not ASCII, the command names `CHARSET UTF-8`.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3501#section-6.4.4">RFC 3501 - 6.4.4. SEARCH Command</a>
 */
internal fun buildSearchCommand(criterion: SearchCriterion): ImapCommand = ImapCommand.build {
    text("UID SEARCH ")
    if (criterion.strings().any { it.any { char -> char.code > 0x7f } }) text("CHARSET UTF-8 ")
    when {
        // The criteria of the top level need no parentheses: a search takes a list of keys.
        criterion is SearchCriterion.AllOf && criterion.criteria.isNotEmpty() ->
            criterion.criteria.forEachIndexed { index, it ->
                if (index > 0) text(" ")
                key(it)
            }
        else -> key(criterion)
    }
}

private fun ImapCommand.Builder.key(criterion: SearchCriterion) {
    when (criterion) {
        is SearchCriterion.Subject -> text("SUBJECT ").string(criterion.text)
        is SearchCriterion.Header -> text("HEADER ").string(criterion.field).text(" ").string(criterion.text)
        is SearchCriterion.SentOn -> text("SENTON ${criterion.date.imap()}")
        is SearchCriterion.SentSince -> text("SENTSINCE ${criterion.date.imap()}")
        is SearchCriterion.SentBefore -> text("SENTBEFORE ${criterion.date.imap()}")
        is SearchCriterion.ReceivedOn -> text("ON ${criterion.date.imap()}")
        is SearchCriterion.ReceivedSince -> text("SINCE ${criterion.date.imap()}")
        is SearchCriterion.ReceivedBefore -> text("BEFORE ${criterion.date.imap()}")
        is SearchCriterion.AllOf -> when (criterion.criteria.size) {
            0 -> text("ALL")
            1 -> key(criterion.criteria.single())
            else -> {
                text("(")
                criterion.criteria.forEachIndexed { index, it ->
                    if (index > 0) text(" ")
                    key(it)
                }
                text(")")
            }
        }
        // OR takes two keys, so more than two are nested: OR a OR b c.
        is SearchCriterion.AnyOf -> {
            criterion.criteria.dropLast(1).forEach {
                text("OR ")
                key(it)
                text(" ")
            }
            key(criterion.criteria.last())
        }
        is SearchCriterion.Not -> {
            text("NOT ")
            key(criterion.criterion)
        }
    }
}

/** Every string [this] sends, to tell whether the command needs a charset. */
private fun SearchCriterion.strings(): List<String> = when (this) {
    is SearchCriterion.Subject -> listOf(text)
    is SearchCriterion.Header -> listOf(field, text)
    is SearchCriterion.AllOf -> criteria.flatMap { it.strings() }
    is SearchCriterion.AnyOf -> criteria.flatMap { it.strings() }
    is SearchCriterion.Not -> criterion.strings()
    else -> emptyList()
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** A date as SEARCH takes it, e.g. `5-May-2025`. */
private fun LocalDate.imap(): String = "$day-${MONTHS[month.ordinal]}-$year"
