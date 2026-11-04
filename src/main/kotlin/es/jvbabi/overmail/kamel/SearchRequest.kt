package es.jvbabi.overmail.kamel

import es.jvbabi.overmail.kamel.util.MimeUtility
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.io.ByteArrayInputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import kotlin.time.Instant

@DslMarker
annotation class SearchDsl

/**
 * The criteria of a search, see [MailFolder.search]. Every criterion added in a block has to
 * match: they are combined with AND. [or] and [not] combine them in other ways.
 *
 * Strings match where the field contains them, ignoring case, as IMAP defines it. Dates are whole
 * days: time and time zone are not looked at. A caller who matches to the second filters the hits
 * afterwards, with the fields of [MailFolder.getMails].
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3501#section-6.4.4">RFC 3501 - 6.4.4. SEARCH Command</a>
 */
@SearchDsl
open class SearchCriteria internal constructor() {
    private val criteria = mutableListOf<SearchCriterion>()

    /** What was added so far, in that order. */
    internal fun criteria(): List<SearchCriterion> = criteria.toList()

    /** Adds [criterion] as it is, e.g. one built elsewhere. */
    fun add(criterion: SearchCriterion) {
        criteria += criterion
    }

    /** The `Subject` contains [text]. */
    fun subject(text: String) = add(SearchCriterion.Subject(text))

    /**
     * A header [field] contains [text]. An empty [text] matches every message that has the header
     * at all.
     */
    fun header(field: String, text: String) = add(SearchCriterion.Header(field, text))

    /**
     * The `Message-ID` header contains [messageId]. Pass it with or without the angle brackets
     * around it: a search for `x@example.org` finds `<x@example.org>` as well.
     */
    fun messageId(messageId: String) = header("Message-ID", messageId)

    /** The `Date` header names [date], in the time zone it is written in. */
    fun sentOn(date: LocalDate) = add(SearchCriterion.SentOn(date))

    /** The `Date` header names [date] or a later day. */
    fun sentSince(date: LocalDate) = add(SearchCriterion.SentSince(date))

    /** The `Date` header names a day before [date]. */
    fun sentBefore(date: LocalDate) = add(SearchCriterion.SentBefore(date))

    /**
     * The message arrived in the folder on [date], as the server keeps it: the internal date of
     * IMAP, which is the day of delivery for most mails, or the day it was copied or appended.
     */
    fun receivedOn(date: LocalDate) = add(SearchCriterion.ReceivedOn(date))

    /** The message arrived on [date] or later, see [receivedOn]. */
    fun receivedSince(date: LocalDate) = add(SearchCriterion.ReceivedSince(date))

    /** The message arrived before [date], see [receivedOn]. */
    fun receivedBefore(date: LocalDate) = add(SearchCriterion.ReceivedBefore(date))

    /**
     * At least one of the criteria added in [block] matches.
     *
     * @throws IllegalArgumentException if [block] adds none
     */
    fun or(block: SearchCriteria.() -> Unit) = add(SearchCriterion.AnyOf(SearchCriteria().apply(block).criteria()))

    /**
     * All of the criteria added in [block] match. Only needed inside [or], where it groups criteria
     * that have to match together.
     */
    fun and(block: SearchCriteria.() -> Unit) = add(SearchCriterion.AllOf(SearchCriteria().apply(block).criteria()))

    /**
     * The criteria added in [block] do not match all together. For "none of them", put an [or]
     * inside: `not { or { … } }`.
     *
     * @throws IllegalArgumentException if [block] adds none
     */
    fun not(block: SearchCriteria.() -> Unit) {
        val criteria = SearchCriteria().apply(block).criteria()
        require(criteria.isNotEmpty()) { "not { } needs at least one criterion" }
        add(SearchCriterion.Not(criteria.singleOrNull() ?: SearchCriterion.AllOf(criteria)))
    }
}

/**
 * What [MailFolder.search] is configured with. Without any criterion it finds every message of the
 * folder.
 */
class SearchRequest : SearchCriteria() {
    /** All criteria of the request, combined with AND. */
    val criterion: SearchCriterion
        get() = criteria().singleOrNull() ?: SearchCriterion.AllOf(criteria())
}

/**
 * One criterion of a search, as [SearchRequest] builds it. A [MailFolder] of your own can decide
 * with [matches] which of its messages a search finds.
 */
sealed interface SearchCriterion {

    /**
     * Whether the message with the source [source] matches, as a server would decide it.
     *
     * @param receivedAt when the message arrived in the folder, the internal date of IMAP
     * @param timeZone the time zone of the server, which the day of [receivedAt] depends on
     * @throws IllegalArgumentException if [source] cannot be read as a message
     */
    fun matches(source: ByteArray, receivedAt: Instant, timeZone: TimeZone = TimeZone.UTC): Boolean =
        evaluate(SearchableMessage(source, receivedAt.toLocalDateTime(timeZone).date))

    /** [matches] on a message that is read already, so a tree of criteria reads its source once. */
    private fun evaluate(message: SearchableMessage): Boolean = when (this) {
        is Subject -> message.headers("Subject").any { it.contains(text, ignoreCase = true) }
        is Header -> message.headers(field).any { it.contains(text, ignoreCase = true) }
        is SentOn -> message.sentOn == date
        is SentSince -> message.sentOn?.let { it >= date } ?: false
        is SentBefore -> message.sentOn?.let { it < date } ?: false
        is ReceivedOn -> message.receivedOn == date
        is ReceivedSince -> message.receivedOn >= date
        is ReceivedBefore -> message.receivedOn < date
        is AllOf -> criteria.all { it.evaluate(message) }
        is AnyOf -> criteria.any { it.evaluate(message) }
        is Not -> !criterion.evaluate(message)
    }

    /** `SUBJECT`: the subject contains [text]. */
    data class Subject(val text: String) : SearchCriterion {
        init {
            requireSendable(text)
        }
    }

    /** `HEADER`: the header [field] contains [text]. */
    data class Header(val field: String, val text: String) : SearchCriterion {
        init {
            require(field.isNotEmpty() && field.all { it in '!'..'~' && it != ':' }) {
                "Not the name of a header field: \"$field\""
            }
            requireSendable(text)
        }
    }

    /** `SENTON`: the `Date` header names [date]. */
    data class SentOn(val date: LocalDate) : SearchCriterion

    /** `SENTSINCE`: the `Date` header names [date] or a later day. */
    data class SentSince(val date: LocalDate) : SearchCriterion

    /** `SENTBEFORE`: the `Date` header names a day before [date]. */
    data class SentBefore(val date: LocalDate) : SearchCriterion

    /** `ON`: the internal date is [date]. */
    data class ReceivedOn(val date: LocalDate) : SearchCriterion

    /** `SINCE`: the internal date is [date] or a later day. */
    data class ReceivedSince(val date: LocalDate) : SearchCriterion

    /** `BEFORE`: the internal date is a day before [date]. */
    data class ReceivedBefore(val date: LocalDate) : SearchCriterion

    /** Every one of [criteria] matches. Without any, every message matches. */
    data class AllOf(val criteria: List<SearchCriterion>) : SearchCriterion

    /** `OR`: at least one of [criteria] matches. */
    data class AnyOf(val criteria: List<SearchCriterion>) : SearchCriterion {
        init {
            require(criteria.isNotEmpty()) { "or { } needs at least one criterion" }
        }
    }

    /** `NOT`: [criterion] does not match. */
    data class Not(val criterion: SearchCriterion) : SearchCriterion

    private companion object {
        /** A NUL byte cannot be sent, not even in a literal. */
        fun requireSendable(text: String) = require('\u0000' !in text) { "A search string cannot contain NUL" }
    }
}

/**
 * The parts of a message a [SearchCriterion] looks at, read from its source once.
 */
private class SearchableMessage(source: ByteArray, val receivedOn: LocalDate) {
    private val message = try {
        MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(source))
    } catch (e: MessagingException) {
        throw IllegalArgumentException("Could not read the message source", e)
    }

    /** The decoded values of every header named [name], ignoring case. */
    fun headers(name: String): List<String> =
        runCatching { message.getHeader(name) }.getOrNull().orEmpty().map(MimeUtility::decode)

    /** The day the `Date` header names, in its own time zone. */
    val sentOn: LocalDate? by lazy {
        val date = runCatching { message.getHeader("Date", null) }.getOrNull() ?: return@lazy null
        val cleaned = date.replace(COMMENT, " ").replace(WHITESPACE, " ").trim()
        runCatching { ZonedDateTime.parse(cleaned, DateTimeFormatter.RFC_1123_DATE_TIME) }.getOrNull()
            ?.let { LocalDate(it.year, it.monthValue, it.dayOfMonth) }
            // Zone names like "EST" that the formatter does not know: jakarta reads them, without the zone.
            ?: runCatching { message.sentDate }.getOrNull()
                ?.let { Instant.fromEpochMilliseconds(it.time).toLocalDateTime(TimeZone.UTC).date }
    }

    private companion object {
        val COMMENT = Regex("""\([^)]*\)""")
        val WHITESPACE = Regex("""\s+""")
    }
}
