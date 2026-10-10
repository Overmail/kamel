package es.jvbabi.overmail.kamel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.ktor.utils.io.readLine
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import java.util.concurrent.CopyOnWriteArrayList
import io.kotest.matchers.shouldBe
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * The untagged FETCH response for a mail whose subject is sent as a literal, byte for byte as a
 * server would put it on the wire.
 */
private fun envelopeResponse(tag: String, subject: String): ByteArray {
    val subjectBytes = subject.toByteArray(Charsets.UTF_8)
    val head = "* 1 FETCH (UID 7 ENVELOPE (\"Mon, 5 May 2025 14:03:12 +0200\" {${subjectBytes.size}}\r\n"
    val tail = " ((\"Jane Doe\" NIL \"jane\" \"example.org\")) NIL NIL " +
            "((\"John Roe\" NIL \"john\" \"example.com\")) NIL NIL NIL \"<x@example.org>\"))\r\n" +
            "$tag OK FETCH completed\r\n"
    return head.toByteArray(Charsets.UTF_8) + subjectBytes + tail.toByteArray(Charsets.UTF_8)
}

/**
 * Drives [ImapFolder.getMails] against a fake server, so the FETCH response passes through
 * [SocketInstance.execute] and the parser exactly as it does in production.
 */
class ImapFolderTest : FunSpec({

    test("an envelope literal is cut at its announced byte count, not after that many characters") {
        // "Grüße aus München" is 17 characters but 20 bytes; a character based reader eats the
        // three bytes following the literal and parses the rest of the envelope off by three.
        val subject = "Grüße aus München"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var server: ServerSocket? = null
        try {
            withTimeout(30.seconds) {
                val running = scope.startRawServer { tag, command ->
                    when {
                        command.startsWith("FETCH") -> envelopeResponse(tag, subject)
                        else -> "$tag OK completed\r\n".toByteArray(Charsets.US_ASCII)
                    }
                }
                server = running
                val client = ImapClient(
                    host = "127.0.0.1",
                    port = (running.localAddress as InetSocketAddress).port,
                    ssl = false,
                    auth = ImapClient.Auth.BasicAuth("user", "password"),
                    coroutineScope = scope
                )
                val folder = ImapFolder(client, listOf("INBOX"), "/", null)

                val mail = folder.getMails {
                    getAll()
                    envelope = true
                    uid = true
                }.single()

                mail.uid.await() shouldBe 7L
                mail.subject.await() shouldBe subject
                mail.from.await() shouldBe setOf(EmailUser("jane@example.org", "Jane Doe"))
                mail.to.await() shouldBe setOf(EmailUser("john@example.com", "John Roe"))
            }
        } finally {
            server?.close()
            scope.cancel()
        }
    }

    // CommuniGate Pro answers "A003 OK completed" - RFC 3501 leaves the text after the status to
    // the server, so nothing may be matched beyond tag and status.
    context("a tagged completion that does not repeat the command name") {
        val respond: (String, String) -> List<String> = { tag, command ->
            when {
                command.startsWith("LIST") -> listOf(
                    """* LIST (\HasNoChildren) "/" "INBOX"""",
                    "$tag OK completed"
                )
                command.startsWith("SEARCH UID 8") -> listOf("* SEARCH 2", "$tag OK completed")
                command.startsWith("SEARCH") -> listOf("* SEARCH 1 2", "$tag OK completed")
                command.startsWith("FETCH") -> listOf(
                    """* 1 FETCH (UID 7 ENVELOPE ("Mon, 5 May 2025 14:03:12 +0200" "Hi" ((NIL NIL "jane" "example.org")) NIL NIL ((NIL NIL "john" "example.com")) NIL NIL NIL "<x@example.org>"))""",
                    """* 2 FETCH (UID 8 ENVELOPE ("Mon, 5 May 2025 14:04:12 +0200" "Ho" ((NIL NIL "jane" "example.org")) NIL NIL ((NIL NIL "john" "example.com")) NIL NIL NIL "<y@example.org>"))""",
                    "$tag OK completed"
                )
                else -> listOf("$tag OK completed")
            }
        }

        /** Runs [block] against a fake server that never repeats the command name. */
        suspend fun withClient(block: suspend (ImapClient) -> Unit) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var server: ServerSocket? = null
            try {
                withTimeout(30.seconds) {
                    val running = scope.startServer(respond)
                    server = running
                    block(
                        ImapClient(
                            host = "127.0.0.1",
                            port = (running.localAddress as InetSocketAddress).port,
                            ssl = false,
                            auth = ImapClient.Auth.BasicAuth("user", "password"),
                            coroutineScope = scope
                        )
                    )
                }
            } finally {
                server?.close()
                scope.cancel()
            }
        }

        test("is recognised by getMailIds") {
            withClient { client ->
                ImapFolder(client, listOf("INBOX"), "/", null).getMailIds() shouldContainExactly listOf(1, 2)
            }
        }

        test("is recognised by getIdByUid") {
            withClient { client ->
                ImapFolder(client, listOf("INBOX"), "/", null).getIdByUid(8L) shouldBe 2
            }
        }

        test("is recognised by getFolders") {
            withClient { client ->
                client.getFolders().map { it.fullName } shouldContainExactly listOf("INBOX")
            }
        }

        test("is not handed to the FETCH parser") {
            withClient { client ->
                val mails = ImapFolder(client, listOf("INBOX"), "/", null).getMails {
                    getAll()
                    all()
                }.toList()
                mails.map { it.uid.await() } shouldContainExactly listOf(7L, 8L)
                mails.map { it.subject.await() } shouldContainExactly listOf("Hi", "Ho")
            }
        }
    }

    context("the state of a folder") {
        test("is what the server reports on SELECT") {
            withServerScope { scope ->
                val server = scope.startServer { tag, command ->
                    if (command.startsWith("SELECT")) listOf(
                        "* 172 EXISTS",
                        "* 1 RECENT",
                        "* OK [UNSEEN 12] Message 12 is first unseen",
                        "* OK [UIDVALIDITY 3857529045] UIDs valid",
                        "* OK [UIDNEXT 4392] Predicted next UID",
                        """* FLAGS (\Answered \Flagged \Deleted \Seen \Draft)""",
                        "$tag OK [READ-WRITE] SELECT completed",
                    ) else listOf("$tag OK completed")
                }

                ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null).state() shouldBe
                        FolderState(uidValidity = 3857529045, uidNext = 4392, exists = 172)
                server.close()
            }
        }

        test("is not asked twice on a connection that was just opened") {
            withServerScope { scope ->
                val commands = CopyOnWriteArrayList<String>()
                val server = scope.startServer { tag, command ->
                    commands += command
                    if (command.startsWith("SELECT")) listOf("* ${commands.size} EXISTS", "* OK [UIDVALIDITY 1] UIDs valid", "$tag OK done")
                    else listOf("$tag OK completed")
                }
                val folder = ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null)

                // LOGIN, SELECT: the answer to the second command is the state.
                folder.state().exists shouldBe 2
                commands.count { it.startsWith("SELECT") } shouldBe 1

                // Only once: afterwards the server is asked again.
                folder.state().exists shouldBe 3
                server.close()
            }
        }

        test("has no UIDNEXT if the server does not predict one") {
            withServerScope { scope ->
                val server = scope.startServer { tag, command ->
                    if (command.startsWith("SELECT")) listOf("* 0 EXISTS", "* OK [UIDVALIDITY 1] UIDs valid", "$tag OK done")
                    else listOf("$tag OK completed")
                }

                ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null).state() shouldBe
                        FolderState(uidValidity = 1, uidNext = null, exists = 0)
                server.close()
            }
        }
    }

    context("the status of a folder") {
        test("is asked without selecting the folder") {
            withServerScope { scope ->
                val commands = CopyOnWriteArrayList<String>()
                val server = scope.startServer { tag, command ->
                    commands += command
                    if (command.startsWith("STATUS")) listOf(
                        """* STATUS "Sent Items" (MESSAGES 231 UIDNEXT 44292 UIDVALIDITY 3857529045)""",
                        "$tag OK STATUS completed",
                    ) else listOf("$tag OK completed")
                }

                ImapFolder(clientFor(server, scope), listOf("Sent Items"), "/", null).status() shouldBe
                        FolderState(uidValidity = 3857529045, uidNext = 44292, exists = 231)
                commands.drop(1) shouldContainExactly listOf("""STATUS "Sent Items" (MESSAGES UIDNEXT UIDVALIDITY)""")
                server.close()
            }
        }

        test("fails if the server refuses it") {
            withServerScope { scope ->
                val server = scope.startServer { tag, command ->
                    if (command.startsWith("STATUS")) listOf("$tag NO Mailbox does not exist") else listOf("$tag OK completed")
                }

                shouldThrow<ImapCommandException> { ImapFolder(clientFor(server, scope), listOf("Nope"), "/", null).status() }
                server.close()
            }
        }
    }

    context("fetching by sequence number") {
        /** A folder with [exists] mails on a server that notes down what it is asked. */
        suspend fun folderOf(scope: CoroutineScope, exists: Int, commands: MutableList<String>): ImapFolder {
            val server = scope.startServer { tag, command ->
                commands += command
                when {
                    command.startsWith("SELECT") -> listOf("* $exists EXISTS", "* OK [UIDVALIDITY 1] UIDs valid", "$tag OK done")
                    command.startsWith("FETCH") -> {
                        // What a server makes of the set: "*" is the last mail, wherever the range starts.
                        val (from, to) = command.substringAfter("FETCH ").substringBefore(" ").split(":")
                            .map { if (it == "*") exists else it.toInt() }
                            .let { it.first() to it.last() }
                        (minOf(from, to)..maxOf(from, to)).map { fetchResponse(it, it + 100L, "Mail $it") } + "$tag OK completed"
                    }
                    else -> listOf("$tag OK completed")
                }
            }
            return ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null)
        }

        fun List<String>.fetches() = filter { it.startsWith("FETCH") }

        test("a range that starts behind the last mail returns nothing") {
            withServerScope { scope ->
                val commands = CopyOnWriteArrayList<String>()
                val folder = folderOf(scope, exists = 3, commands)

                // "FETCH 4:*" would be answered with mail 3.
                folder.getMails { idRange(4); all() }.toList().shouldBeEmpty()
                commands.fetches().shouldBeEmpty()
            }
        }

        test("an empty folder is not asked for its mails") {
            withServerScope { scope ->
                val commands = CopyOnWriteArrayList<String>()
                val folder = folderOf(scope, exists = 0, commands)

                // Some servers refuse "FETCH 1:*" there instead of answering nothing.
                folder.getMails { getAll(); all() }.toList().shouldBeEmpty()
                commands.fetches().shouldBeEmpty()
            }
        }

        test("a range is cut at the last mail") {
            withServerScope { scope ->
                val commands = CopyOnWriteArrayList<String>()
                val folder = folderOf(scope, exists = 3, commands)

                folder.getMails { idRange(2, 500); all() }.toList().map { it.uid.await() } shouldContainExactly listOf(102L, 103L)
                folder.getMails { getAll(); all() }.toList().size shouldBe 3
                commands.fetches() shouldContainExactly listOf("FETCH 2:3 (FLAGS ENVELOPE UID)", "FETCH 1:3 (FLAGS ENVELOPE UID)")
            }
        }

        test("ids the folder does not have are left out") {
            withServerScope { scope ->
                val commands = CopyOnWriteArrayList<String>()
                val folder = folderOf(scope, exists = 3, commands)

                folder.getMails { getIds(listOf(2, 9)); all() }.toList().map { it.uid.await() } shouldContainExactly listOf(102L)
                folder.getMails { getId(9); all() }.toList().shouldBeEmpty()
                commands.fetches() shouldContainExactly listOf("FETCH 2 (FLAGS ENVELOPE UID)")
            }
        }

        test("the size of the folder is taken from the SELECT its connection was opened with") {
            withServerScope { scope ->
                val commands = CopyOnWriteArrayList<String>()
                val folder = folderOf(scope, exists = 3, commands)

                folder.getMails { getAll(); all() }.toList()
                commands.count { it.startsWith("SELECT") } shouldBe 1

                // By then the folder may have changed.
                folder.getMails { getAll(); all() }.toList()
                commands.count { it.startsWith("SELECT") } shouldBe 2
            }
        }
    }

    context("fetching by uid") {
        test("sends one UID FETCH and no SEARCH before it") {
            withServerScope { scope ->
                val commands = CopyOnWriteArrayList<String>()
                val server = scope.startServer { tag, command ->
                    commands += command
                    if (command.startsWith("UID FETCH")) listOf(fetchResponse(1, 8, "Ho"), "$tag OK completed")
                    else listOf("$tag OK completed")
                }
                val folder = ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null)

                val mails = folder.getMails {
                    uidRange(8)
                    all()
                }.toList()

                mails.map { it.uid.await() } shouldContainExactly listOf(8L)
                commands.filter { !it.startsWith("LOGIN") && !it.startsWith("SELECT") } shouldContainExactly
                        listOf("UID FETCH 8:* (FLAGS ENVELOPE UID)")
                server.close()
            }
        }

        test("an open range with nothing new returns no mails") {
            withServerScope { scope ->
                // What a server answers to "UID FETCH 9:*" in a folder whose newest mail has uid 8.
                val server = scope.startServer { tag, command ->
                    if (command.startsWith("UID FETCH 9:*")) listOf(fetchResponse(2, 8, "Ho"), "$tag OK completed")
                    else listOf("$tag OK completed")
                }
                val folder = ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null)

                folder.getMails {
                    uidRange(9)
                    all()
                }.toList().shouldBeEmpty()
                server.close()
            }
        }

        test("an empty set of uids sends nothing") {
            withServerScope { scope ->
                val commands = CopyOnWriteArrayList<String>()
                val server = scope.startServer { tag, command ->
                    commands += command
                    listOf("$tag OK completed")
                }

                ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null)
                    .getMails { uids(emptyList()); all() }
                    .toList()
                    .shouldBeEmpty()
                commands.shouldBeEmpty()
                server.close()
            }
        }

        test("flags can be fetched without the envelope") {
            withServerScope { scope ->
                val server = scope.startServer { tag, command ->
                    if (command == "UID FETCH 1:* (FLAGS UID)") listOf(
                        """* 1 FETCH (UID 7 FLAGS (\Seen))""",
                        """* 2 FETCH (UID 8 FLAGS ())""",
                        "$tag OK completed",
                    ) else listOf("$tag OK completed")
                }
                val folder = ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null)

                val mails = folder.getMails {
                    uidRange(1)
                    flags = true
                    uid = true
                }.toList()

                mails.map { it.uid.await() to it.flags.await() } shouldContainExactly
                        listOf(7L to setOf(Email.Flag.Seen), 8L to emptySet())
                // Not a NotImplementedError: that is an Error, and no caller catches those.
                shouldThrow<IllegalStateException> { mails.first().subject.await() }.message shouldContain "subject"
                server.close()
            }
        }
    }

    context("a fetch as a flow") {
        test("emits a mail before the response is complete") {
            withServerScope { scope ->
                val firstCollected = CompletableDeferred<Unit>()
                val server = scope.startScriptedServer { input, output ->
                    while (true) {
                        val line = input.readLine() ?: break
                        val tag = line.substringBefore(' ')
                        if (!line.contains("FETCH")) {
                            output.writeStringUtf8("$tag OK completed\r\n")
                            continue
                        }
                        output.writeStringUtf8(fetchResponse(1, 7, "Hi") + "\r\n")
                        // A fetch collecting the whole response first never gets here.
                        firstCollected.await()
                        output.writeStringUtf8(fetchResponse(2, 8, "Ho") + "\r\n$tag OK completed\r\n")
                    }
                }
                val folder = ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null)

                val subjects = mutableListOf<String?>()
                folder.getMails { getAll(); all() }.collect {
                    subjects += it.subject.await()
                    firstCollected.complete(Unit)
                }

                subjects shouldContainExactly listOf("Hi", "Ho")
                server.close()
            }
        }

        test("goes on after a response it cannot read") {
            withServerScope { scope ->
                // The unreadable one carries a literal: what follows it must not be taken for the
                // next response.
                val broken = "* 2 FETCH (UID 8 ENVELOPE (\"sometime last week\" {2}\r\nHo" +
                        " ((NIL NIL \"jane\" \"example.org\")) NIL NIL NIL NIL NIL NIL \"<y@example.org>\"))"
                val server = scope.startServer { tag, command ->
                    if (command.startsWith("FETCH")) listOf(
                        fetchResponse(1, 7, "Hi"),
                        broken,
                        "* 3 EXISTS",
                        fetchResponse(3, 9, "Hu"),
                        "$tag OK completed",
                    ) else listOf("$tag OK completed")
                }
                val folder = ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null)

                val unreadable = mutableListOf<UnreadableMail>()
                val mails = folder.getMails {
                    getAll()
                    all()
                    onUnreadable { unreadable += it }
                }.toList()

                mails.map { it.uid.await() } shouldContainExactly listOf(7L, 9L)
                unreadable.map { it.sequenceNumber to it.uid } shouldContainExactly listOf(2L to 8L)
                server.close()
            }
        }

        test("fails on a response it cannot read if nobody takes it") {
            withServerScope { scope ->
                val server = scope.startServer { tag, command ->
                    if (command.startsWith("FETCH")) listOf(
                        """* 1 FETCH (UID 7 ENVELOPE ("sometime last week" NIL NIL NIL NIL NIL NIL NIL NIL NIL))""",
                        "$tag OK completed",
                    ) else listOf("$tag OK completed")
                }
                val folder = ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null)

                shouldThrow<IllegalArgumentException> { folder.getMails { getAll(); all() }.toList() }
                server.close()
            }
        }

        test("taking the only mail of a fetch keeps the connection") {
            withServerScope { scope ->
                val commands = CopyOnWriteArrayList<String>()
                val server = scope.startServer { tag, command ->
                    commands += command
                    if (command.startsWith("UID FETCH")) listOf(fetchResponse(1, 8, "Ho"), "$tag OK completed")
                    else listOf("$tag OK completed")
                }
                val folder = ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null)

                // first() leaves the flow at the mail, before the completion that follows it.
                repeat(3) { folder.getMails { getUid(8); all() }.first().uid.await() shouldBe 8L }

                commands.count { it.startsWith("LOGIN") } shouldBe 1
                server.close()
            }
        }

        test("collectors that download what they are handed do not wait for each other forever") {
            withServerScope { scope ->
                val message = "Subject: Hi\r\n\r\nBody\r\n".toByteArray()
                val server = scope.startRawServer { tag, command ->
                    when {
                        command.startsWith("UID FETCH") -> fetchBodyResponse(tag, message)
                        command.startsWith("FETCH") -> (fetchResponse(1, 7, "Hi") + "\r\n$tag OK completed\r\n").toByteArray()
                        else -> "$tag OK completed\r\n".toByteArray()
                    }
                }
                val folder = ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null)

                // As many fetches as the folder has connections, each of them holding one while
                // its collector asks for another.
                val collecting = AtomicInteger()
                val allCollecting = CompletableDeferred<Unit>()
                val bodies = (1..5).map {
                    async {
                        folder.getMails { getAll(); all() }.map { mail ->
                            if (collecting.incrementAndGet() == 5) allCollecting.complete(Unit)
                            allCollecting.await()
                            mail.getContent().text
                        }.toList()
                    }
                }.awaitAll()

                bodies shouldContainExactly List(5) { listOf("Body\r\n") }
                server.close()
            }
        }

        test("a collector that stops early does not leave the rest to the next command") {
            withServerScope { scope ->
                val server = scope.startServer { tag, command ->
                    if (command.startsWith("FETCH")) (1..500).map { fetchResponse(it, it + 100L, "Mail $it") } + "$tag OK completed"
                    else listOf("$tag OK completed")
                }
                val folder = ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null)

                folder.getMails { getAll(); all() }.first().uid.await() shouldBe 101L
                folder.getMails { getAll(); all() }.toList().size shouldBe 500
                server.close()
            }
        }
    }
})

/** The untagged FETCH response for a mail at [sequenceNumber], without its line break. */
private fun fetchResponse(sequenceNumber: Int, uid: Long, subject: String) =
    """* $sequenceNumber FETCH (UID $uid FLAGS (\Seen) ENVELOPE ("Mon, 5 May 2025 14:03:12 +0200" "$subject" """ +
            """((NIL NIL "jane" "example.org")) NIL NIL ((NIL NIL "john" "example.com")) NIL NIL NIL "<$uid@example.org>"))"""
