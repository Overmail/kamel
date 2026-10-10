package es.jvbabi.overmail.kamel

import es.jvbabi.overmail.kamel.util.MimeUtility
import es.jvbabi.overmail.kamel.util.Optional
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.fold
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.time.Instant

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
class Email private constructor(
    /** `null` for an email that was parsed from its source, see [parse]. */
    internal val folder: ImapFolder?,
    /** The message source of an email that was parsed from it, `null` for an email of a [folder]. */
    internal val source: ByteArray?
) {
    internal constructor(folder: ImapFolder) : this(folder, null)

    private val content = EmailContent(this)

    /**
     * A field that is set is returned without touching the connection, so it also works for an
     * email without a [folder].
     */
    private fun <T> field(name: String, value: Optional<T>): Deferred<T> {
        if (value is Optional.Set) return CompletableDeferred(value.value)

        val folder = folder ?: return CompletableDeferred<T>().apply {
            completeExceptionally(IllegalStateException("$name is not available: this email was parsed from its source and has no connection to load it with"))
        }
        return folder.imapClient.coroutineScope.async { TODO("Use connection to download $name") }
    }

    /**
     * The message source as the server sent it, in chunks, byte for byte.
     */
    fun getRawContent(): Flow<ByteArray> = content.getRawContent()

    /**
     * Fetches the message and splits it into its text body, its html body and - if
     * [includeAttachments] is set - its attachments.
     *
     * @throws ImapCommandException if the server refused the FETCH
     */
    suspend fun getContent(includeAttachments: Boolean = false): Content = content.getContent(includeAttachments)

    var subjectValue: Optional<String?> = Optional.Empty()
        internal set

    val subject: Deferred<String?>
        get() = field("subject", subjectValue)

    var sentAtValue: Optional<Instant> = Optional.Empty()
        internal set

    val sentAt: Deferred<Instant>
        get() = field("sentAt", sentAtValue)

    var sendersValue: Optional<Set<EmailUser>> = Optional.Empty()
        internal set

    val senders: Deferred<Set<EmailUser>>
        get() = field("senders", sendersValue)

    var fromValue: Optional<Set<EmailUser>> = Optional.Empty()
        internal set

    val from: Deferred<Set<EmailUser>>
        get() = field("from", fromValue)

    var replyToValue: Optional<Set<EmailUser>> = Optional.Empty()
        internal set

    val replyTo: Deferred<Set<EmailUser>>
        get() = field("replyTo", replyToValue)

    var toValue: Optional<Set<EmailUser>> = Optional.Empty()
        internal set

    val to: Deferred<Set<EmailUser>>
        get() = field("to", toValue)

    var ccValue: Optional<Set<EmailUser>> = Optional.Empty()
        internal set

    val cc: Deferred<Set<EmailUser>>
        get() = field("cc", ccValue)

    var bccValue: Optional<Set<EmailUser>> = Optional.Empty()
        internal set

    val bcc: Deferred<Set<EmailUser>>
        get() = field("bcc", bccValue)

    var messageIdValue: Optional<String> = Optional.Empty()
        internal set

    val messageId: Deferred<String>
        get() = field("messageId", messageIdValue)

    var inReplyToValue: Optional<String?> = Optional.Empty()
        internal set

    val inReplyTo: Deferred<String?>
        get() = field("inReplyTo", inReplyToValue)

    var uidValue: Optional<Long> = Optional.Empty()
        internal set

    val uid: Deferred<Long>
        get() = field("uid", uidValue)

    var flagsValue: Optional<Set<Flag>> = Optional.Empty()
        internal set

    val flags: Deferred<Set<Flag>>
        get() = field("flags", flagsValue)

    class Content(
        /** The message source, byte for byte. */
        val raw: ByteArray,
        /** All text bodies joined, `null` if the message has none. */
        val text: String?,
        /** All html bodies joined, `null` if the message has none. */
        val html: String?,
        /** Empty unless requested with `includeAttachments`. */
        val attachments: List<Attachment>,
    )

    class Attachment(
        /** Decoded file name, `null` if the part names none. */
        val fileName: String?,
        /** Base type in lowercase, e.g. `application/pdf`. */
        val contentType: String,
        /** `Content-ID` without angle brackets, referenced by `cid:` urls in the html body. */
        val contentId: String?,
        /** `true` for `Content-Disposition: inline`, e.g. images embedded in the html body. */
        val isInline: Boolean,
        /** The content with its transfer encoding already decoded. */
        val data: ByteArray,
    )

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

    companion object {
        /**
         * Creates an email from its message source, e.g. the bytes [getRawContent] or
         * [Content.raw] returned earlier, or an `.eml` file. No connection is involved.
         *
         * The fields of the envelope are read from the headers. [uid] and [flags] are not part of
         * a message source and stay empty, as does [sentAt] without a readable `Date` header:
         * awaiting them fails with an [IllegalStateException]. [getContent] and [getRawContent]
         * work on [raw].
         *
         * @throws IllegalArgumentException if [raw] cannot be read as a message
         */
        fun parse(raw: ByteArray): Email = EmailParser.parse(raw)

        /**
         * Reads [input] to its end and creates an email from it, see [parse]. The read blocks and
         * [input] is not closed.
         */
        fun parse(input: InputStream): Email = parse(input.readBytes())

        /**
         * Collects [raw] and creates an email from it, see [parse].
         */
        suspend fun parse(raw: Flow<ByteArray>): Email =
            parse(raw.fold(ByteArrayOutputStream()) { buffer, chunk -> buffer.apply { write(chunk) } }.toByteArray())

        /** Wraps [source] without reading its headers. */
        internal fun ofSource(source: ByteArray) = Email(null, source)
    }

    suspend fun print() {
        println("${this.uid.await()}: ${this.subject.await() ?: "<no subject>"}")
        println("    From: ${this.from.await().joinToString(", ") { it.toString() }}")
        println("    Sender: ${this.senders.await().joinToString(", ") { it.toString() }}")
        println("    To: ${this.to.await().joinToString(", ") { it.toString() }}")
        println("    Cc: ${this.cc.await().joinToString(", ") { it.toString() }}")
        println("    Bcc: ${this.bcc.await().joinToString(", ") { it.toString() }}")
        println("    Date: ${this.sentAt.await()}")
        println("    Flags: ${this.flags.await().joinToString(", ") { it.value }}")
        println("    In-Reply-To: ${this.inReplyTo.await() ?: "<none>"}")
        println("    Message-ID: ${this.messageId.await()}")
    }

    override fun toString(): String {
        return buildString {
            appendLine("${this@Email.uidValue}: ${this@Email.subjectValue}")
            appendLine("    From: ${this@Email.fromValue}")
            appendLine("    Sender: ${this@Email.sendersValue}")
            appendLine("    To: ${this@Email.toValue}")
            appendLine("    Cc: ${this@Email.ccValue}")
            appendLine("    Bcc: ${this@Email.bccValue}")
            appendLine("    Date: ${this@Email.sentAtValue}")
            appendLine("    Flags: ${this@Email.flagsValue}")
            appendLine("    In-Reply-To: ${this@Email.inReplyToValue}")
            appendLine("    Message-ID: ${this@Email.messageIdValue}")
        }
    }
}