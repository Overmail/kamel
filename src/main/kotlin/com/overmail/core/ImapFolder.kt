package com.overmail.core

import com.overmail.util.MimeUtility
import com.overmail.util.Optional
import com.overmail.util.substringAfterIgnoreCasing
import io.ktor.network.sockets.*
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.toInstant
import org.slf4j.LoggerFactory
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
                        val flags =
                            email.flagsValue.let { if (it is Optional.Set) it.value.toMutableSet() else mutableSetOf() }

                        val rawFlagsRegex = Regex("""\((.*?)\)""")
                        val match = rawFlagsRegex.find(
                            data.joinToString(" ")
                                .substringAfter(data.take(i + 1).joinToString(" "))
                        )
                        if (match == null) {
                            logger.error(
                                "Could not parse flags in ${
                                    data.joinToString(" ").substringAfter(data.take(i + 1).joinToString(" "))
                                }"
                            )
                            i++
                            continue
                        }
                        val rawFlags = match.groupValues[1].split(" ").map { it.trim() }
                        flags.addAll(rawFlags.mapTo(flags) { Email.Flag.fromString(it) })
                        i += flags.size + 1
                        email.flagsValue = Optional.Set(flags)
                    }

                    "UID" -> {
                        val uid = data.getOrNull(i + 1)?.toLongOrNull()
                        if (uid == null) {
                            logger.error("Could not parse UID in ${data.joinToString(" ").substringAfter(data.take(i + 1).joinToString(" "))}")
                            i++
                            continue
                        }
                        email.uidValue = Optional.Set(uid)
                        i += 2
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

                        // lade den kompletten envelope in eine variable, zähle die klammern, bis sie wieder ausgeglichen sind
                        val spaces = run {
                            var parenCount = 1
                            buildString {
                                for (char in remaining) {
                                    when (char) {
                                        '(' -> parenCount++
                                        ')' -> parenCount--
                                    }
                                    if (parenCount == 0) break
                                    append(char)
                                }
                            }.count { it == ' ' }
                        }


                        val simpleQuoteRegex = Regex("\"([^\"]*)\"") // matches "content"

                        val rawDate = simpleQuoteRegex.find(remaining)?.groupValues?.get(1)
                            ?: throw IllegalArgumentException("Could not parse date in $remaining (quoteRegex)")

                        val dateWithOffsetRegex =
                            Regex("((?<dayofweek>(Mon|Tue|Wed|Thu|Fri|Sat|Sun))(,)? )?(?<dayofmonth>\\d{1,2}) (?<month>(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)) (?<year>\\d{4}) (?<hour>\\d{2}):(?<minute>\\d{2}):(?<second>\\d{2}) (?<offset>[+-]\\d{4}|UT)")
                        val rawDateWithOffset = dateWithOffsetRegex.find(remaining)
                            ?: throw IllegalArgumentException("Could not parse date in $remaining")

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
                            offsetRaw.startsWith("+") || offsetRaw.startsWith("-") -> {
                                val offsetHours = offsetRaw.drop(1).take(2).toInt().let {
                                    if (offsetRaw.startsWith('-')) -it else it
                                }
                                val offsetMinutes = offsetRaw.drop(3).take(2).toInt()
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
                        email.sentAtValue = Optional.Set(date.toInstant(offset))

                        if (remaining.startsWith("NIL ")) {
                            email.subjectValue = Optional.Set(null)
                            remaining = remaining.substringAfter("NIL ")
                        } else {
                            val subjectRegex = Regex("^(?:\"(?<subject>[^\"]*)\"|NIL) ")
                            val subjectMatch = subjectRegex.find(remaining)
                                ?: throw IllegalArgumentException("Could not parse subject in $remaining (subjectRegex)")
                            val subject = subjectMatch.groups["subject"]?.value
                            email.subjectValue = Optional.Set(subject?.let { MimeUtility.decode(it) })
                            remaining = remaining.removePrefix(subjectMatch.value)
                        }

                        fun handleEmailUsers(remaining: String): Set<EmailUser> {
                            if (!remaining.startsWith("((")) throw IllegalArgumentException("Not a valid email user list")

                            var parenCount = 0
                            val content = buildString {
                                for (char in remaining) {
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
                            remaining: String,
                            getter: () -> Set<EmailUser>?,
                            setter: (Set<EmailUser>) -> Unit
                        ): String {
                            val trimmed = remaining.dropWhile { it == ' ' }
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
                            { email.toValue.getOrNull() } to { v: Set<EmailUser> -> email.toValue = Optional.Set(v) },
                            { email.ccValue.getOrNull() } to { v: Set<EmailUser> -> email.ccValue = Optional.Set(v) },
                            { email.bccValue.getOrNull() } to { v: Set<EmailUser> -> email.bccValue = Optional.Set(v) }
                        ).forEach { (getter, setter) ->
                            remaining = parseEmailField(remaining, getter, setter)
                        }

                        if (remaining.startsWith("NIL ")) {
                            email.inReplyToValue = Optional.Set(null)
                            remaining = remaining.substringAfter("NIL ")
                        } else {
                            remaining = remaining.removePrefix("\"")
                            remaining = remaining.removePrefix("<")
                            val inReplyTo = remaining.substringBefore(">")
                            email.inReplyToValue = Optional.Set(inReplyTo)
                            remaining = remaining
                                .removePrefix(inReplyTo)
                                .removePrefix(">\"")
                                .removePrefix(" ")
                        }

                        remaining = remaining.removePrefix("\"<")
                        email.messageIdValue = Optional.Set(remaining.substringBefore(">"))

                        emails.add(email)
                        i += spaces + 2
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
    val name = name
        ?.takeIf { it != "NIL" && it.isNotBlank() }
        ?.let {
            if (it.startsWith('"') && it.endsWith('"')) it.drop(1).dropLast(1)
            else it
        }
        ?.let { name -> MimeUtility.decode(name) }
        ?.let {
            if (it.startsWith("'") && it.endsWith("'")) it.drop(1).dropLast(1)
            else it
        }

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

    var uidValue: Optional<Long> = Optional.Empty()
        internal set

    val uid: Deferred<Long>
        get() = client.coroutineScope.async {
            this@Email.uidValue.let { if (it is Optional.Set) return@async it.value }
            TODO("Use connection to download uid")
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

        data object Seen : Flag() {
            override val value = "\\Seen"
        }

        data object Answered : Flag() {
            override val value = "\\Answered"
        }

        data object Flagged : Flag() {
            override val value = "\\Flagged"
        }

        data object Deleted : Flag() {
            override val value = "\\Deleted"
        }

        data object Draft : Flag() {
            override val value = "\\Draft"
        }

        data object Recent : Flag() {
            override val value = "\\Recent"
        }

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
