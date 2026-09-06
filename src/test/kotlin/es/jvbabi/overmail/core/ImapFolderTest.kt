package es.jvbabi.overmail.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
                        command.startsWith("SEARCH") -> "* SEARCH 1\r\n$tag OK SEARCH completed\r\n".toByteArray(Charsets.US_ASCII)
                        command.startsWith("FETCH") -> envelopeResponse(tag, subject)
                        else -> "$tag OK completed\r\n".toByteArray(Charsets.US_ASCII)
                    }
                }
                server = running
                val client = ImapClient(
                    host = "127.0.0.1",
                    port = (running.localAddress as InetSocketAddress).port,
                    ssl = false,
                    username = "user",
                    password = "password",
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
                            username = "user",
                            password = "password",
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
                }
                mails.map { it.uid.await() } shouldContainExactly listOf(7L, 8L)
                mails.map { it.subject.await() } shouldContainExactly listOf("Hi", "Ho")
            }
        }
    }
})
