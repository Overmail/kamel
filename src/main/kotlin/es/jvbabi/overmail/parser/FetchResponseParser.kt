package es.jvbabi.overmail.parser

import es.jvbabi.overmail.core.Email
import es.jvbabi.overmail.core.EmailUser
import es.jvbabi.overmail.util.MimeUtility
import es.jvbabi.overmail.util.sha1
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.toInstant
import kotlin.time.Instant

/**
 * The fields of the envelope structure are in the following order: date, subject, from, sender,
 * reply-to, to, cc, bcc, in-reply-to, and message-id.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3501#section-2.3.5">RFC 3501 - 2.3.5. ENVELOPE</a>
 */
internal data class ParsedEnvelope(
    val sentAt: Instant,
    val subject: String?,
    val from: Set<EmailUser>,
    val senders: Set<EmailUser>,
    val replyTo: Set<EmailUser>,
    val to: Set<EmailUser>,
    val cc: Set<EmailUser>,
    val bcc: Set<EmailUser>,
    val inReplyTo: String?,
    val messageId: String
)

/**
 * A single untagged `FETCH` response. Items that were not part of the response are `null`.
 */
internal data class ParsedFetchItem(
    val flags: Set<Email.Flag>? = null,
    val uid: Long? = null,
    val envelope: ParsedEnvelope? = null
)

/**
 * Parses a single untagged `FETCH` response, e.g.
 * `* 1 FETCH (FLAGS (\Seen) UID 42 ENVELOPE (...))`.
 *
 * A parser instance handles exactly one response line (plus its literal continuation lines) and keeps
 * [line] and [remaining] around afterwards so callers can point at the position a failure occurred at.
 *
 * @param nextLine supplies the next response line, used when the server sends a literal (`{42}`).
 */
internal class FetchResponseParser(
    private val nextLine: suspend () -> String = { error("No continuation line available") }
) {
    /** The line currently being parsed. Changes when a literal continuation line is read. */
    var line: String = ""
        private set

    /** The not yet consumed rest of [line]. */
    var remaining: String = ""
        private set

    private var flags: Set<Email.Flag>? = null
    private var uid: Long? = null
    private var envelope: ParsedEnvelope? = null

    /** Everything parsed so far, for error reporting on a partially parsed response. */
    fun partialResult() = ParsedFetchItem(flags = flags, uid = uid, envelope = envelope)

    suspend fun parse(rawLine: String): ParsedFetchItem {
        line = rawLine
        remaining = rawLine
            .substringAfter("(")
            .let { if (it.endsWith("}")) it else it.substringBeforeLast(")") }
            .trim()

        while (remaining.isNotEmpty()) {
            if (remaining.startsWith("FLAGS")) {
                flags = parseFlags()
                continue
            }

            if (remaining.startsWith("UID")) {
                uid = parseUid()
                continue
            }

            if (remaining.startsWith("ENVELOPE")) {
                envelope = parseEnvelope()
                continue
            }

            if (remaining == ")") break

            throw IllegalArgumentException("Could not parse FETCH response")
        }

        return partialResult()
    }

    private fun parseFlags(): Set<Email.Flag> {
        remaining = remaining.removePrefix("FLAGS (")

        var flags = emptySet<Email.Flag>()
        while (!remaining.startsWith(")")) {
            var currentFlag = ""
            while (remaining.first() != ' ' && remaining.first() != ')') {
                currentFlag += remaining.first()
                remaining = remaining.drop(1)
            }
            remaining = remaining.trimStart()
            if (currentFlag.isBlank()) continue
            flags = flags + Email.Flag.fromString(currentFlag)
        }

        remaining = remaining
            .substringAfter(")")
            .trim()
        return flags
    }

    private fun parseUid(): Long {
        remaining = remaining
            .removePrefix("UID ")
            .trim()

        val uid = remaining.substringBefore(" ").toLongOrNull()
            ?: throw IllegalArgumentException("Could not parse UID in $line")

        // UID may be the last item in the response; without an explicit missing
        // delimiter value substringAfter would return the UID itself and fail to parse.
        remaining = remaining
            .substringAfter(" ", "").trim()
        return uid
    }

    private suspend fun parseEnvelope(): ParsedEnvelope {
        remaining = remaining
            .removePrefix("ENVELOPE (")
            .trim()

        val rawDate = SIMPLE_QUOTE_REGEX.find(remaining)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Could not parse date in $remaining (quoteRegex)")

        val rawDateWithOffset = DATE_WITH_OFFSET_REGEX.find(remaining)
            ?: throw IllegalArgumentException("Could not parse date in $remaining")

        val dayOfMonth = rawDateWithOffset.groups["dayofmonth"]!!.value.toInt()
        val month = Month(MonthNames.ENGLISH_ABBREVIATED.names.indexOf(rawDateWithOffset.groups["month"]!!.value) + 1)
        val year = rawDateWithOffset.groups["year"]!!.value.toInt()
        val hour = rawDateWithOffset.groups["hour"]!!.value.toInt()
        val minute = rawDateWithOffset.groups["minute"]!!.value.toInt()
        val second = rawDateWithOffset.groups["second"]!!.value.toInt()
        val offsetRaw = rawDateWithOffset.groups["offset"]!!.value

        val offset = when {
            offsetRaw == "UT" -> UtcOffset.ZERO
            offsetRaw == "GMT" -> UtcOffset.ZERO
            offsetRaw.startsWith("+") || offsetRaw.startsWith("-") -> {
                val isNegative = offsetRaw.startsWith('-')
                // UtcOffset requires hours and minutes to carry the same sign.
                val offsetHours = offsetRaw.drop(1).take(2).toInt().let { if (isNegative) -it else it }
                val offsetMinutes = offsetRaw.drop(3).take(2).toInt().let { if (isNegative) -it else it }
                UtcOffset(offsetHours, offsetMinutes)
            }

            else -> throw IllegalArgumentException("Invalid offset: $offsetRaw")
        }

        remaining = remaining.removePrefix("\"$rawDate\" ")
        val date = LocalDateTime(
            day = dayOfMonth,
            month = month,
            year = year,
            hour = hour,
            minute = minute,
            second = second,
        )
        val sentAt = date.toInstant(offset)

        val subjectRaw = if (remaining.startsWith("{")) {
            remaining = remaining.drop(1)
            val followingBytesCount = remaining.substringBefore("}").toInt()
            readLiteral(followingBytesCount)
        } else {
            val subjectMatch = SUBJECT_REGEX.find(remaining)
                ?: throw IllegalArgumentException("Could not parse subject")

            remaining = remaining.removePrefix(subjectMatch.value)
            subjectMatch.groups[1]?.value
        }

        val subject = subjectRaw
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")

        val from = parseEmailField()
        val senders = parseEmailField()
        val replyTo = parseEmailField()
        val to = parseEmailField()
        val cc = parseEmailField()
        val bcc = parseEmailField()

        val inReplyTo = if (remaining.startsWith("NIL ")) {
            remaining = remaining.substringAfter("NIL ")
            null
        } else {
            remaining = remaining.removePrefix("\"")
            val inReplyTo = remaining.substringBefore("\"")
            remaining = remaining
                .removePrefix(inReplyTo)
                .removePrefix("\"")
                .removePrefix(" ")
            inReplyTo
        }

        val messageId = if (remaining.startsWith("NIL")) {
            remaining = remaining
                .substringAfter("NIL")
                .trimStart()
            val fingerprint = buildString {
                append(sentAt.toEpochMilliseconds())
                append(subject)
                append(inReplyTo)
                append(from.map { it.address }.sorted().distinct().joinToString(""))
            }
            "overmail-generated-id:" + fingerprint.sha1()
        } else {
            val messageIdRaw = SIMPLE_QUOTE_REGEX.find(remaining)?.value!!
            remaining = remaining.removePrefix(messageIdRaw)
            messageIdRaw
                .removePrefix("\"<")
                .removeSuffix(">\"")
        }

        remaining = remaining
            .removePrefix(")")
            .trim()

        return ParsedEnvelope(
            sentAt = sentAt,
            subject = subject?.let { MimeUtility.decode(it) },
            from = from,
            senders = senders,
            replyTo = replyTo,
            to = to,
            cc = cc,
            bcc = bcc,
            inReplyTo = inReplyTo,
            messageId = messageId
        )
    }

    /**
     * Reads a literal of [length] characters from the continuation lines. A folded header keeps its
     * line breaks inside the literal, so the literal may span more than one line; the rest of the
     * last line stays in [remaining].
     */
    private suspend fun readLiteral(length: Int): String {
        val literal = StringBuilder()
        while (true) {
            line = nextLine()
            val missing = length - literal.length
            if (line.length >= missing) {
                literal.append(line, 0, missing)
                remaining = line.drop(missing).removePrefix(" ")
                break
            }
            // The line break that split the literal is part of it, readLine() has stripped it.
            literal.append(line).append("\r\n")
        }

        // A literal ending on a line break leaves the rest of the envelope on the following line.
        if (remaining.isEmpty()) {
            line = nextLine()
            remaining = line
        }

        return literal.toString()
    }

    /**
     * Consumes one address list (`NIL` or `((name adl mailbox host) ...)`) from [remaining].
     */
    private fun parseEmailField(): Set<EmailUser> {
        remaining = remaining.dropWhile { it == ' ' }
        if (remaining.startsWith("NIL")) {
            remaining = remaining.removePrefix("NIL").dropWhile { it == ' ' }
            return emptySet()
        }

        val raw = remaining.substringBefore("))") + "))"
        remaining = remaining.removePrefix(raw).dropWhile { it == ' ' }
        return parseEmailUsers(raw)
    }

    companion object {
        /** matches "content" */
        private val SIMPLE_QUOTE_REGEX = Regex("\"([^\"]*)\"")

        private val DATE_WITH_OFFSET_REGEX =
            Regex("((?<dayofweek>(Mon|Tue|Wed|Thu|Fri|Sat|Sun))(,)? )?(?<dayofmonth>\\d{1,2}) (?<month>(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)) (?<year>\\d{4}) (?<hour>\\d{2}):(?<minute>\\d{2}):(?<second>\\d{2}) (?<offset>[+-]\\d{4}|UT|GMT)")

        private val SUBJECT_REGEX = Regex("^(?:\"((?:[^\"\\\\]|\\\\.)*)\"|NIL) ")

        private val EMAIL_USER_REGEX = Regex("\\((\"([^\"]+)\"|NIL)\\s+NIL\\s+\"([^\"]+)\"\\s+\"([^\"]+)\"\\)")

        /**
         * Parses a parenthesized list of address structures, e.g. `(("Jane" NIL "jane" "example.org"))`.
         */
        fun parseEmailUsers(raw: String): Set<EmailUser> {
            if (!raw.startsWith("((")) throw IllegalArgumentException("Not a valid email user list")

            var parenCount = 0
            val content = buildString {
                for (char in raw) {
                    if (char == '(') parenCount++
                    if (char == ')') parenCount--
                    append(char)
                    if (parenCount == 0) break
                }
            }

            return EMAIL_USER_REGEX.findAll(content).map { match ->
                val (name, _, mailbox, host) = match.destructured
                EmailUser("$mailbox@$host", name.takeIf { it != "NIL" })
            }.toSet()
        }
    }
}
