package es.jvbabi.overmail.kamel.test

import es.jvbabi.overmail.kamel.ImapFolder
import es.jvbabi.overmail.kamel.MailClient

/**
 * An account whose folders live in memory. Hand it to code that takes a [MailClient] to test that
 * code without a server.
 */
class InMemoryMailClient : MailClient {
    private val lock = Any()
    private val folderList = mutableListOf<InMemoryMailFolder>()

    /** The folders of the account, in the order they were created. */
    val folders: List<InMemoryMailFolder> get() = synchronized(lock) { folderList.toList() }

    /**
     * If set, [getFolders] throws it instead of answering, as a server that cannot be reached
     * makes a real client do.
     */
    @Volatile
    var failure: Throwable? = null

    /**
     * Returns the folder named [fullName], creating it if the account does not have it yet.
     * The other arguments only matter for a folder that is created.
     */
    fun folder(
        fullName: String,
        delimiter: String = "/",
        specialType: ImapFolder.SpecialType? = null,
        uidValidity: Long = 1,
    ): InMemoryMailFolder = synchronized(lock) {
        folderList.firstOrNull { it.fullName == fullName }
            ?: InMemoryMailFolder(fullName.split(delimiter), delimiter, specialType, uidValidity).also { folderList += it }
    }

    /** Removes the folder named [fullName], as if it was deleted on the server. */
    fun removeFolder(fullName: String) {
        synchronized(lock) { folderList.removeAll { it.fullName == fullName } }
    }

    /**
     * @param onlyRoot `true` asks a server for the root of its hierarchy instead of for its
     * folders, which this account does not have: the answer is empty
     */
    override suspend fun getFolders(onlyRoot: Boolean): List<InMemoryMailFolder> {
        failure?.let { throw it }
        return if (onlyRoot) emptyList() else folders
    }
}
