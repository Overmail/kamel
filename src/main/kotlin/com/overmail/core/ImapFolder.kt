package com.overmail.core

import com.overmail.util.MimeUtility
import com.overmail.util.Optional
import com.overmail.util.substringAfterIgnoreCasing
import io.ktor.network.sockets.*
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.toInstant
import org.slf4j.LoggerFactory

class ImapFolder(
    private val client: ImapClient,
    val path: List<String>,
    val delimiter: String,
    val specialType: SpecialType?
) {
    val fullName = this@ImapFolder.path.joinToString(delimiter)
    val name = path.lastOrNull() ?: fullName

    enum class SpecialType {
        INBOX,
        SENT,
        SPAM,
        TRASH,
        DRAFTS
    }

    private val logger = LoggerFactory.getLogger("ImapFolder/$fullName")
    private var socketInstance: SocketInstance? = null

    override fun toString(): String {
        return "Folder(path=$path, specialType=$specialType)"
    }

    /**
     * Returns all mail ids in this folder. This is a relative identifier, always starting with 0.
     */
    suspend fun getMailIds(): List<Int> {
        val response = getSocketInstance().runCommand("SEARCH ALL").response.await().lines()
        val ids = mutableListOf<Int>()
        response.forEach { line ->
            if (line.uppercase().startsWith("OK SEARCH")) return ids
            else if (line.uppercase().startsWith("SEARCH")) {
                line
                    .substringAfterIgnoreCasing("SEARCH ")
                    .split(" ")
                    .mapNotNull { it.toIntOrNull() }
                    .forEach { ids.add(it) }
            } else logger.error("Could not get mail ids: $line")
        }
        return emptyList()
    }

    private suspend fun getSocketInstance(): SocketInstance {
        if (socketInstance == null || socketInstance?.socket?.isClosed == true) socketInstance =
            client.createNewSocket().apply {
                this.login(client.username, client.password)
                runCommand("SELECT \"${path.joinToString(delimiter)}\"").response.await()
            }
        return socketInstance!!
    }

    suspend fun getMails(config: FetchRequest.() -> Unit): List<Email> {
        val config = FetchRequest().apply(config)
        val mailIds = getMailIds()
        if (mailIds.isEmpty()) return emptyList()

        val emails = mutableListOf<Email>()

        val to = config.to.let { to ->
            if (to == Long.MAX_VALUE) "*" else to.toString()
        }

        val command = StringBuilder()
        command.append("FETCH ${config.from}:${to} (")
        if (config.flags) command.append("FLAGS ")
        if (config.envelope) command.append("ENVELOPE ")
        if (config.uid) command.append("UID ")
        if (command.last() == ' ') command.deleteCharAt(command.lastIndex)
        command.append(")")

        val response = getSocketInstance().runCommand(command.toString()).response.await()
        response.lines().forEach { line ->
            if (line.uppercase().startsWith("OK FETCH")) return@forEach

            val email = Email(client = this.client)
            var consuming = line
                .substringAfter("(")
                .substringBeforeLast(")")
                .trim()

            try {
                while (consuming.isNotEmpty()) {
                    if (consuming.startsWith("FLAGS")) {
                        consuming = consuming
                            .removePrefix("FLAGS (")

                        while (!consuming.startsWith(")")) {
                            var currentFlag = ""
                            while (consuming.first() != ' ' && consuming.first() != ')') {
                                currentFlag += consuming.first()
                                consuming = consuming.drop(1)
                            }
                            if (currentFlag.isBlank()) continue
                            email.flagsValue = Optional.Set((email.flagsValue.getOrNull() ?: emptySet()) + Email.Flag.fromString(currentFlag))
                            consuming = consuming.trimStart()
                        }
                        consuming = consuming
                            .substringAfter(")")
                            .trim()
                        continue
                    }

                    if (consuming.startsWith("UID")) {
                        consuming = consuming
                            .removePrefix("UID ")
                            .trim()

                        val uid = consuming.substringBefore(" ").toLongOrNull()
                            ?: throw IllegalArgumentException("Could not parse UID in $line")
                        email.uidValue = Optional.Set(uid)
                        consuming = consuming
                            .substringAfter(" ").trim()
                        continue
                    }

                    if (consuming.startsWith("ENVELOPE")) {
                        consuming = consuming
                            .removePrefix("ENVELOPE (")
                            .trim()


                        /**
                         *  The fields of the envelope structure are in the following
                         *  order: date, subject, from, sender, reply-to, to, cc, bcc,
                         *  in-reply-to, and message-id. The date, subject, in-reply-to,
                         *  and message-id fields are strings. The from, sender, reply-to,
                         *  to, cc, and bcc fields are parenthesized lists of address
                         *  structures.
                         * @see <a href="https://www.rfc-editor.org/rfc/rfc3501#section-2.3.5">RFC 3501 - 2.3.5. ENVELOPE</a>
                         */

                        val simpleQuoteRegex = Regex("\"([^\"]*)\"") // matches "content"

                        val rawDate = simpleQuoteRegex.find(consuming)?.groupValues?.get(1)
                            ?: throw IllegalArgumentException("Could not parse date in $consuming (quoteRegex)")

                        val dateWithOffsetRegex =
                            Regex("((?<dayofweek>(Mon|Tue|Wed|Thu|Fri|Sat|Sun))(,)? )?(?<dayofmonth>\\d{1,2}) (?<month>(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)) (?<year>\\d{4}) (?<hour>\\d{2}):(?<minute>\\d{2}):(?<second>\\d{2}) (?<offset>[+-]\\d{4}|UT|GMT)")
                        val rawDateWithOffset = dateWithOffsetRegex.find(consuming)
                            ?: throw IllegalArgumentException("Could not parse date in $consuming")

                        val dayOfMonth = rawDateWithOffset.groups["dayofmonth"]!!.value.toInt()
                        val month =
                            Month(MonthNames.ENGLISH_ABBREVIATED.names.indexOf(rawDateWithOffset.groups["month"]!!.value) + 1)
                        val year = rawDateWithOffset.groups["year"]!!.value.toInt()
                        val hour = rawDateWithOffset.groups["hour"]!!.value.toInt()
                        val minute = rawDateWithOffset.groups["minute"]!!.value.toInt()
                        val second = rawDateWithOffset.groups["second"]!!.value.toInt()
                        val offsetRaw = rawDateWithOffset.groups["offset"]!!.value

                        val offset = when {
                            offsetRaw == "UT" -> UtcOffset.ZERO
                            offsetRaw == "GMT" -> UtcOffset.ZERO
                            offsetRaw.startsWith("+") || offsetRaw.startsWith("-") -> {
                                val offsetHours = offsetRaw.drop(1).take(2).toInt().let {
                                    if (offsetRaw.startsWith('-')) -it else it
                                }
                                val offsetMinutes = offsetRaw.drop(3).take(2).toInt()
                                UtcOffset(offsetHours, offsetMinutes)
                            }

                            else -> throw IllegalArgumentException("Invalid offset: $offsetRaw")
                        }

                        consuming = consuming.removePrefix("\"$rawDate\" ")
                        val date = LocalDateTime(
                            day = dayOfMonth,
                            month = month,
                            year = year,
                            hour = hour,
                            minute = minute,
                            second = second,
                        )
                        email.sentAtValue = Optional.Set(date.toInstant(offset))

                        val subjectRegex = Regex("^(?:\"((?:[^\"\\\\]|\\\\.)*)\"|NIL) ")
                        val subjectMatch = subjectRegex.find(consuming)
                            ?: throw IllegalArgumentException("Could not parse subject")

                        val subjectRaw = subjectMatch.groups[1]?.value
                        val subject = subjectRaw
                            ?.replace("\\\"", "\"")
                            ?.replace("\\\\", "\\")

                        email.subjectValue = Optional.Set(subject?.let { MimeUtility.decode(it) })
                        consuming = consuming.removePrefix(subjectMatch.value)

                        fun handleEmailUsers(consuming: String): Set<EmailUser> {
                            if (!consuming.startsWith("((")) throw IllegalArgumentException("Not a valid email user list")

                            var parenCount = 0
                            val content = buildString {
                                for (char in consuming) {
                                    if (char == '(') parenCount++
                                    if (char == ')') parenCount--
                                    append(char)
                                    if (parenCount == 0) break
                                }
                            }

                            val regex = Regex("\\((\"([^\"]+)\"|NIL)\\s+NIL\\s+\"([^\"]+)\"\\s+\"([^\"]+)\"\\)")
                            return regex.findAll(content).map { match ->
                                val (name, _, mailbox, host) = match.destructured
                                EmailUser("$mailbox@$host", name.takeIf { it != "NIL" })
                            }.toSet()
                        }

                        fun parseEmailField(
                            consuming: String,
                            getter: () -> Set<EmailUser>?,
                            setter: (Set<EmailUser>) -> Unit
                        ): String {
                            val trimmed = consuming.dropWhile { it == ' ' }
                            return if (trimmed.startsWith("NIL")) {
                                setter(emptySet())
                                trimmed.removePrefix("NIL").dropWhile { it == ' ' }
                            } else {
                                val raw = trimmed.substringBefore("))") + "))"
                                setter((getter() ?: emptySet()) + handleEmailUsers(raw))
                                trimmed.removePrefix(raw).dropWhile { it == ' ' }
                            }
                        }

                        listOf(
                            { email.fromValue.getOrNull() } to { v: Set<EmailUser> ->
                                email.fromValue = Optional.Set(v)
                            },
                            { email.sendersValue.getOrNull() } to { v: Set<EmailUser> ->
                                email.sendersValue = Optional.Set(v)
                            },
                            { email.replyToValue.getOrNull() } to { v: Set<EmailUser> ->
                                email.replyToValue = Optional.Set(v)
                            },
                            { email.toValue.getOrNull() } to { v: Set<EmailUser> ->
                                email.toValue = Optional.Set(v)
                            },
                            { email.ccValue.getOrNull() } to { v: Set<EmailUser> ->
                                email.ccValue = Optional.Set(v)
                            },
                            { email.bccValue.getOrNull() } to { v: Set<EmailUser> ->
                                email.bccValue = Optional.Set(v)
                            }
                        ).forEach { (getter, setter) ->
                            consuming = parseEmailField(consuming, getter, setter)
                        }

                        if (consuming.startsWith("NIL ")) {
                            email.inReplyToValue = Optional.Set(null)
                            consuming = consuming.substringAfter("NIL ")
                        } else {
                            consuming = consuming.removePrefix("\"")
                            consuming = consuming.removePrefix("<")
                            val inReplyTo = consuming.substringBefore(">")
                            email.inReplyToValue = Optional.Set(inReplyTo)
                            consuming = consuming
                                .removePrefix(inReplyTo)
                                .removePrefix(">\"")
                                .removePrefix(" ")
                        }

                        val messageIdRaw = simpleQuoteRegex.find(consuming)?.value!!
                        email.messageIdValue = Optional.Set(
                            messageIdRaw
                                .removePrefix("\"<")
                                .removeSuffix(">\"")
                        )

                        consuming = consuming
                            .removePrefix(messageIdRaw)
                            .removePrefix(")")
                            .trim()

                        emails.add(email)
                        continue
                    }

                    throw IllegalArgumentException("Could not parse FETCH response: $line (remaining: $consuming)")
                }
            } catch (e: Exception) {
                logger.error(buildString {
                    appendLine("Failed to parse FETCH response:")
                    if (config.dumpMailOnError) {
                        appendLine(line)
                        appendLine(" ".repeat(line.length - consuming.length) + "^-- error occurred around here")
                        appendLine(email.toString())
                    }
                    else appendLine("Enable dumpMailOnError to see email details")
                })
                throw e
            }
        }

        return emails
    }
}

@Suppress("unused")
class FetchRequest {
    var envelope = false
    var flags = false
    var uid = false
    var from = 1L
    var to = Long.MAX_VALUE

    /**
     * If true, details of the email will be shown before the stacktrace. This may include sensitive data like email content.
     */
    var dumpMailOnError = false


    /**
     * Request a single message by id.
     */
    fun getId(id: Long) {
        from = id
        to = id
    }


    /**
     * Request messages in the given id range (inclusive).
     */
    fun getIds(ids: List<Long>) {
        from = ids.minOrNull() ?: 1L
        to = ids.maxOrNull() ?: 1L
    }

    /**
     * Request all messages.
     */
    fun getAll() {
        from = 1L
        to = Long.MAX_VALUE
    }

    /**
     * Request all fields.
     */
    fun all() {
        flags = true
        envelope = true
        uid = true
    }
}

