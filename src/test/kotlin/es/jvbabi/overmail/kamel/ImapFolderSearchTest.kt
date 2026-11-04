package es.jvbabi.overmail.kamel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.readLine
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.LocalDate
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

private val LITERAL = Regex("""\{(\d+)}$""")

/**
 * Reads one command off [input] the way a server does: a line that ends in a literal is answered
 * with a continuation, or with [refuseLiteral] if that is set, before the literal is read.
 *
 * @return the tag and the command as the client sent it, bytes of the literals included; `null`
 * once the connection is closed or the literal was refused
 */
private suspend fun readCommand(
    input: ByteReadChannel,
    output: ByteWriteChannel,
    refuseLiteral: ((tag: String) -> String)? = null,
): Pair<String, ByteArray>? {
    val command = ByteArrayOutputStream()
    var line = input.readLine() ?: return null
    val tag = line.substringBefore(' ')
    while (true) {
        command.write("$line\r\n".toByteArray(Charsets.UTF_8))
        val length = LITERAL.find(line)?.groupValues?.get(1)?.toInt() ?: break
        if (refuseLiteral != null) {
            output.writeStringUtf8(refuseLiteral(tag) + "\r\n")
            return tag to command.toByteArray()
        }
        output.writeStringUtf8("+ Ready for literal data\r\n")
        command.write(input.readByteArray(length))
        line = input.readLine() ?: return null
    }
    return tag to command.toByteArray()
}

/**
 * Drives [ImapFolder.search] against a fake server.
 */
class ImapFolderSearchTest : FunSpec({

    test("sends UID SEARCH and returns the UIDs the server found, lowest first") {
        withServerScope { scope ->
            val commands = CopyOnWriteArrayList<String>()
            val server = scope.startServer { tag, command ->
                commands += command
                if (command.startsWith("UID SEARCH")) listOf(
                    "* SEARCH 4294967295 12 7",
                    "* 3 EXISTS",
                    "* SEARCH 12",
                    "$tag OK SEARCH completed",
                ) else listOf("$tag OK completed")
            }
            val folder = ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null)

            val uids = folder.search {
                subject("Lunch")
                sentOn(LocalDate(2025, 5, 5))
            }

            uids shouldContainExactly listOf(7L, 12L, 4294967295L)
            commands.last() shouldBe """UID SEARCH SUBJECT "Lunch" SENTON 5-May-2025"""
        }
    }

    test("returns nothing if the server found nothing") {
        withServerScope { scope ->
            val server = scope.startServer { tag, command ->
                if (command.startsWith("UID SEARCH")) listOf("* SEARCH", "$tag OK SEARCH completed")
                else listOf("$tag OK completed")
            }
            val folder = ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null)

            folder.search { messageId("x@example.org") }.shouldBeEmpty()
        }
    }

    test("hands UIDs that getMails takes as they are") {
        withServerScope { scope ->
            val commands = CopyOnWriteArrayList<String>()
            val server = scope.startServer { tag, command ->
                commands += command
                when {
                    command.startsWith("UID SEARCH") -> listOf("* SEARCH 7 9", "$tag OK SEARCH completed")
                    command.startsWith("UID FETCH") -> listOf(
                        """* 1 FETCH (UID 7 FLAGS ())""",
                        """* 2 FETCH (UID 9 FLAGS ())""",
                        "$tag OK FETCH completed",
                    )
                    else -> listOf("$tag OK completed")
                }
            }
            val folder = ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null)

            val uids = folder.search { subject("Lunch") }
            val mails = folder.getMails { uids(uids); flags = true }.toList()

            mails.map { it.uid.await() } shouldContainExactly listOf(7L, 9L)
            commands.last() shouldBe "UID FETCH 7,9 (FLAGS)"
        }
    }

    test("sends a string that is not ASCII as a literal once the server asked for it") {
        withServerScope { scope ->
            val searches = CopyOnWriteArrayList<ByteArray>()
            val server = scope.startScriptedServer { input, output ->
                while (true) {
                    val (tag, command) = readCommand(input, output) ?: break
                    val text = command.toString(Charsets.UTF_8)
                    if (text.startsWith("$tag UID SEARCH")) {
                        searches += command
                        output.writeStringUtf8("* SEARCH 5\r\n$tag OK SEARCH completed\r\n")
                    } else output.writeStringUtf8("$tag OK completed\r\n")
                }
            }
            val folder = ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null)

            folder.search {
                subject("Grüße aus München")
                header("X-Mailer", "kamel")
            } shouldContainExactly listOf(5L)

            val sent = searches.single()
            val tag = sent.toString(Charsets.UTF_8).substringBefore(' ')
            // 17 characters, but 20 bytes: the size of a literal counts bytes.
            sent shouldBe ("$tag UID SEARCH CHARSET UTF-8 SUBJECT {20}\r\n" +
                    "Grüße aus München" +
                    " HEADER \"X-Mailer\" \"kamel\"\r\n").toByteArray(Charsets.UTF_8)
        }
    }

    test("fails if the server refuses the literal, and keeps the connection") {
        withServerScope { scope ->
            val connections = java.util.concurrent.atomic.AtomicInteger()
            val server = scope.startScriptedServer { input, output ->
                connections.incrementAndGet()
                while (true) {
                    val (tag, command) = readCommand(input, output) { tag ->
                        "$tag NO [BADCHARSET (US-ASCII)] The charset is not supported"
                    } ?: break
                    val text = command.toString(Charsets.UTF_8)
                    when {
                        text.startsWith("$tag UID SEARCH CHARSET") -> Unit // refused above
                        text.startsWith("$tag UID SEARCH") ->
                            output.writeStringUtf8("* SEARCH 3\r\n$tag OK SEARCH completed\r\n")
                        else -> output.writeStringUtf8("$tag OK completed\r\n")
                    }
                }
            }
            val folder = ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null)

            val error = shouldThrow<ImapCommandException> { folder.search { subject("Grüße") } }
            error.message shouldContain "BADCHARSET"

            folder.search { subject("Hello") } shouldContainExactly listOf(3L)
            connections.get() shouldBe 1
        }
    }

    test("fails if the server refuses the search") {
        withServerScope { scope ->
            val server = scope.startServer { tag, command ->
                if (command.startsWith("UID SEARCH")) listOf("$tag BAD Unknown search criterion")
                else listOf("$tag OK completed")
            }
            val folder = ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null)

            shouldThrow<ImapCommandException> { folder.search { subject("Lunch") } }
        }
    }
})
