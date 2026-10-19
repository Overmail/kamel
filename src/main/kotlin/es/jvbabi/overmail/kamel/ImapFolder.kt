package es.jvbabi.overmail.kamel

import es.jvbabi.overmail.kamel.FetchRequest.Selection
import es.jvbabi.overmail.kamel.parser.FetchResponseParser
import es.jvbabi.overmail.kamel.parser.SearchResponseParser
import es.jvbabi.overmail.kamel.parser.SelectResponseParser
import es.jvbabi.overmail.kamel.parser.StatusResponseParser
import es.jvbabi.overmail.kamel.parser.buildFetchCommands
import es.jvbabi.overmail.kamel.parser.fetchResponseUid
import es.jvbabi.overmail.kamel.util.Optional
import es.jvbabi.overmail.kamel.util.quoteImap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory

class ImapFolder(
    internal val imapClient: ImapClient,
    override val path: List<String>,
    override val delimiter: String,
    override val specialType: SpecialType?
): ClosableClientPool(
    factory = {
        imapClient.getClient(requireNew = true).apply {
            // Quoted and awaited: an unquoted name with a space is read as two arguments and the
            // server answers BAD, which used to surface as a hang on the next command instead.
            freshSelect = select(path.joinToString(delimiter))
        }
    },
    name = "ImapFolder/${path.joinToString(delimiter)}",
    // The connections of a folder go quiet just like those of the client, more so even: they sit
    // between two passes of an importer while the IDLE on the side reports the change.
    checkConnectionsAfter = imapClient.checkConnectionsAfter,
    connectionCheckTimeout = imapClient.connectionCheckTimeout,
), MailFolder {
    override val fullName = this@ImapFolder.path.joinToString(delimiter)
    override val name = path.lastOrNull() ?: fullName

    enum class SpecialType {
        INBOX,
        SENT,
        SPAM,
        TRASH,
        DRAFTS
    }

    override fun getIdleFolder(): IdleFolder = ImapIdleFolder(this)

    private val logger = LoggerFactory.getLogger("ImapFolder/$fullName")

    override fun toString(): String {
        return "Folder(path=$path, specialType=$specialType)"
    }

    /**
     * Asks the server with a `SELECT`, so the answer is the state of the folder now and not the
     * one from when its connection was opened.
     *
     * @throws IllegalStateException if the server reports no `UIDVALIDITY` or no `EXISTS`
     */
    override suspend fun state(): FolderState = selected(getClient()).state(fullName)

    /**
     * What the server says about this folder on [client]: the answer to the `SELECT` it was just
     * opened with, or to a new one.
     */
    private suspend fun selected(client: SocketInstance): SelectResponseParser {
        val fresh = client.freshSelect
        client.freshSelect = null
        return fresh ?: client.select(fullName)
    }

    /**
     * Asks the server with a `STATUS`, on a connection of the client: no connection of this folder
     * is opened for it, and none has to select it. The cheap way to see whether a folder changed
     * before working on it.
     *
     * @throws IllegalStateException if the server answers without `UIDVALIDITY` or `MESSAGES`
     */
    override suspend fun status(): FolderState =
        imapClient.getClient().execute("STATUS ${fullName.quoteImap()} (MESSAGES UIDNEXT UIDVALIDITY)") {
            var state: FolderState? = null
            while (true) StatusResponseParser.parse(next() ?: break)?.let { state = it }
            checkNotNull(state) { "The server did not answer the STATUS of $fullName" }
        }

    /**
     * Returns the sequence numbers of all mails in this folder. They are positions, starting at 1,
     * and change when a mail is removed.
     */
    suspend fun getMailIds(): List<Int> = search("SEARCH ALL")

    suspend fun getIdByUid(uid: Long): Int? = search("SEARCH UID $uid").firstOrNull()

    private suspend fun search(command: String): List<Int> = getClient().execute(command) {
        val ids = mutableListOf<Int>()
        while (true) {
            val line = next()?.first() ?: break
            val parsed = SearchResponseParser.parseIds(line)
            if (parsed != null) ids.addAll(parsed)
            else logger.error("Unexpected response to $command: $line")
        }
        ids
    }

    /**
     * Fetches the mails [config] selects and emits each one as its response arrives, so a large
     * folder is never held as a whole. The folder is not listed first: a selection by UID goes
     * out as it is, one by sequence number is cut to the size of the folder, which a `SELECT`
     * tells. What lies beyond it is not there and yields nothing.
     *
     * The connection is busy until the flow ends. A collector that stops early may cost that
     * connection: unless the rest of the response is there to be skipped, it is closed and replaced.
     *
     * A response that cannot be parsed fails the flow, unless [FetchRequest.onUnreadable] takes it.
     *
     * A collector may run other commands of this folder, such as downloading the mail it was
     * just handed. They run on another connection.
     *
     * @throws ImapCommandException if the server refused the fetch
     */
    override fun getMails(config: FetchRequest.() -> Unit): Flow<Email> {
        val request = FetchRequest().apply(config)
        return flow {
            // An empty set of mails needs no connection to come back empty.
            if (buildFetchCommands(request).isEmpty()) return@flow

            val client = getClient()
            // Servers differ in what they make of a sequence number above the size of the
            // folder: some refuse the fetch, even "1:*" in an empty folder, and all of them read
            // "n:*" beyond the end as the last mail. A UID that is not there is just left out.
            val isBySequenceNumber = request.selection is Selection.Ids || request.selection is Selection.IdRange
            val exists = if (isBySequenceNumber) selected(client).exists else null

            for (command in buildFetchCommands(request, exists)) {
                client.execute(command) {
                    while (true) {
                        val response = next() ?: break
                        emit(readMail(request, response) ?: continue)
                    }
                }
            }
        }
    }

    /**
     * Turns one untagged [response] into the mail it describes, or into `null` if it is not one
     * [request] asked for.
     */
    private suspend fun readMail(request: FetchRequest, response: List<String>): Email? {
        // A server may report anything in between, a new EXISTS for example.
        val sequenceNumber = FETCH_RESPONSE_REGEX.find(response.first())?.groupValues?.get(1)?.toLongOrNull()
            ?: return null

        val continuation = response.listIterator(1)
        val parser = FetchResponseParser {
            if (continuation.hasNext()) continuation.next()
            else throw IllegalArgumentException("The FETCH response ended inside a literal")
        }
        val parsed = try {
            parser.parse(response.first())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(buildString {
                appendLine("Failed to parse FETCH response:")
                if (request.dumpMailOnError) {
                    appendLine(parser.line)
                    val consumedChars = (parser.line.length - parser.remaining.length).coerceAtLeast(0)
                    appendLine(" ".repeat(consumedChars) + "^-- error occurred around here")
                    appendLine(parser.partialResult().toString())
                }
                else appendLine("Enable dumpMailOnError to see email details")
            })
            request.unreadable(UnreadableMail(sequenceNumber, parser.partialResult().uid ?: fetchResponseUid(response), e))
            return null
        }

        // An unrequested FETCH, e.g. the flags another client just changed, lacks what was asked for.
        val isUidFetch = request.selection is Selection.UidRange || request.selection is Selection.Uids
        if (request.envelope && parsed.envelope == null) return null
        if ((request.uid || isUidFetch) && parsed.uid == null) return null

        // "UID FETCH n:*" always answers with the newest mail, even if its UID is below n: * is
        // the highest UID in the folder, and n:* is read as the range between the two.
        val selection = request.selection
        if (selection is Selection.UidRange && parsed.uid != null && parsed.uid !in selection) return null

        return Email(folder = this@ImapFolder).apply {
            parsed.flags?.let { flagsValue = Optional.Set(it) }
            parsed.uid?.let { uidValue = Optional.Set(it) }
            parsed.envelope?.let { envelope ->
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
            }
        }
    }

    private companion object {
        /** Selects the folder named [fullName] on this connection and collects what the server reports. */
        private suspend fun SocketInstance.select(fullName: String): SelectResponseParser =
            execute("SELECT ${fullName.quoteImap()}") {
                val parser = SelectResponseParser()
                while (true) parser.add(next()?.first() ?: break)
                parser
            }

        private val FETCH_RESPONSE_REGEX = Regex("""^\* (\d+) FETCH """, RegexOption.IGNORE_CASE)
    }
}

/**
 * A `FETCH` response that could not be parsed, see [FetchRequest.onUnreadable].
 */
class UnreadableMail(
    /** The position of the mail in its folder. */
    val sequenceNumber: Long,
    /** `null` if the response did not get as far as its UID, or carried none. */
    val uid: Long?,
    val cause: Exception,
)

@Suppress("unused")
class FetchRequest {
    var envelope = false
    var flags = false
    var uid = false

    /** Which mails are fetched. Set by the functions below, the last one called wins. */
    var selection: Selection = Selection.IdRange(1, null)
        private set

    private var onUnreadable: (suspend (UnreadableMail) -> Unit)? = null

    /**
     * If true, details of the email will be shown before the stacktrace. This may include sensitive data like email content.
     */
    var dumpMailOnError = false

    /**
     * Called for every response that could not be parsed. The fetch then goes on with the next
     * mail instead of failing as a whole.
     */
    fun onUnreadable(block: suspend (UnreadableMail) -> Unit) {
        onUnreadable = block
    }

    /**
     * Reports a mail that could not be read: hands it to [onUnreadable], or throws its cause if
     * nobody asked for such mails. For a [MailFolder] of your own; [ImapFolder] calls it itself.
     */
    suspend fun unreadable(mail: UnreadableMail) {
        val onUnreadable = onUnreadable ?: throw mail.cause
        onUnreadable(mail)
    }

    /**
     * Request a single message by id.
     */
    fun getId(id: Long) {
        selection = Selection.Ids(setOf(id))
    }

    fun getUid(uid: Long) {
        selection = Selection.Uids(setOf(uid))
    }

    /**
     * Request exactly the messages with the given ids. For everything between two ids, see [idRange].
     */
    fun getIds(ids: Collection<Long>) {
        selection = Selection.Ids(ids.toSet())
    }

    /**
     * Request the messages with the ids [from] to [to] (inclusive), up to the last one if [to] is `null`.
     */
    fun idRange(from: Long, to: Long? = null) {
        selection = Selection.IdRange(from, to)
    }

    /**
     * Request exactly the messages with the given UIDs. UIDs the folder does not have are skipped.
     */
    fun uids(uids: Collection<Long>) {
        selection = Selection.Uids(uids.toSet())
    }

    /**
     * Request the messages with the UIDs [from] to [to] (inclusive), up to the newest one if [to]
     * is `null`. Nothing is returned if the folder has no UID in that range.
     */
    fun uidRange(from: Long, to: Long? = null) {
        selection = Selection.UidRange(from, to)
    }

    /**
     * Request all messages.
     */
    fun getAll() {
        selection = Selection.IdRange(1L, null)
    }

    /**
     * Request all fields.
     */
    fun all() {
        flags = true
        envelope = true
        uid = true
    }

    /**
     * The mails a [FetchRequest] asks for, by sequence number (`id`) or by UID. Both start at 1.
     */
    sealed interface Selection {
        data class Ids(val ids: Set<Long>) : Selection {
            init {
                require(ids.all { it >= 1 }) { "Ids start at 1: $ids" }
            }
        }

        /** [to] is inclusive, `null` stands for the last mail of the folder. */
        data class IdRange(val from: Long, val to: Long?) : Selection {
            init {
                require(from >= 1 && (to == null || to >= from)) { "Not a range of ids: $from to $to" }
            }
        }

        data class Uids(val uids: Set<Long>) : Selection {
            init {
                require(uids.all { it >= 1 }) { "UIDs start at 1: $uids" }
            }
        }

        /** [to] is inclusive, `null` stands for the highest UID of the folder. */
        data class UidRange(val from: Long, val to: Long?) : Selection {
            init {
                require(from >= 1 && (to == null || to >= from)) { "Not a range of UIDs: $from to $to" }
            }

            operator fun contains(uid: Long) = uid >= from && (to == null || uid <= to)
        }
    }
}
