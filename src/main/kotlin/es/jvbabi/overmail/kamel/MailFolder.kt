package es.jvbabi.overmail.kamel

import kotlinx.coroutines.flow.Flow

/**
 * An account to list folders of. [ImapClient] is the one that talks to a server; code that only
 * takes a [MailClient] can be handed one that keeps its folders in memory, for example in a test.
 */
interface MailClient {
    suspend fun getFolders(onlyRoot: Boolean = false): List<MailFolder>
}

/**
 * A folder to read messages from, see [MailClient]. [ImapFolder] is the one of a server.
 */
interface MailFolder {
    val path: List<String>
    val delimiter: String
    val specialType: ImapFolder.SpecialType?

    /** The name as the server knows it. */
    val fullName: String get() = path.joinToString(delimiter)

    /** The last segment of [path]. */
    val name: String get() = path.lastOrNull() ?: fullName

    /** What the server reports about the folder right now. */
    suspend fun state(): FolderState

    /**
     * The same as [state], asked in a way that does not need the folder to be opened where that
     * makes a difference.
     */
    suspend fun status(): FolderState = state()

    /**
     * Fetches the messages [config] selects. Nothing is sent before the flow is collected, and
     * every collection fetches again. Build the mails to emit with [Email.parse].
     */
    fun getMails(config: FetchRequest.() -> Unit): Flow<Email>

    /**
     * Searches the folder for the messages [criteria] describes and returns their UIDs, lowest
     * first. Hand them to [getMails] with `uids(...)` to fetch them:
     *
     * ```
     * val uids = folder.search {
     *     subject("Lunch")
     *     sentOn(LocalDate(2025, 5, 5))
     * }
     * folder.getMails { uids(uids); all() }
     * ```
     *
     * Without any criterion, every message of the folder is found. See [SearchCriteria] for how
     * strings and dates are matched.
     */
    suspend fun search(criteria: SearchRequest.() -> Unit): List<Long>

    fun getIdleFolder(): IdleFolder
}
