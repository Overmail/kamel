package es.jvbabi.overmail.kamel

import es.jvbabi.overmail.kamel.FetchRequest.Selection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList

/**
 * A folder that never saw a server: the mails are parsed from their source and kept in a list.
 */
private class MemoryFolder(
    override val path: List<String>,
    private val uidValidity: Long,
    private val mails: List<Email>,
    private val changes: List<IdleEvent> = emptyList(),
) : MailFolder {
    override val delimiter = "/"
    override val specialType: ImapFolder.SpecialType? = null

    override suspend fun state() = FolderState(
        uidValidity = uidValidity,
        uidNext = (mails.maxOfOrNull { it.uid.await() } ?: 0) + 1,
        exists = mails.size,
    )

    override fun getMails(config: FetchRequest.() -> Unit): Flow<Email> = flow {
        mails.forEachIndexed { index, mail ->
            val uid = mail.uid.await()
            val isSelected = when (val selection = FetchRequest().apply(config).selection) {
                is Selection.Ids -> index + 1L in selection.ids
                is Selection.IdRange -> index + 1L >= selection.from && (selection.to == null || index + 1L <= selection.to)
                is Selection.Uids -> uid in selection.uids
                is Selection.UidRange -> uid in selection
            }
            if (isSelected) emit(mail)
        }
    }

    override fun getIdleFolder() = object : IdleFolder {
        override fun events() = changes.asFlow()
    }
}

private class MemoryClient(private val folders: List<MailFolder>) : MailClient {
    override suspend fun getFolders(onlyRoot: Boolean) = folders
}

/**
 * What an importer does with a mailbox, written against the interfaces only: the subjects of
 * everything that arrived in [folderName] since [knownUid].
 */
private suspend fun newSubjects(client: MailClient, folderName: String, knownUid: Long): List<String?> =
    client.getFolders()
        .first { it.fullName == folderName }
        .getMails { uidRange(knownUid + 1); all() }
        .map { it.subject.await() }
        .toList()

private fun mail(uid: Long, subject: String, vararg flags: Email.Flag) = Email.parse(
    "From: ada@example.com\r\nSubject: $subject\r\n\r\nBody\r\n".toByteArray(),
    uid = uid,
    flags = flags.toSet(),
)

/**
 * Code written against [MailClient] and [MailFolder] runs without a socket.
 */
class MailFolderTest : FunSpec({

    val inbox = MemoryFolder(
        path = listOf("Archive", "2025"),
        uidValidity = 7,
        mails = listOf(mail(4, "First"), mail(9, "Second", Email.Flag.Seen), mail(12, "Third")),
        changes = listOf(IdleEvent.NewMessage(4)),
    )

    test("a folder kept in memory answers what a server would") {
        newSubjects(MemoryClient(listOf(inbox)), "Archive/2025", knownUid = 4) shouldContainExactly listOf("Second", "Third")
        newSubjects(MemoryClient(listOf(inbox)), "Archive/2025", knownUid = 12) shouldContainExactly emptyList()
    }

    test("its names follow from its path") {
        inbox.fullName shouldBe "Archive/2025"
        inbox.name shouldBe "2025"
    }

    test("its state and its changes come from the same interface as those of a server") {
        inbox.state() shouldBe FolderState(uidValidity = 7, uidNext = 13, exists = 3)
        inbox.getIdleFolder().use { it.events().toList() } shouldContainExactly listOf(IdleEvent.NewMessage(4))
    }

    test("the folders of a server are folders of the interface") {
        val client: MailClient = ImapClient(host = "imap.invalid", port = 993, auth = ImapClient.Auth.BasicAuth("u", "p"))
        val folder: MailFolder = ImapFolder(client as ImapClient, listOf("INBOX"), "/", ImapFolder.SpecialType.INBOX)

        folder.fullName shouldBe "INBOX"
    }
})
