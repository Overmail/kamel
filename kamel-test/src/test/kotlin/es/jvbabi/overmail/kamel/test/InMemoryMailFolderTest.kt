package es.jvbabi.overmail.kamel.test

import es.jvbabi.overmail.kamel.Email
import es.jvbabi.overmail.kamel.FetchRequest.Selection
import es.jvbabi.overmail.kamel.FolderState
import es.jvbabi.overmail.kamel.IdleEvent
import es.jvbabi.overmail.kamel.ImapConnectionClosedException
import es.jvbabi.overmail.kamel.ImapFolder
import es.jvbabi.overmail.kamel.SearchCriterion
import es.jvbabi.overmail.kamel.UnreadableMail
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Instant
import kotlin.time.Duration.Companion.seconds

private fun source(subject: String) = "From: ada@example.com\r\nSubject: $subject\r\n\r\nBody\r\n"

class InMemoryMailFolderTest : FunSpec({

    context("the client") {
        test("lists the folders that were created on it") {
            val client = InMemoryMailClient()
            client.folder("INBOX", specialType = ImapFolder.SpecialType.INBOX)
            client.folder("Archive/2025")

            client.getFolders().map { it.fullName } shouldContainExactly listOf("INBOX", "Archive/2025")
            client.getFolders().last().path shouldContainExactly listOf("Archive", "2025")
        }

        test("has no folders at the root of its hierarchy") {
            val client = InMemoryMailClient()
            client.folder("INBOX")

            client.getFolders(onlyRoot = true).shouldBeEmpty()
        }

        test("hands out the same folder for the same name") {
            val client = InMemoryMailClient()
            client.folder("INBOX").add(source("First"))

            client.folder("INBOX").uids shouldContainExactly listOf(1L)
        }

        test("fails like a client that lost its server") {
            val client = InMemoryMailClient().apply { failure = ImapConnectionClosedException("gone") }

            shouldThrow<ImapConnectionClosedException> { client.getFolders() }
        }
    }

    context("state") {
        test("follows the mails of the folder") {
            val folder = InMemoryMailFolder(listOf("INBOX"), uidValidity = 7)
            folder.state() shouldBe FolderState(uidValidity = 7, uidNext = 1, exists = 0)

            folder.add(source("First"))
            folder.add(source("Second"))
            folder.remove(1)

            folder.state() shouldBe FolderState(uidValidity = 7, uidNext = 3, exists = 1)
            folder.status() shouldBe folder.state()
        }

        test("a removed uid is not handed out again") {
            val folder = InMemoryMailFolder(listOf("INBOX"))
            folder.add(source("First"))
            folder.remove(1)

            folder.add(source("Second")) shouldBe 2L
        }

        test("resetUids voids the uids under a new uidValidity") {
            val folder = InMemoryMailFolder(listOf("INBOX"))
            folder.add(source("First"), uid = 40)
            folder.add(source("Second"), uid = 90)

            folder.resetUids()

            folder.uids shouldContainExactly listOf(1L, 2L)
            folder.state() shouldBe FolderState(uidValidity = 2, uidNext = 3, exists = 2)
        }

        test("a uid below one that was handed out is refused") {
            val folder = InMemoryMailFolder(listOf("INBOX"))
            folder.add(source("First"), uid = 40)

            shouldThrow<IllegalArgumentException> { folder.add(source("Second"), uid = 12) }
        }
    }

    context("getMails") {
        val folder = InMemoryMailFolder(listOf("INBOX")).apply {
            add(source("First"), uid = 4)
            add(source("Second"), flags = setOf(Email.Flag.Seen), uid = 9)
            add(source("Third"), uid = 12)
        }

        suspend fun subjects(config: es.jvbabi.overmail.kamel.FetchRequest.() -> Unit) =
            folder.getMails(config).map { it.subject.await() }.toList()

        test("selects by uid") {
            subjects { uidRange(5); all() } shouldContainExactly listOf("Second", "Third")
            subjects { uidRange(4, 9); all() } shouldContainExactly listOf("First", "Second")
            subjects { uidRange(13); all() }.shouldBeEmpty()
            subjects { uids(listOf(12, 4, 5)); all() } shouldContainExactly listOf("First", "Third")
            subjects { getUid(9); all() } shouldContainExactly listOf("Second")
        }

        test("selects by sequence number") {
            subjects { getAll(); all() } shouldContainExactly listOf("First", "Second", "Third")
            subjects { idRange(2); all() } shouldContainExactly listOf("Second", "Third")
            subjects { getIds(listOf(3, 1)); all() } shouldContainExactly listOf("First", "Third")
        }

        test("has nothing at a sequence number behind its last mail") {
            subjects { getId(4); all() }.shouldBeEmpty()
            subjects { getIds(listOf(2, 9)); all() } shouldContainExactly listOf("Second")
            subjects { idRange(4); all() }.shouldBeEmpty()
            subjects { idRange(2, 500); all() } shouldContainExactly listOf("Second", "Third")
        }

        test("fills uid and flags only if they were asked for") {
            val mail = folder.getMails { getId(2); envelope = true }.toList().single()
            shouldThrow<IllegalStateException> { mail.uid.await() }
            shouldThrow<IllegalStateException> { mail.flags.await() }

            val complete = folder.getMails { getId(2); all() }.toList().single()
            complete.uid.await() shouldBe 9L
            complete.flags.await() shouldBe setOf(Email.Flag.Seen)
        }

        test("a fetch by uid carries the uid") {
            folder.getMails { getUid(9) }.toList().single().uid.await() shouldBe 9L
        }

        test("hands out the content of a mail") {
            folder.getMails { getUid(9); all() }.toList().single().getContent().text shouldBe "Body\r\n"
        }

        test("records what was fetched") {
            val recorded = InMemoryMailFolder(listOf("INBOX")).apply { add(source("First")) }
            val mails = recorded.getMails { uidRange(2); all() }
            recorded.fetches.shouldBeEmpty()

            mails.toList()

            recorded.fetches shouldContainExactly listOf(Selection.UidRange(2, null))
        }

        test("fails like a folder that lost its connection") {
            val broken = InMemoryMailFolder(listOf("INBOX")).apply { failure = ImapConnectionClosedException("gone") }

            shouldThrow<ImapConnectionClosedException> { broken.getMails { getAll() }.toList() }
            shouldThrow<ImapConnectionClosedException> { broken.state() }
        }
    }

    context("unreadable mails") {
        val folder = InMemoryMailFolder(listOf("INBOX")).apply {
            add(source("First"))
            addUnreadable()
            add(source("Third"))
        }

        test("go to onUnreadable and cost only themselves") {
            val unreadable = mutableListOf<UnreadableMail>()

            val uids = folder.getMails {
                getAll()
                all()
                onUnreadable { unreadable += it }
            }.map { it.uid.await() }.toList()

            uids shouldContainExactly listOf(1L, 3L)
            unreadable.map { it.sequenceNumber to it.uid } shouldContainExactly listOf(2L to 2L)
        }

        test("fail the fetch if nobody takes them") {
            shouldThrow<IllegalArgumentException> { folder.getMails { getAll(); all() }.toList() }
        }
    }

    context("watching") {
        test("reports changes made from several threads in the order they were made") {
            withTimeout(30.seconds) {
                val folder = InMemoryMailFolder(listOf("INBOX"))
                val events = async(start = CoroutineStart.UNDISPATCHED) { folder.getIdleFolder().events().take(4000).toList() }
                folder.awaitWatcher()

                // More than a buffer of any usual size holds, and nobody collects in between.
                (1..4).map { launch(Dispatchers.Default) { repeat(1000) { folder.add(source("Mail")) } } }.joinAll()

                events.await() shouldContainExactly (1..4000L).map { IdleEvent.NewMessage(it) }
            }
        }

        test("reports the changes made while somebody watches") {
            withTimeout(10.seconds) {
                val folder = InMemoryMailFolder(listOf("INBOX"))
                folder.add(source("Before"))

                val events = async { folder.getIdleFolder().events().take(3).toList() }
                folder.awaitWatcher()

                folder.add(source("New"))
                folder.setFlags(1, setOf(Email.Flag.Seen))
                folder.remove(1)

                events.await() shouldContainExactly listOf(
                    IdleEvent.NewMessage(2),
                    IdleEvent.FlagsChanged(1, listOf(Email.Flag.Seen)),
                    IdleEvent.RemovedMessage(1),
                )
            }
        }
    }

    context("search") {
        test("finds the mails the criteria describe, by UID") {
            val folder = InMemoryMailFolder(listOf("INBOX"))
            folder.add(source("Lunch tomorrow?"))
            val dinner = folder.add(source("Dinner tonight?"))
            val lunch = folder.add(source("Re: lunch tomorrow?"))

            folder.search { subject("LUNCH") } shouldContainExactly listOf(1L, lunch)
            folder.search { not { subject("lunch") } } shouldContainExactly listOf(dinner)
        }

        test("finds every mail without criteria") {
            val folder = InMemoryMailFolder(listOf("INBOX"))
            folder.add(source("First"))
            folder.add(source("Second"))

            folder.search { } shouldContainExactly listOf(1L, 2L)
        }

        test("finds a mail by its Message-ID and the day it was sent") {
            val folder = InMemoryMailFolder(listOf("INBOX"))
            folder.add("Message-ID: <a@example.org>\r\nDate: Mon, 5 May 2025 14:03:12 +0200\r\nSubject: A\r\n\r\nBody\r\n")
            val b = folder.add("Message-ID: <b@example.org>\r\nDate: Tue, 6 May 2025 09:00:00 +0200\r\nSubject: B\r\n\r\nBody\r\n")

            folder.search { messageId("b@example.org") } shouldContainExactly listOf(b)
            folder.search { sentSince(LocalDate(2025, 5, 6)) } shouldContainExactly listOf(b)
            folder.search {
                or {
                    sentOn(LocalDate(2025, 5, 5))
                    messageId("<b@example.org>")
                }
            } shouldContainExactly listOf(1L, b)
        }

        test("looks at the day a mail arrived in the time zone of the folder") {
            val folder = InMemoryMailFolder(listOf("INBOX"), timeZone = TimeZone.of("Europe/Berlin"))
            val late = folder.add(source("Late"), receivedAt = Instant.parse("2025-05-05T23:30:00Z"))
            folder.add(source("Early"), receivedAt = Instant.parse("2025-05-05T08:00:00Z"))

            folder.search { receivedOn(LocalDate(2025, 5, 6)) } shouldContainExactly listOf(late)
        }

        test("hands UIDs that getMails takes as they are") {
            val folder = InMemoryMailFolder(listOf("INBOX"))
            folder.add(source("Lunch"))
            folder.add(source("Dinner"))

            val uids = folder.search { subject("Dinner") }
            folder.getMails { uids(uids) }.map { it.subject.await() }.toList() shouldContainExactly listOf("Dinner")
        }

        test("never finds an unreadable mail") {
            val folder = InMemoryMailFolder(listOf("INBOX"))
            folder.addUnreadable()
            val readable = folder.add(source("Readable"))

            folder.search { } shouldContainExactly listOf(readable)
        }

        test("remembers what was searched for") {
            val folder = InMemoryMailFolder(listOf("INBOX"))
            folder.search { subject("Lunch") }

            folder.searches shouldContainExactly listOf(SearchCriterion.Subject("Lunch"))
            folder.fetches.shouldBeEmpty()
        }

        test("fails like a folder that lost its connection") {
            val folder = InMemoryMailFolder(listOf("INBOX")).apply { failure = ImapConnectionClosedException("gone") }

            shouldThrow<ImapConnectionClosedException> { folder.search { } }
        }
    }
})
