package es.jvbabi.overmail.kamel

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThan
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.ktor.utils.io.readLine
import io.ktor.utils.io.writeStringUtf8
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives [ImapClient] against [startServer], over the pool it shares with everything else on the
 * account.
 */
class EmailClientTest : FunSpec({

    test("a second LIST on the same client sees the folders too") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var server: ServerSocket? = null
        try {
            withTimeout(10.seconds) {
                val running = scope.startServer { tag, command ->
                    if (command.startsWith("LIST")) listOf(
                        "* LIST (\\HasNoChildren) \".\" \"INBOX\"",
                        "* LIST (\\HasNoChildren \\Sent) \".\" \"INBOX.Sent\"",
                        "$tag OK LIST completed",
                    ) else listOf("$tag OK completed")
                }
                server = running
                val client = ImapClient(
                    host = "127.0.0.1",
                    port = (running.localAddress as InetSocketAddress).port,
                    ssl = false,
                    auth = ImapClient.Auth.BasicAuth("user", "password"),
                    coroutineScope = scope,
                )

                client.getFolders().map { it.fullName } shouldContainExactly listOf("INBOX", "INBOX.Sent")

                // Came back empty: the first call closed the socket it borrowed from the pool, and
                // the pool handed that dead socket straight back. An importer reading this as a
                // result sees an account without a single folder.
                client.getFolders().map { it.fullName } shouldContainExactly listOf("INBOX", "INBOX.Sent")

                client.close()
            }
        } finally {
            server?.close()
            scope.cancel()
        }
    }

    test("closed clients leave no selector behind") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var server: ServerSocket? = null
        try {
            withTimeout(30.seconds) {
                val running = scope.startServer { tag, command ->
                    if (command.startsWith("LIST")) listOf(
                        "* LIST (\\HasNoChildren) \".\" \"INBOX\"",
                        "$tag OK LIST completed",
                    ) else listOf("$tag OK completed")
                }
                server = running
                val selecting = selectingThreads()

                // What an importer does on every cycle: a client, a folder on its own connection,
                // both closed again.
                repeat(50) {
                    ImapClient(
                        host = "127.0.0.1",
                        port = (running.localAddress as InetSocketAddress).port,
                        ssl = false,
                        auth = ImapClient.Auth.BasicAuth("user", "password"),
                        coroutineScope = scope,
                    ).use { client ->
                        client.getFolders().single().use { it.getMailIds() }
                    }
                }
                delay(1.seconds)

                // Each leaked selector kept a Dispatchers.IO thread spinning in select(), until
                // all 64 were taken and nothing else on Dispatchers.IO got to run.
                selectingThreads() shouldBeLessThan selecting + 5
            }
        } finally {
            server?.close()
            scope.cancel()
        }
    }

    test("a quiet connection that no longer answers is replaced before a command runs on it") {
        withServerScope { scope ->
            val log = PoolServerLog()
            // The first connection stops answering after its first LIST, the way one a NAT
            // forgot does; every connection after it answers.
            val server = scope.startPoolServer(log) { connection, commands -> connection == 1 && commands > 2 }
            val client = clientFor(
                server, scope,
                readTimeout = 20.seconds,
                checkConnectionsAfter = 200.milliseconds,
                connectionCheckTimeout = 300.milliseconds,
            )

            client.getFolders().map { it.fullName } shouldContainExactly listOf("INBOX")
            delay(400.milliseconds)

            val started = TimeSource.Monotonic.markNow()
            // Used to fail with an ImapConnectionClosedException, after the whole readTimeout.
            client.getFolders().map { it.fullName } shouldContainExactly listOf("INBOX")
            started.elapsedNow() shouldBeLessThan 5.seconds

            log.commands.filter { it.first == 1 }.map { it.second } shouldContainExactly listOf("LOGIN", "LIST", "NOOP")
            log.commands.filter { it.first == 2 }.map { it.second } shouldContainExactly listOf("LOGIN", "LIST")
            client.close()
            server.close()
        }
    }

    test("a quiet connection that still answers is checked and kept") {
        withServerScope { scope ->
            val log = PoolServerLog()
            val server = scope.startPoolServer(log) { _, _ -> false }
            val client = clientFor(server, scope, checkConnectionsAfter = 200.milliseconds)

            client.getFolders()
            delay(400.milliseconds)
            client.getFolders()

            log.commands.map { it.second } shouldContainExactly listOf("LOGIN", "LIST", "NOOP", "LIST")
            log.connections.get() shouldBe 1
            client.close()
            server.close()
        }
    }

    test("a connection used within the threshold gets no NOOP") {
        withServerScope { scope ->
            val log = PoolServerLog()
            val server = scope.startPoolServer(log) { _, _ -> false }
            val client = clientFor(server, scope, checkConnectionsAfter = 10.seconds)

            repeat(3) { client.getFolders() }

            log.commands.map { it.second } shouldContainExactly listOf("LOGIN", "LIST", "LIST", "LIST")
            client.close()
            server.close()
        }
    }

    test("a folder checks its quiet connections too") {
        withServerScope { scope ->
            val log = PoolServerLog()
            // The folder's first connection stops answering after its SELECT and first SEARCH.
            val server = scope.startPoolServer(log) { connection, commands -> connection == 1 && commands > 3 }
            val client = clientFor(
                server, scope,
                readTimeout = 20.seconds,
                checkConnectionsAfter = 200.milliseconds,
                connectionCheckTimeout = 300.milliseconds,
            )
            val folder = ImapFolder(client, listOf("INBOX"), "/", null)

            folder.getMailIds() shouldContainExactly listOf(1)
            delay(400.milliseconds)
            folder.getMailIds() shouldContainExactly listOf(1)

            log.commands.filter { it.first == 1 }.map { it.second } shouldContainExactly
                    listOf("LOGIN", "SELECT", "SEARCH", "NOOP")
            log.commands.filter { it.first == 2 }.map { it.second } shouldContainExactly
                    listOf("LOGIN", "SELECT", "SEARCH")
            client.close()
            server.close()
        }
    }
})

/** What the clients did on a server started with [startPoolServer]. */
private class PoolServerLog {
    /** Every command by the number of its connection, starting at 1, and its first word. */
    val commands = CopyOnWriteArrayList<Pair<Int, String>>()
    val connections = AtomicInteger()
}

/**
 * A server with one folder holding one mail. Stops answering on a connection once [isSilent]
 * says so for the connection and the number of commands it has received, but keeps reading what
 * comes in, as a peer that is gone without a FIN looks to the client.
 */
private suspend fun CoroutineScope.startPoolServer(
    log: PoolServerLog,
    isSilent: (connection: Int, commands: Int) -> Boolean,
): ServerSocket = startScriptedServer { input, output ->
    val connection = log.connections.incrementAndGet()
    var commands = 0
    while (true) {
        val line = input.readLine() ?: break
        val tag = line.substringBefore(' ')
        val command = line.substringAfter(' ').substringBefore(' ')
        log.commands += connection to command
        if (isSilent(connection, ++commands)) continue
        when (command) {
            "LIST" -> output.writeStringUtf8("* LIST (\\HasNoChildren) \".\" \"INBOX\"\r\n")
            "SELECT" -> output.writeStringUtf8("* 1 EXISTS\r\n* OK [UIDVALIDITY 1] UIDs valid\r\n")
            "SEARCH" -> output.writeStringUtf8("* SEARCH 1\r\n")
        }
        output.writeStringUtf8("$tag OK completed\r\n")
    }
}

private fun selectingThreads() = Thread.getAllStackTraces().values.count { stack ->
    stack.any { it.className.endsWith("SelectorImpl") && it.methodName == "doSelect" }
}
