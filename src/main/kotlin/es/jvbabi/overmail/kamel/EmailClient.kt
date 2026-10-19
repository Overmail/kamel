package es.jvbabi.overmail.kamel

import es.jvbabi.overmail.kamel.parser.FolderListParser
import es.jvbabi.overmail.kamel.parser.ImapStatus
import es.jvbabi.overmail.kamel.parser.SelectResponseParser
import es.jvbabi.overmail.kamel.parser.TaggedResponseParser
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.network.tls.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class ImapClient(
    val host: String,
    val port: Int,
    val ssl: Boolean = true,
    val auth: Auth,
    /**
     * Runs what a connection needs for as long as it is open: its selector and, with [ssl], the
     * TLS record layer. Commands do not run here, they run in the context of their caller.
     */
    val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    val debug: Boolean = false,
    maxConnections: Int = 500,
    /**
     * How long the server may stay silent while a response is being read. After that the
     * connection is closed and the command throws an [ImapConnectionClosedException].
     * [Duration.INFINITE] waits forever.
     */
    val readTimeout: Duration = 60.seconds,
    /**
     * How long a pooled connection may go without a command before it is checked with a `NOOP`
     * the next time it is handed out. A NAT or a firewall drops a quiet connection without telling
     * either side, and a command on it would only fail after [readTimeout]. A connection used more
     * recently costs no extra round trip. [Duration.INFINITE] never checks.
     */
    val checkConnectionsAfter: Duration = 60.seconds,
    /**
     * How long the server gets to answer that `NOOP`. A connection that does not answer in time is
     * closed and replaced, and the command runs on the replacement.
     */
    val connectionCheckTimeout: Duration = 5.seconds,
): ClosableClientPool(
    maxPoolSize = maxConnections,
    checkConnectionsAfter = checkConnectionsAfter,
    connectionCheckTimeout = connectionCheckTimeout,
    factory = {
        // One per connection, closed with it: a selector manager only lets go of its selector once
        // it is closed, and one that was left open kept a Dispatchers.IO thread spinning in
        // select() after its socket was gone. Enough of them starved every IO coroutine.
        val selectorManager = SelectorManager(coroutineScope.coroutineContext)
        val instance = try {
            // Lets the kernel notice a connection that died silently, which the NOOP of the pool
            // only does when the connection is handed out. Without tuning the system that takes
            // hours, so it is a supplement and not what replaces a dead connection in time.
            val tcpSocket = aSocket(selectorManager).tcp().connect(host, port) { keepAlive = true }
            val socket = try {
                if (ssl) tcpSocket.tls(coroutineScope.coroutineContext) else tcpSocket
            } catch (e: Throwable) {
                tcpSocket.close()
                throw e
            }
            SocketInstance(
                socket = socket,
                isDebug = debug,
                input = socket.openReadChannel(),
                output = socket.openWriteChannel(autoFlush = true),
                selectorManager = selectorManager,
                readTimeout = readTimeout,
            )
        } catch (e: Throwable) {
            selectorManager.close()
            throw e
        }
        // Not in the pool yet, so nothing else would ever close it.
        try {
            instance.login(auth)
        } catch (e: Throwable) {
            instance.close()
            throw e
        }
        instance
    },
    name = "ImapClient/${auth.username}@$host:$port"
), MailClient {
    private val logger = LoggerFactory.getLogger(this::class.java)

    /**
     * How the client logs in on every connection it opens.
     */
    sealed class Auth {
        abstract val username: String

        /** `LOGIN` with a username and a password. */
        data class BasicAuth(override val username: String, val password: String) : Auth() {
            // Keeps the password out of logs and exception messages.
            override fun toString() = "BasicAuth(username=$username, password=***)"
        }

        /**
         * `AUTHENTICATE XOAUTH2` with an OAuth 2.0 access token, as Gmail and Outlook expect it.
         * [bearer] is the bare access token, without the `Bearer ` prefix.
         */
        data class BearerAuth(override val username: String, val bearer: String) : Auth() {
            override fun toString() = "BearerAuth(username=$username, bearer=***)"
        }
    }

    suspend fun testConnection() {
        this.getClient()
    }

    override suspend fun getFolders(onlyRoot: Boolean): List<ImapFolder> {
        // Borrowed, not owned: closing it here left the instance in the pool, and the next command
        // got the dead socket handed back -- its LIST answered nothing, so the account looked like
        // it had no folders at all.
        val command = buildString {
            append("LIST \"\" \"")
            if (onlyRoot) append("\"")
            else append("*")
            append("\"")
        }

        return this.getClient().execute(command) {
            val folders = mutableListOf<ImapFolder>()
            while (true) {
                val line = next()?.first() ?: break
                val folder = FolderListParser.parse(line)
                if (folder != null) folders.add(ImapFolder(this@ImapClient, folder.path, folder.delimiter, folder.specialType))
                else logger.warn("Failed to parse folder: $line")
            }
            folders
        }
    }
}

typealias SocketInstanceFactory = suspend () -> SocketInstance

/**
 * One connection to the server.
 *
 * A command runs in the coroutine that sent it: there is no reader working in the background, so
 * cancelling the caller cancels the read. A response that was not read to its end leaves bytes of
 * it on the socket, which the next command would take for its own; unless they can be skipped
 * right away, such a connection is closed, and the pool it came from replaces it.
 */
data class SocketInstance(
    val socket: Socket,
    val input: ByteReadChannel,
    val output: ByteWriteChannel,
    val isDebug: Boolean,
    /** The selector [socket] was opened on, if it belongs to this instance alone. */
    private val selectorManager: SelectorManager? = null,
    /** See [ImapClient.readTimeout]. */
    private val readTimeout: Duration = Duration.INFINITE,
    /** How long the server gets to complete an `IDLE` after `DONE`, see [idle]. */
    private val doneTimeout: Duration = DONE_TIMEOUT,
) : AutoCloseable {

    private val id = System.currentTimeMillis()
    private var lastCommandId: Int = 0
    internal val commandMutex = Mutex()

    @Volatile
    private var isClosed = false

    /**
     * What the server answered to the `SELECT` this connection was opened with, for as long as no
     * other command ran on it: a caller asking for the state of the folder right after does not
     * have to select it a second time.
     */
    @Volatile
    internal var freshSelect: SelectResponseParser? = null

    /**
     * When the server last completed a command on this connection, or when it was opened: how
     * long it has been since the connection was last seen working, see [isQuietFor].
     */
    @Volatile
    private var lastCompletion: TimeMark = TimeSource.Monotonic.markNow()

    /** Whether the server greeting was read; it precedes the response to the first command. */
    private var isGreeted = false

    /** Whether this instance can still carry a command, see [ClosableClientPool.getClient]. */
    internal val isAlive: Boolean get() = !isClosed && !input.isClosedForRead

    /** Whether no command completed on this connection for [duration]. */
    internal fun isQuietFor(duration: Duration): Boolean = lastCompletion.elapsedNow() > duration

    /**
     * Takes [commandMutex] for [checkClaimed] if it is free. Does not suspend, so the pool can do it
     * under its own lock: a connection is claimed and taken off the free ones in one step, and no
     * second caller gets it handed out while it is being checked.
     */
    internal fun tryClaimForCheck(): Boolean = commandMutex.tryLock(CHECK_OWNER)

    /**
     * Sends a `NOOP` on a connection [tryClaimForCheck] claimed and releases it again. Whether the
     * server completed it within [timeout]; one that did not is closed.
     *
     * The `NOOP` drops [freshSelect] like any other command. Only a connection that has been quiet
     * for a while is checked, and what its `SELECT` once answered is no longer the state of the
     * folder anyway.
     */
    internal suspend fun checkClaimed(timeout: Duration): Boolean = try {
        val response = send("NOOP")
        while (response.line(timeout) != null) { }
        true
    } catch (e: Exception) {
        // Silent, gone, or cancelled halfway through the answer: in each case nobody can tell what
        // is left on the socket. And a server that refuses a NOOP is not one to send the next
        // command to either.
        close()
        if (e is CancellationException) throw e
        false
    } finally {
        commandMutex.unlock(CHECK_OWNER)
    }

    /**
     * Sends [command] and lets [block] read its response. Whatever [block] left unread is read
     * and dropped, so the command is complete when this returns.
     *
     * @throws ImapCommandException if the server answered with `NO` or `BAD`
     * @throws ImapConnectionClosedException if the connection ended or stayed silent for
     * [readTimeout] before the tagged completion
     */
    internal suspend fun <T> execute(command: String, block: suspend Response.() -> T): T = commandMutex.withLock(owner()) {
        val response = send(command)
        try {
            val result = response.block()
            while (response.next() != null) { }
            result
        } catch (e: Throwable) {
            // Cancelled or failed somewhere in the middle of the response. If that was between two
            // reads, as for a collector that has seen enough, the rest may be there already: a
            // moment to skip it is cheaper than a new connection.
            if (!response.isComplete && response.isAligned && !isClosed) withContext(NonCancellable) {
                withTimeoutOrNull(SKIP_TIMEOUT) { runCatching { while (response.next() != null) { } } }
            }
            if (!response.isComplete) close()
            throw e
        }
    }

    /** Sends [command] and waits for its tagged completion, see [execute]. */
    internal suspend fun execute(command: String) = execute(command) { }

    /**
     * Executes [command] and hands the raw bytes of every literal (`{n}`) in its response to
     * [onLiteralChunk], chunk by chunk. Everything around the literals - the untagged response
     * lines, the closing paren, the tagged completion - is consumed and dropped.
     *
     * [Response.next] cannot do this: it decodes a literal to text and holds it as a whole, which
     * is fine for an envelope and wrong for `BODY[]`.
     *
     * @throws ImapCommandException if the server answered with `NO` or `BAD`
     */
    internal suspend fun executeWithLiterals(command: String, onLiteralChunk: suspend (ByteArray) -> Unit) =
        execute(command) {
            while (true) {
                val line = line() ?: break
                val length = literalLength(line) ?: continue
                streamLiteral(this, length, onLiteralChunk)
            }
        }

    /**
     * Sends `IDLE` and hands every line the server reports to [onLine] until the caller is
     * cancelled or [onLine] throws. Either way the idling is ended with `DONE` and its completion
     * awaited, so the connection can carry the next command, the next `IDLE` included.
     *
     * [readTimeout] does not apply while idling: a quiet folder is what an `IDLE` waits on. The
     * completion of `DONE` is only waited for [doneTimeout], or [readTimeout] if that is shorter:
     * a healthy server answers it at once, and a connection that died while idling would otherwise
     * be held for the whole [readTimeout] before the caller learns of it.
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc2177">RFC 2177 - IMAP4 IDLE command</a>
     */
    internal suspend fun idle(onLine: suspend (String) -> Unit): Unit = commandMutex.withLock(owner()) {
        val response = send("IDLE")
        try {
            var isIdling = false
            while (true) {
                val line = response.line(if (isIdling) Duration.INFINITE else readTimeout) ?: break
                if (line.startsWith("+")) isIdling = true
                else onLine(line)
            }
        } catch (e: Throwable) {
            if (!response.isComplete && !isClosed) withContext(NonCancellable) {
                try {
                    write("DONE")
                    // A line the cancellation interrupted may be lost, which does not matter here:
                    // only the tagged completion is looked for.
                    while (response.line(minOf(doneTimeout, readTimeout)) != null) { }
                } catch (_: Exception) {
                    close()
                }
            }
            throw e
        }
    }

    /**
     * Writes [command] under a new tag. Has to be called with [commandMutex] held, and its
     * [Response] read to the end before the mutex is released.
     */
    private suspend fun send(command: String): Response {
        // Before awaiting the greeting: a socket closed while waiting for one never gets it.
        if (isClosed) throw ImapConnectionClosedException("The connection is closed, cannot run $command")
        freshSelect = null
        try {
            awaitGreeting()
            val tag = "A${(lastCommandId++).toString().padStart(3, '0')}"
            val message = "$tag $command"
            write(message)
            return Response(tag, message)
        } catch (e: Throwable) {
            // Cancelled or failed with the greeting half read or the command half written: the
            // server may still answer a command nobody is going to read the response of.
            close()
            throw e
        }
    }

    /**
     * The coroutine a command runs in owns the connection for that long, which is how
     * [isHeldBy] tells a command that is issued from inside another one.
     */
    private suspend fun owner(): Job? = currentCoroutineContext()[Job]

    /**
     * Whether [job] or one of its parents is in the middle of a command on this connection, for
     * example as the collector of a fetch that is still being read.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun isHeldBy(job: Job?): Boolean = generateSequence(job) { it.parent }.any { commandMutex.holdsLock(it) }

    private suspend fun awaitGreeting() {
        if (isGreeted) return
        while (true) {
            val line = read(readTimeout, "waiting for the server greeting") { input.readLine() }
                ?: throw closed("The connection closed before the server greeting")
            if (isDebug) println("SI $id < $line")
            if (line.startsWith("* OK")) break
        }
        isGreeted = true
    }

    private suspend fun write(line: String) {
        try {
            this.output.writeStringUtf8("$line\r\n")
        } catch (e: IOException) {
            throw closed("The connection closed while sending a command", e)
        }
        if (isDebug) println("SI $id > $line")
    }

    /**
     * Runs one read on the socket under [timeout]. A read that fails or times out leaves the
     * stream at an unknown position, so the connection is closed with it.
     */
    private suspend fun <T> read(timeout: Duration, activity: String, block: suspend () -> T): T {
        try {
            if (!timeout.isFinite()) return block()
            // Boxed, since null is what a read at the end of the stream returns.
            val result = withTimeoutOrNull(timeout) { ReadResult(block()) }
                ?: throw closed("The server did not answer within $timeout while $activity")
            return result.value
        } catch (e: IOException) {
            if (e is ImapConnectionClosedException) throw e
            throw closed("The connection failed while $activity", e)
        } catch (e: CancellationException) {
            // A read on a socket that close() took away ends cancelled although nobody cancelled
            // the caller, who would otherwise stop without an error.
            currentCoroutineContext().ensureActive()
            throw closed("The connection was closed while $activity", e)
        }
    }

    private class ReadResult<T>(val value: T)

    private fun closed(message: String, cause: Throwable? = null): ImapConnectionClosedException {
        close()
        return ImapConnectionClosedException(message, cause)
    }

    /**
     * Reads a literal of [length] bytes - the size the server announced, in bytes, not in
     * characters - and passes it on in chunks.
     */
    private suspend fun streamLiteral(response: Response, length: Int, onChunk: suspend (ByteArray) -> Unit) {
        var remaining = length
        response.isAligned = false
        while (remaining > 0) {
            val chunk = ByteArray(minOf(remaining, LITERAL_CHUNK_SIZE))
            read(readTimeout, "reading the response to ${response.message}") {
                try {
                    this.input.readFully(chunk, 0, chunk.size)
                } catch (e: IOException) {
                    throw closed("The connection closed inside a literal of the response to ${response.message}: ${length - remaining} of $length bytes arrived", e)
                }
            }
            onChunk(chunk)
            remaining -= chunk.size
        }
        response.isAligned = true
    }

    /**
     * The response to one command, read from the socket as it is asked for.
     *
     * @param message the command as it was sent, tag included
     */
    internal inner class Response(val tag: String, val message: String) {
        /** Whether the tagged completion was read, after which the socket is free for the next command. */
        var isComplete = false
            private set

        /**
         * Whether the next byte on the socket starts a line. Not while a read is under way or was
         * interrupted, and not inside a literal.
         */
        var isAligned = true

        /**
         * The next untagged response, or `null` once the command completed with `OK`.
         *
         * A response is one line, unless it carries literals: then it is the line up to the
         * announced literal, the literal, the rest of the line, and so on, each as its own item.
         * Framing it here keeps a response a parser gave up on from spilling into the next one.
         *
         * A literal is cut off the socket over its byte count: `{n}` counts bytes, while the line
         * around it is decoded already, so splitting it off a line would cut at the wrong place as
         * soon as it carries non ASCII bytes.
         */
        suspend fun next(): List<String>? {
            var line = line() ?: return null
            var length = literalLength(line) ?: return listOf(line)

            val parts = mutableListOf(line)
            while (true) {
                val literal = ByteArrayOutputStream(length)
                streamLiteral(this, length) { literal.write(it) }
                parts += literal.toString(Charsets.UTF_8)
                // What follows a literal belongs to the response, whatever it starts with.
                line = rawLine(readTimeout)
                parts += line
                length = literalLength(line) ?: return parts
            }
        }

        /**
         * The next line, or `null` once the command completed with `OK`. Does not know about
         * literals: the caller has to take one off the socket before asking for the next line.
         */
        suspend fun line(timeout: Duration = readTimeout): String? {
            if (isComplete) return null
            val line = rawLine(timeout)
            // NO and BAD complete the command just like OK does.
            return when (TaggedResponseParser.parse(line, tag)) {
                ImapStatus.OK -> {
                    complete()
                    null
                }
                ImapStatus.NO, ImapStatus.BAD -> {
                    complete()
                    throw ImapCommandException(message, line)
                }
                null -> line
            }
        }

        private fun complete() {
            isComplete = true
            lastCompletion = TimeSource.Monotonic.markNow()
        }

        /** Answers a continuation request (`+`) of the server. */
        suspend fun answer(line: String) = write(line)

        private suspend fun rawLine(timeout: Duration): String {
            // The response ended without its tagged completion: the connection is gone. Ending
            // the command as a success here is what made a dead socket look like an empty answer
            // to whatever was asked.
            isAligned = false
            val line = read(timeout, "reading the response to $message") { input.readLine() }
                ?: throw closed("The connection closed while reading the response to $message")
            isAligned = true
            if (isDebug) println("SI $id < $line")
            return line
        }
    }

    internal suspend fun loginUsernamePassword(username: String, password: String) {
        execute("LOGIN \"$username\" \"$password\"")
    }

    internal suspend fun login(auth: ImapClient.Auth) {
        when (auth) {
            is ImapClient.Auth.BasicAuth -> loginUsernamePassword(auth.username, auth.password)
            is ImapClient.Auth.BearerAuth -> loginOauth(auth.username, auth.bearer)
        }
    }

    /**
     * Logs in with SASL XOAUTH2.
     *
     * The credentials are sent as the answer to the first continuation instead of as an initial
     * response, which would need SASL-IR. A rejected token is answered with a second continuation
     * carrying the error; the server only sends its tagged `NO` once that one got an empty answer,
     * so without it the login would wait forever.
     *
     * @see <a href="https://developers.google.com/workspace/gmail/imap/xoauth2-protocol">XOAUTH2</a>
     */
    internal suspend fun loginOauth(email: String, bearer: String) {
        val credentials = Base64.encode("user=$email\u0001auth=Bearer $bearer\u0001\u0001".toByteArray(Charsets.UTF_8))
        execute("AUTHENTICATE XOAUTH2") {
            var credentialsSent = false
            while (true) {
                val line = line() ?: break
                if (!line.startsWith("+")) continue
                answer(if (credentialsSent) "" else credentials)
                credentialsSent = true
            }
        }
    }

    override fun close() {
        isClosed = true
        this.socket.close()
        this.selectorManager?.close()
    }

    companion object {
        /**
         * A literal announces its size in bytes at the end of a response line, e.g.
         * `* 12 FETCH (BODY[] {5678}`.
         *
         * @see <a href="https://www.rfc-editor.org/rfc/rfc3501#section-4.3">RFC 3501 - 4.3. Literals</a>
         */
        private val LITERAL_LENGTH_REGEX = Regex("""\{(\d+)\+?}$""")

        private fun literalLength(line: String): Int? =
            LITERAL_LENGTH_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull()

        private const val LITERAL_CHUNK_SIZE = 8 * 1024

        /** How long the rest of a response nobody wants any more is read before the connection is given up. */
        private val SKIP_TIMEOUT = 200.milliseconds

        /**
         * How long a server gets to complete an `IDLE` after `DONE`. Long enough for a slow server
         * that is still there, short enough not to hold on to one that is not.
         */
        internal val DONE_TIMEOUT = 10.seconds

        /**
         * Holds [commandMutex] while the pool checks the connection. Not a [Job], so [isHeldBy]
         * never takes a check for a command of its caller.
         */
        private val CHECK_OWNER = Any()
    }
}
