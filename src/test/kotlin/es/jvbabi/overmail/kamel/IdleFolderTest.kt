package es.jvbabi.overmail.kamel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.network.sockets.ServerSocket
import io.ktor.utils.io.readLine
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a client did on a server started with [startIdleServer].
 */
private class IdleServerLog {
    /** Every line the clients sent, the tag left out. */
    val lines = CopyOnWriteArrayList<String>()
    val connections = AtomicInteger()

    /** Completed whenever a `DONE` was answered. */
    @Volatile
    var done = CompletableDeferred<Unit>()
}

/**
 * A server that speaks `IDLE`: it asks for the `DONE` with a continuation, sends [reports] while it
 * waits for it, and completes the command once it came. With [dropAfterReports] it closes the
 * connection instead of waiting.
 */
private suspend fun CoroutineScope.startIdleServer(
    log: IdleServerLog,
    reports: List<String>,
    dropAfterReports: Boolean = false,
): ServerSocket = startScriptedServer { input, output ->
    log.connections.incrementAndGet()
    var idleTag: String? = null
    while (true) {
        val line = input.readLine() ?: break
        if (line == "DONE") {
            log.lines += line
            output.writeStringUtf8("$idleTag OK IDLE terminated\r\n")
            log.done.complete(Unit)
            continue
        }
        val tag = line.substringBefore(' ')
        val command = line.substringAfter(' ')
        log.lines += command
        if (command != "IDLE") {
            output.writeStringUtf8("$tag OK completed\r\n")
            continue
        }
        idleTag = tag
        output.writeStringUtf8("+ idling\r\n")
        reports.forEach { output.writeStringUtf8("$it\r\n") }
        if (dropAfterReports) break
    }
}

/**
 * Drives [IdleFolder] against [startIdleServer].
 */
class IdleFolderTest : FunSpec({

    test("events reports what the server sends while idling") {
        withServerScope { scope ->
            val log = IdleServerLog()
            val server = scope.startIdleServer(
                log,
                listOf("* 23 EXISTS", "* 1 RECENT", "* 5 EXPUNGE", """* 7 FETCH (FLAGS (\Seen))"""),
            )
            val idleFolder = ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null).getIdleFolder()

            idleFolder.events().take(3).toList() shouldContainExactly listOf(
                IdleEvent.NewMessage(23),
                IdleEvent.RemovedMessage(5),
                IdleEvent.FlagsChanged(7, listOf(Email.Flag.Seen)),
            )
            server.close()
        }
    }

    test("a collector that is done ends the idling with DONE") {
        withServerScope { scope ->
            val log = IdleServerLog()
            val server = scope.startIdleServer(log, listOf("* 23 EXISTS"))
            val idleFolder = ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null).getIdleFolder()

            idleFolder.events().first()

            // Not "eventually": events() only returns once the server completed the IDLE.
            log.lines.takeLast(2) shouldContainExactly listOf("IDLE", "DONE")
            server.close()
        }
    }

    test("cancelling the collector ends the idling with DONE") {
        withServerScope { scope ->
            val log = IdleServerLog()
            val server = scope.startIdleServer(log, listOf("* 23 EXISTS"))
            val idleFolder = ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null).getIdleFolder()

            val seen = CompletableDeferred<IdleEvent>()
            val watcher = launch { idleFolder.events().collect { seen.complete(it) } }
            seen.await() shouldBe IdleEvent.NewMessage(23)

            // Now the collector waits in a read that only the cancellation can end.
            watcher.cancelAndJoin()

            log.lines.takeLast(2) shouldContainExactly listOf("IDLE", "DONE")
            server.close()
        }
    }

    test("an IDLE is renewed on the connection of the one before") {
        withServerScope { scope ->
            val log = IdleServerLog()
            val server = scope.startIdleServer(log, listOf("* 23 EXISTS"))
            val idleFolder = ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null).getIdleFolder()

            repeat(3) { idleFolder.events().first() shouldBe IdleEvent.NewMessage(23) }

            log.lines.filter { it == "IDLE" || it == "DONE" } shouldContainExactly
                    listOf("IDLE", "DONE", "IDLE", "DONE", "IDLE", "DONE")
            log.connections.get() shouldBe 1
            server.close()
        }
    }

    test("a second collector waits for the first instead of idling next to it") {
        withServerScope { scope ->
            val log = IdleServerLog()
            val server = scope.startIdleServer(log, listOf("* 23 EXISTS"))
            val idleFolder = ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null).getIdleFolder()

            val seen = List(2) { CompletableDeferred<Unit>() }
            val watchers = seen.map { launch { idleFolder.events().collect { _ -> it.complete(Unit) } } }

            // Whichever of the two got to watch; the other one has not sent an IDLE.
            val first = select { seen.forEachIndexed { index, deferred -> deferred.onAwait { index } } }
            log.lines.count { it == "IDLE" } shouldBe 1

            watchers[first].cancelAndJoin()
            seen[1 - first].await()

            log.connections.get() shouldBe 1
            watchers[1 - first].cancelAndJoin()
            server.close()
        }
    }

    test("idle hands sequence numbers to its callbacks") {
        withServerScope { scope ->
            val log = IdleServerLog()
            val server = scope.startIdleServer(log, listOf("* 23 EXISTS", "* 5 EXPUNGE"))
            val idleFolder = ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null).getIdleFolder()

            val added = CompletableDeferred<Long>()
            val removed = CompletableDeferred<Long>()
            val watcher = launch {
                idleFolder.idle {
                    onNewMessage { added.complete(it) }
                    onRemovedMessage { removed.complete(it) }
                }
            }

            added.await() shouldBe 23L
            removed.await() shouldBe 5L
            watcher.cancelAndJoin()
            server.close()
        }
    }

    test("a connection dropped while idling fails the collector") {
        withServerScope { scope ->
            val server = scope.startIdleServer(IdleServerLog(), listOf("* 23 EXISTS"), dropAfterReports = true)
            val idleFolder = ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null).getIdleFolder()

            shouldThrow<ImapConnectionClosedException> { idleFolder.events().collect { } }
            server.close()
        }
    }

    test("closing an idle folder fails its collector instead of leaving it waiting") {
        withServerScope { scope ->
            val server = scope.startIdleServer(IdleServerLog(), listOf("* 23 EXISTS"))
            val idleFolder = ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null).getIdleFolder()

            val seen = CompletableDeferred<Unit>()
            val failure = CompletableDeferred<Throwable?>()
            launch {
                failure.complete(runCatching { idleFolder.events().collect { seen.complete(Unit) } }.exceptionOrNull())
            }
            seen.await()
            idleFolder.close()

            (failure.await() is ImapConnectionClosedException) shouldBe true
            server.close()
        }
    }

    test("a server without IDLE fails the collector with its answer") {
        withServerScope { scope ->
            val server = scope.startServer { tag, command ->
                if (command == "IDLE") listOf("$tag BAD Unknown command") else listOf("$tag OK completed")
            }
            val folder = ImapFolder(clientFor(server, scope), listOf("INBOX"), "/", null)

            shouldThrow<ImapCommandException> { folder.getIdleFolder().events().collect { } }
            server.close()
        }
    }
})
