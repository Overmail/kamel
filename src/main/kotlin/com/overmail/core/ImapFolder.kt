package com.overmail.core

import com.overmail.util.Optional
import com.overmail.util.substringAfterIgnoreCasing
import io.ktor.network.sockets.*
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toInstant
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import kotlin.io.encoding.Base64
import kotlin.time.Instant

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
            }
            else logger.error("Could not get mail ids: $line")
        }
        return emptyList()
    }

    private suspend fun getSocketInstance(): SocketInstance {
        if (socketInstance == null || socketInstance?.socket?.isClosed == true) socketInstance = client.createNewSocket().apply {
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
            val data = line
                .substringAfter("(")
                .substringBeforeLast(")")
                .trim()
                .split(" ")

            val email = Email(client = this.client)

            var i = 0
            while (i < data.size) {
                when (val segment = data[i].uppercase()) {
                    "FLAGS" -> {
                        // get flags
                        val flags = email.flagsValue.let { if (it is Optional.Set) it.value.toMutableSet() else mutableSetOf() }
                        flags.addAll(data.joinToString(" ")
                            .substringAfter(data.take(i + 1).joinToString(" "))
                            .substringAfter("(")
                            .substringBefore(")")
                            .split(" ")
                            .map { Email.Flag.fromString(it) }
                        )
                        i += flags.size + 1
                        email.flagsValue = Optional.Set(flags)
                    }
                    "ENVELOPE" -> {
                        /**
                         *  The fields of the envelope structure are in the following
                         *  order: date, subject, from, sender, reply-to, to, cc, bcc,
                         *  in-reply-to, and message-id. The date, subject, in-reply-to,
                         *  and message-id fields are strings. The from, sender, reply-to,
                         *  to, cc, and bcc fields are parenthesized lists of address
                         *  structures.
                         * @see <a href="https://www.rfc-editor.org/rfc/rfc3501#section-2.3.5">RFC 3501 - 2.3.5. ENVELOPE</a>
                         */
                        var remaining = data.joinToString(" ")
                            .substringAfter(data.take(i + 1).joinToString(" "))
                            .substringAfter("(")

                        /**
                         * The number of spaces used within the envelope structure. Used to increment i.
                         */
                        var spaces = 1

                        remaining = remaining.removePrefix("\"")
                        val sentAtRaw = remaining.substringBefore("\"")
                        val dateFormatter = LocalDateTime.Format {
                            dayOfWeek(DayOfWeekNames("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"))
                            chars(", ")
                            day(Padding.ZERO)
                            char(' ')
                            monthName(MonthNames("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"))
                            char(' ')
                            year(Padding.ZERO)
                            char(' ')
                            hour(Padding.ZERO)
                            char(':')
                            minute(Padding.ZERO)
                            char(':')
                            second(Padding.ZERO)
                        }
                        val date = dateFormatter.parse(sentAtRaw.substringBeforeLast(" "))
                        val offset = sentAtRaw.substringAfterLast(" ")
                        // e.g. +0200, -0145
                        val offsetHours = offset.drop(1).take(2).toInt().let {
                            if (offset.startsWith('-')) -it else it
                        }
                        val offsetMinutes = offset.drop(3).take(2).toInt()
                        email.sentAtValue = Optional.Set(date.toInstant(UtcOffset(offsetHours, offsetMinutes)))

                        remaining = remaining.removePrefix("$sentAtRaw\" ")
                        spaces += sentAtRaw.count { it == ' ' } + 1

                        remaining = remaining.removePrefix("\"")

                        val rawSubject: String
                        if (remaining.startsWith("NIL ")) {
                            rawSubject = "NIL"
                            email.subjectValue = Optional.Set(null)
                        }
                        else {
                            rawSubject = remaining.substringBefore("\"")
                            if (rawSubject.startsWith("=?UTF-8?")) {
                                val utf8Content = rawSubject.substringAfter("?UTF-8?")
                                email.subjectValue = Optional.Set(if (utf8Content.startsWith("B?")) {
                                    Base64.decode(utf8Content.substringAfter("?").substringBeforeLast("?=")).decodeToString()
                                } else if (utf8Content.startsWith("Q?")) {
                                    decodeQuotedPrintable(utf8Content.substringAfter("?").substringBeforeLast("?="))
                                } else null)
                            } else email.subjectValue = Optional.Set(rawSubject)
                        }

                        remaining = remaining
                            .removePrefix("\"")
                            .removePrefix(rawSubject)
                            .removePrefix("\"")
                            .removePrefix(" ")
                        spaces += rawSubject.count { it == ' ' } + 1

                        fun handleEmailUsers(remaining: String): Set<EmailUser> {
                            var remainingUsers = remaining
                            fun handleSingle(single: String): EmailUser? {
                                val single = single
                                    .removePrefix("(")
                                    .removeSuffix(")")
                                val regex = Regex("^(?:\"([^\"]+)\"|NIL)\\s+NIL\\s+\"([^\"]+)\"\\s+\"([^\"]+)\"$")
                                val match = regex.find(single)
                                if (match != null) {
                                    val (name, mailbox, host) = match.destructured
                                    return EmailUser("$mailbox@$host", name.takeIf { it != "NIL" })
                                } else {
                                    logger.error("Cannot handle single user $single")
                                    return null
                                }
                            }

                            if (!remainingUsers.startsWith("((")) throw IllegalArgumentException("Not a valid email user list")
                            remainingUsers = remainingUsers.substringAfter("(")
                            val result = mutableSetOf<EmailUser>()
                            while (remainingUsers != ")") {
                                val nextPart = remainingUsers.substringBefore(")") + ")"
                                val user = handleSingle(nextPart)
                                user?.let { result.add(it) }
                                remainingUsers = remainingUsers.removePrefix(nextPart)
                            }
                            return result
                        }

                        val rawFrom = remaining.substringBefore("))") + "))"
                        email.fromValue = Optional.Set((email.fromValue.getOrNull() ?: emptySet()) + handleEmailUsers(rawFrom))
                        remaining = remaining
                            .removePrefix(rawFrom)
                            .dropWhile { it == ' ' }
                        spaces += rawFrom.count { it == ' ' } + 1

                        val rawSenders = remaining.substringBefore("))") + "))"
                        email.sendersValue = Optional.Set((email.sendersValue.getOrNull() ?: emptySet()) + handleEmailUsers(rawSenders))
                        remaining = remaining
                            .removePrefix(rawSenders)
                            .dropWhile { it == ' ' }
                        spaces += rawSenders.count { it == ' ' } + 1

                        val rawReplyTo = remaining.substringBefore("))") + "))"
                        email.replyToValue = Optional.Set((email.replyToValue.getOrNull() ?: emptySet()) + handleEmailUsers(rawReplyTo))
                        remaining = remaining
                            .removePrefix(rawReplyTo)
                            .dropWhile { it == ' ' }
                        spaces += rawReplyTo.count { it == ' ' } + 1

                        val recipientsRaw = remaining.substringBefore("))") + "))"
                        email.toValue = Optional.Set((email.toValue.getOrNull() ?: emptySet()) + handleEmailUsers(recipientsRaw))
                        remaining = remaining
                            .removePrefix(recipientsRaw)
                            .dropWhile { it == ' ' }
                        spaces += recipientsRaw.count { it == ' ' } + 1

                        if (remaining.startsWith("NIL ")) {
                            remaining = remaining.substringAfter("NIL ")
                            spaces += 1
                            email.ccValue = Optional.Set(emptySet())
                        } else {
                            val ccRaw = remaining.substringBefore("))") + "))"
                            email.ccValue = Optional.Set((email.ccValue.getOrNull() ?: emptySet()) + handleEmailUsers(ccRaw))
                            remaining = remaining
                                .removePrefix(rawReplyTo)
                                .dropWhile { it == ' ' }
                            spaces += ccRaw.count { it == ' ' } + 1
                        }

                        if (remaining.startsWith("NIL ")) {
                            remaining = remaining.substringAfter("NIL ")
                            spaces += 1
                            email.bccValue = Optional.Set(emptySet())
                        } else {
                            val bccRaw = remaining.substringBefore("))") + "))"
                            email.bccValue = Optional.Set((email.bccValue.getOrNull() ?: emptySet()) + handleEmailUsers(bccRaw))
                            remaining = remaining
                                .removePrefix(rawReplyTo)
                                .dropWhile { it == ' ' }
                            spaces += bccRaw.count { it == ' ' } + 1
                        }

                        if (remaining.startsWith("NIL ")) {
                            email.inReplyToValue = Optional.Set(null)
                            remaining = remaining.substringAfter("NIL ")
                            spaces += 1
                        } else {
                            remaining = remaining.removePrefix("\"")
                            remaining = remaining.removePrefix("<")
                            val inReplyTo = remaining.substringBefore(">")
                            email.inReplyToValue = Optional.Set(inReplyTo)
                            remaining = remaining
                                .removePrefix(inReplyTo)
                                .removePrefix(">\"")
                                .removePrefix(" ")
                            spaces += 1
                        }

                        remaining = remaining.removePrefix("\"<")
                        email.messageIdValue = Optional.Set(remaining.substringBefore(">"))

                        i += spaces + 1
                        emails.add(email)
                    }
                    else -> {
                        println("Unknown segment: $segment")
                        i++
                    }
                }
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

class EmailUser(
    val address: String,
    name: String?
) {
    val name = name?.takeIf { it != "NIL" && it.isNotBlank() }
    override fun toString(): String {
        if (name == null) return address
        return "$name <$address>"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmailUser) return false
        if (address != other.address) return false
        if (name != other.name) return false
        return true
    }

    override fun hashCode(): Int {
        var result = address.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        return result
    }
}

private fun decodeQuotedPrintable(input: String): String {
    val outputStream = ByteArrayOutputStream()
    var i = 0

    while (i < input.length) {
        when (val c = input[i]) {
            '_' -> outputStream.write(' '.code)
            '=' if i + 2 < input.length -> {
                val hex = input.substring(i + 1, i + 3)
                outputStream.write(hex.toInt(16))
                i += 2
            }
            else -> outputStream.write(c.code)
        }
        i++
    }

    return String(outputStream.toByteArray(), Charsets.UTF_8)
}

@Suppress("unused")
class Email internal constructor(
    private val client: ImapClient
) {
    var subjectValue: Optional<String?> = Optional.Empty()
        internal set

    val subject: Deferred<String?>
        get() = client.coroutineScope.async {
            this@Email.subjectValue.let { if (it is Optional.Set) return@async it.value }

            TODO("Use connection to download subject")
        }

    var sentAtValue: Optional<Instant> = Optional.Empty()
        internal set

    val sentAt: Deferred<Instant>
        get() = client.coroutineScope.async {
            this@Email.sentAtValue.let { if (it is Optional.Set) return@async it.value }
            TODO("Use connection to download sentAt")
        }

    var sendersValue: Optional<Set<EmailUser>> = Optional.Empty()
        internal set

    val senders: Deferred<Set<EmailUser>>
        get() = client.coroutineScope.async {
            this@Email.sendersValue.let { if (it is Optional.Set) return@async it.value }
            TODO("Use connection to download senders")
        }

    var fromValue: Optional<Set<EmailUser>> = Optional.Empty()
        internal set

    val from: Deferred<Set<EmailUser>>
        get() = client.coroutineScope.async {
            this@Email.fromValue.let { if (it is Optional.Set) return@async it.value }
            TODO("Use connection to download from")
        }

    var replyToValue: Optional<Set<EmailUser>> = Optional.Empty()
        internal set

    val replyTo: Deferred<Set<EmailUser>>
        get() = client.coroutineScope.async {
            this@Email.replyToValue.let { if (it is Optional.Set) return@async it.value }
            TODO("Use connection to download replyTo")
        }

    var toValue: Optional<Set<EmailUser>> = Optional.Empty()
        internal set

    val to: Deferred<Set<EmailUser>>
        get() = client.coroutineScope.async {
            this@Email.toValue.let { if (it is Optional.Set) return@async it.value }
            TODO("Use connection to download to")
        }

    var ccValue: Optional<Set<EmailUser>> = Optional.Empty()
            internal set

    val cc: Deferred<Set<EmailUser>>
        get() = client.coroutineScope.async {
            this@Email.ccValue.let { if (it is Optional.Set) return@async it.value }
            TODO("Verbindung verwenden, um cc herunterzuladen")
        }

    var bccValue: Optional<Set<EmailUser>> = Optional.Empty()
        internal set

    val bcc: Deferred<Set<EmailUser>>
        get() = client.coroutineScope.async {
            this@Email.bccValue.let { if (it is Optional.Set) return@async it.value }
            TODO("Verbindung verwenden, um bcc herunterzuladen")
        }

    var messageIdValue: Optional<String> = Optional.Empty()
        internal set

    val messageId: Deferred<String>
        get() = client.coroutineScope.async {
            this@Email.messageIdValue.let { if (it is Optional.Set) return@async it.value }
            TODO("Use connection to download messageId")
        }

    var inReplyToValue: Optional<String?> = Optional.Empty()
        internal set

    val inReplyTo: Deferred<String?>
        get() = client.coroutineScope.async {
            this@Email.inReplyToValue.let { if (it is Optional.Set) return@async it.value }
            TODO("Use connection to download inReplyTo")
        }

    var flagsValue: Optional<Set<Flag>> = Optional.Empty()
        internal set

    val flags: Deferred<Set<Flag>>
        get() = client.coroutineScope.async {
            this@Email.flagsValue.let { if (it is Optional.Set) return@async it.value }
            TODO("Use connection to download flags")
        }

    sealed class Flag {
        abstract val value: String
        data object Seen : Flag() { override val value = "\\Seen" }
        data object Answered : Flag() { override val value = "\\Answered" }
        data object Flagged : Flag() { override val value = "\\Flagged" }
        data object Deleted : Flag() { override val value = "\\Deleted" }
        data object Draft : Flag() { override val value = "\\Draft" }
        data object Recent : Flag() { override val value = "\\Recent" }
        data class Other(override val value: String) : Flag()

        companion object {
            fun fromString(value: String): Flag {
                return when (value) {
                    "\\Seen" -> Seen
                    "\\Answered" -> Answered
                    "\\Flagged" -> Flagged
                    "\\Deleted" -> Deleted
                    "\\Draft" -> Draft
                    "\\Recent" -> Recent
                    else -> Other(value)
                }
            }
        }
    }
}