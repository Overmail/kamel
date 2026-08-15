package es.jvbabi.overmail.core

import es.jvbabi.overmail.parser.FetchResponseParser
import es.jvbabi.overmail.parser.SearchResponseParser
import es.jvbabi.overmail.parser.buildFetchCommand
import es.jvbabi.overmail.util.Optional
import es.jvbabi.overmail.util.quoteImap
import kotlinx.coroutines.channels.consumeEach
import org.slf4j.LoggerFactory

class ImapFolder(
    internal val imapClient: ImapClient,
    val path: List<String>,
    val delimiter: String,
    val specialType: SpecialType?
): ClosableClientPool(
    factory = {
        imapClient.getClient(requireNew = true).apply {
            // Quoted and awaited: an unquoted name with a space is read as two arguments and the
            // server answers BAD, which used to surface as a hang on the next command instead.
            this.execute("SELECT ${path.joinToString(delimiter).quoteImap()}").await()
        }
    },
    name = "ImapFolder/${path.joinToString(delimiter)}"
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

    fun getIdleFolder() = IdleFolder(this)

    private val logger = LoggerFactory.getLogger("ImapFolder/$fullName")

    override fun toString(): String {
        return "Folder(path=$path, specialType=$specialType)"
    }

    /**
     * Returns all mail ids in this folder. This is a relative identifier, always starting with 0.
     */
    suspend fun getMailIds(): List<Int> {
        val response = getClient().execute("SEARCH ALL")
        val ids = mutableListOf<Int>()
        response.response.consumeEach { line ->
            if (line.uppercase().startsWith("${response.commandId} OK SEARCH")) return ids
            val parsed = SearchResponseParser.parseIds(line)
            if (parsed != null) ids.addAll(parsed)
            else logger.error("Could not get mail ids: $line")
        }
        return emptyList()
    }

    suspend fun getIdByUid(uid: Long): Int? {
        val response = getClient().execute("SEARCH UID $uid")
        response.response.consumeEach { line ->
            if (line.uppercase().startsWith("${response.commandId} OK SEARCH")) return null
            val parsed = SearchResponseParser.parseIds(line)
            if (parsed != null) return parsed.firstOrNull()
            else logger.error("Could not get mail id by uid: $line")
        }
        return null
    }

    suspend fun getMails(config: FetchRequest.() -> Unit): List<Email> {
        val config = FetchRequest().apply(config)
        val mailIds = getMailIds()
        if (mailIds.isEmpty()) return emptyList()

        val emails = mutableListOf<Email>()

        val from = when (val selection = config.selection) {
            is FetchRequest.EmailSelection.Id -> {
                mailIds.firstOrNull { it >= selection.from } ?: return emptyList()
            }
            is FetchRequest.EmailSelection.Uid -> {
                val id = getIdByUid(selection.uid) ?: return emptyList()
                if (id !in mailIds) return emptyList()
                id
            }
        }

        val to = when (val selection = config.selection) {
            is FetchRequest.EmailSelection.Id -> {
                mailIds.lastOrNull { it <= selection.to } ?: return emptyList()
            }
            is FetchRequest.EmailSelection.Uid -> from
        }

        val command = buildFetchCommand(config, from, to)

        // No await() here: it drains the response, and the lines are needed below. consumeEach
        // already runs until the job completes and closes the channel.
        val response = getClient().execute(command)
        response.response.consumeEach { line ->
            if (line.uppercase().startsWith("${response.commandId} OK FETCH")) return@consumeEach

            val parser = FetchResponseParser { response.response.receive() }
            val parsed = try {
                parser.parse(line)
            } catch (e: Exception) {
                logger.error(buildString {
                    appendLine("Failed to parse FETCH response:")
                    if (config.dumpMailOnError) {
                        appendLine(parser.line)
                        val consumedChars = (parser.line.length - parser.remaining.length).coerceAtLeast(0)
                        appendLine(" ".repeat(consumedChars) + "^-- error occurred around here")
                        appendLine(parser.partialResult().toString())
                    }
                    else appendLine("Enable dumpMailOnError to see email details")
                })
                throw e
            }

            // Only responses carrying an ENVELOPE become mails, everything else is ignored.
            val envelope = parsed.envelope ?: return@consumeEach

            emails.add(Email(folder = this@ImapFolder).apply {
                parsed.flags?.let { flagsValue = Optional.Set(it) }
                parsed.uid?.let { uidValue = Optional.Set(it) }
                sentAtValue = Optional.Set(envelope.sentAt)
                subjectValue = Optional.Set(envelope.subject)
                fromValue = Optional.Set(envelope.from)
                sendersValue = Optional.Set(envelope.senders)
                replyToValue = Optional.Set(envelope.replyTo)
                toValue = Optional.Set(envelope.to)
                ccValue = Optional.Set(envelope.cc)
                bccValue = Optional.Set(envelope.bcc)
                inReplyToValue = Optional.Set(envelope.inReplyTo)
                messageIdValue = Optional.Set(envelope.messageId)
            })
        }

        return emails
    }
}

@Suppress("unused")
class FetchRequest {
    var envelope = false
    var flags = false
    var uid = false
    internal var selection: EmailSelection = EmailSelection.Id(1, Long.MAX_VALUE)

    /**
     * If true, details of the email will be shown before the stacktrace. This may include sensitive data like email content.
     */
    var dumpMailOnError = false


    /**
     * Request a single message by id.
     */
    fun getId(id: Long) {
        selection = EmailSelection.Id(id, id)
    }

    fun getUid(uid: Long) {
        selection = EmailSelection.Uid(uid)
    }


    /**
     * Request messages in the given id range (inclusive).
     */
    fun getIds(ids: List<Long>) {
        selection = EmailSelection.Id(
            from = ids.minOrNull() ?: 1L,
            to = ids.maxOrNull() ?: 1L
        )
    }

    /**
     * Request all messages.
     */
    fun getAll() {
        selection = EmailSelection.Id(1L, Long.MAX_VALUE)
    }

    /**
     * Request all fields.
     */
    fun all() {
        flags = true
        envelope = true
        uid = true
    }

    internal sealed class EmailSelection {
        data class Id(val from: Long, val to: Long): EmailSelection()
        data class Uid(val uid: Long): EmailSelection()
    }
}

