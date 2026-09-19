package es.jvbabi.overmail.core

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
import kotlin.time.Duration.Companion.seconds

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
                    username = "user",
                    password = "password",
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
                        username = "user",
                        password = "password",
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
})

private fun selectingThreads() = Thread.getAllStackTraces().values.count { stack ->
    stack.any { it.className.endsWith("SelectorImpl") && it.methodName == "doSelect" }
}
