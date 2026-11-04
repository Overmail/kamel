package es.jvbabi.overmail.kamel.test

import es.jvbabi.overmail.kamel.Email
import es.jvbabi.overmail.kamel.FetchRequest
import es.jvbabi.overmail.kamel.FetchRequest.Selection
import es.jvbabi.overmail.kamel.FolderState
import es.jvbabi.overmail.kamel.IdleEvent
import es.jvbabi.overmail.kamel.IdleFolder
import es.jvbabi.overmail.kamel.ImapFolder
import es.jvbabi.overmail.kamel.MailFolder
import es.jvbabi.overmail.kamel.SearchCriterion
import es.jvbabi.overmail.kamel.SearchRequest
import es.jvbabi.overmail.kamel.UnreadableMail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A folder whose mails live in memory. It hands out UIDs and sequence numbers the way a server
 * does and reports every change to whoever watches it.
 *
 * Create one with [InMemoryMailClient.folder], or on its own if the code under test only takes a
 * [MailFolder].
 */
class InMemoryMailFolder(
    override val path: List<String>,
    override val delimiter: String = "/",
    override val specialType: ImapFolder.SpecialType? = null,
    uidValidity: Long = 1,
    /**
     * The time zone of the server, which decides on which day a mail arrived for
     * [SearchCriterion.ReceivedOn] and the like.
     */
    val timeZone: TimeZone = TimeZone.UTC,
) : MailFolder {

    /** @param source `null` for a mail the folder reports as unreadable, see [addUnreadable] */
    private class Stored(
        val uid: Long,
        val source: ByteArray?,
        val flags: Set<Email.Flag>,
        val cause: Exception?,
        val receivedAt: Instant,
    )

    private val lock = Any()
    private val mails = mutableListOf<Stored>()
    private var nextUid = 1L
    private val fetchList = mutableListOf<Selection>()
    private val searchList = mutableListOf<SearchCriterion>()
    private val changes = MutableSharedFlow<IdleEvent>(extraBufferCapacity = Int.MAX_VALUE)

    /** See [FolderState.uidValidity]. Changed by [resetUids]. */
    var uidValidity: Long = uidValidity
        private set

    /**
     * If set, [state], [status], [search] and collecting [getMails] throw it instead of answering. Set it to
     * an `ImapConnectionClosedException` to play a connection that is gone.
     */
    @Volatile
    var failure: Throwable? = null

    /** The UIDs of the mails in the folder, in the order of their sequence numbers. */
    val uids: List<Long> get() = synchronized(lock) { mails.map { it.uid } }

    /**
     * What was asked of the folder so far, one entry per collected [getMails]. Tells whether the
     * code under test fetched what is new or the whole folder.
     */
    val fetches: List<Selection> get() = synchronized(lock) { fetchList.toList() }

    /**
     * The criteria of every [search] so far, in that order. Tells whether the code under test
     * searched the server or fetched to compare on its own.
     */
    val searches: List<SearchCriterion> get() = synchronized(lock) { searchList.toList() }

    /**
     * Puts a mail into the folder and returns the UID it got. Watchers are told about it.
     *
     * @param source the message source, headers and body, as in an `.eml` file
     * @param uid the UID to give the mail, which has to be above every UID handed out before.
     * Leave it out to get the next one
     * @param receivedAt when the mail arrived, the internal date a search by
     * [SearchCriterion.ReceivedOn] and the like looks at
     */
    fun add(
        source: ByteArray,
        flags: Set<Email.Flag> = emptySet(),
        uid: Long? = null,
        receivedAt: Instant = Clock.System.now(),
    ): Long = store(source, flags, uid, null, receivedAt)

    /** [add] for a message source written as a string. */
    fun add(
        source: String,
        flags: Set<Email.Flag> = emptySet(),
        uid: Long? = null,
        receivedAt: Instant = Clock.System.now(),
    ): Long = add(source.toByteArray(Charsets.UTF_8), flags, uid, receivedAt)

    /**
     * Puts a mail into the folder that a fetch cannot read, as one whose response Kamel fails to
     * parse: it goes to `onUnreadable` of the request, or fails the fetch with [cause]. A
     * [search] never finds it.
     */
    fun addUnreadable(
        cause: Exception = IllegalArgumentException("Could not parse FETCH response"),
        uid: Long? = null,
    ): Long = store(null, emptySet(), uid, cause, Clock.System.now())

    private fun store(
        source: ByteArray?,
        flags: Set<Email.Flag>,
        uid: Long?,
        cause: Exception?,
        receivedAt: Instant,
    ): Long = synchronized(lock) {
        val assigned = uid ?: nextUid
        require(assigned >= nextUid) { "UIDs only grow: $assigned was asked for, the next one is $nextUid" }
        nextUid = assigned + 1
        mails += Stored(assigned, source, flags, cause, receivedAt)
        report(IdleEvent.NewMessage(mails.size.toLong()))
        assigned
    }

    /**
     * Has to be called with [lock] held, so watchers see the changes in the order they were made.
     * Never suspends and never drops: the buffer of [changes] has no limit.
     */
    private fun report(event: IdleEvent) {
        changes.tryEmit(event)
    }

    /**
     * Removes the mail with [uid]. The mails after it move up by one sequence number.
     *
     * @throws NoSuchElementException if the folder has no such mail
     */
    fun remove(uid: Long) {
        synchronized(lock) {
            val index = indexOf(uid)
            mails.removeAt(index)
            report(IdleEvent.RemovedMessage(index + 1L))
        }
    }

    /**
     * Replaces the flags of the mail with [uid].
     *
     * @throws NoSuchElementException if the folder has no such mail
     */
    fun setFlags(uid: Long, flags: Set<Email.Flag>) {
        synchronized(lock) {
            val index = indexOf(uid)
            val mail = mails[index]
            mails[index] = Stored(mail.uid, mail.source, flags, mail.cause, mail.receivedAt)
            report(IdleEvent.FlagsChanged(index + 1L, flags.toList()))
        }
    }

    /**
     * Numbers the mails anew, starting at 1, under a new [uidValidity]: what a server does when it
     * rebuilds a folder. Every UID a client stored is void afterwards.
     */
    fun resetUids(uidValidity: Long = this.uidValidity + 1) {
        synchronized(lock) {
            this.uidValidity = uidValidity
            val renumbered = mails.mapIndexed { index, mail ->
                Stored(index + 1L, mail.source, mail.flags, mail.cause, mail.receivedAt)
            }
            mails.clear()
            mails += renumbered
            nextUid = mails.size + 1L
        }
    }

    private fun indexOf(uid: Long): Int = mails.indexOfFirst { it.uid == uid }
        .also { if (it < 0) throw NoSuchElementException("No mail with uid $uid in $fullName") }

    override suspend fun state(): FolderState {
        failure?.let { throw it }
        return synchronized(lock) { FolderState(uidValidity = uidValidity, uidNext = nextUid, exists = mails.size) }
    }

    /**
     * Emits the mails the request selects, as they are when the flow is collected. What a
     * selection names beyond the mails of the folder yields nothing, as with [ImapFolder]. Like a server,
     * it only fills [Email.uid] and [Email.flags] if the request asked for them; the fields of the
     * envelope are always there.
     */
    override fun getMails(config: FetchRequest.() -> Unit): Flow<Email> {
        val request = FetchRequest().apply(config)
        return flow {
            failure?.let { throw it }
            val selection = request.selection
            val snapshot = synchronized(lock) {
                fetchList += selection
                mails.toList()
            }
            snapshot.forEachIndexed { index, mail ->
                val sequenceNumber = index + 1L
                val isSelected = when (selection) {
                    is Selection.Ids -> sequenceNumber in selection.ids
                    is Selection.IdRange -> sequenceNumber >= selection.from && (selection.to?.let { sequenceNumber <= it } ?: true)
                    is Selection.Uids -> mail.uid in selection.uids
                    is Selection.UidRange -> mail.uid in selection
                }
                if (!isSelected) return@forEachIndexed

                if (mail.source == null) {
                    request.unreadable(UnreadableMail(sequenceNumber, mail.uid, checkNotNull(mail.cause)))
                    return@forEachIndexed
                }
                val isUidFetch = selection is Selection.Uids || selection is Selection.UidRange
                emit(
                    Email.parse(
                        mail.source,
                        uid = mail.uid.takeIf { request.uid || isUidFetch },
                        flags = mail.flags.takeIf { request.flags },
                    )
                )
            }
        }
    }

    /**
     * Returns the UIDs of the mails [criteria] describes, lowest first, decided as
     * [SearchCriterion.matches] decides it. Mails added with [addUnreadable] are never found.
     */
    override suspend fun search(criteria: SearchRequest.() -> Unit): List<Long> {
        val criterion = SearchRequest().apply(criteria).criterion
        failure?.let { throw it }
        val snapshot = synchronized(lock) {
            searchList += criterion
            mails.toList()
        }
        return snapshot
            .filter { it.source != null && criterion.matches(it.source, it.receivedAt, timeZone) }
            .map { it.uid }
    }

    /**
     * The changes made through [add], [remove] and [setFlags] from the moment its events are
     * collected. Like `IDLE`, it does not report what happened before, and it never completes.
     */
    override fun getIdleFolder(): IdleFolder = object : IdleFolder {
        override fun events(): Flow<IdleEvent> = changes
    }

    /**
     * Suspends until somebody collects the events of [getIdleFolder]. A change made before that
     * is reported to nobody, so a test waits here before it makes one.
     */
    suspend fun awaitWatcher() {
        changes.subscriptionCount.first { it > 0 }
    }
}
