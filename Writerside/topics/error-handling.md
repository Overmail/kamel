# Error handling

Kamel reports failures with exceptions. There are two of its own, and a few that come from below.

## ImapCommandException {id="command"}

Thrown when the server answers a command with `NO` or `BAD`: wrong credentials, a folder that does not exist any
more, a command the server does not accept.

| Property | Description |
|----------|-------------|
| `command` | The command that was sent, including its tag, for example `A003 SELECT "Archive"` |
| `response` | The line the server answered with, for example `A003 NO Mailbox doesn't exist` |
| `message` | Both of them combined |

```kotlin
try {
    client.testConnection()
} catch (e: ImapCommandException) {
    println("The server said: ${e.response}")
}
```

> For a failed password login, `command` and `message` contain the password. Log `response` instead. See
> [](authentication.md).
{style="warning"}

## ImapConnectionClosedException {id="closed"}

Thrown when a command runs into a connection that is gone: closed by `close()`, dropped by the server or the
network before the response was complete, or silent for longer than the
[`readTimeout`](connecting.md) of the client.

No command returns a partial result instead: a folder list, a fetch or a message body that ended before the
server completed it throws, and so does a body that is shorter than the server announced.

It is a `java.io.IOException`, so existing handling for I/O errors catches it.

The pool removes dead connections, so simply running the command again usually works: it gets a fresh connection.

```kotlin
suspend fun <T> retryOnce(block: suspend () -> T): T =
    try {
        block()
    } catch (e: ImapConnectionClosedException) {
        block()
    }

val state = retryOnce { inbox.state() }
```

## Other exceptions

Connecting
: An unknown host, a refused connection or a failed TLS handshake surface as the exceptions of the JVM and of Ktor,
most of them an `IOException`.

Parsing
: A `FETCH` response Kamel cannot parse makes the `getMails` flow fail, usually with an
`IllegalArgumentException`, unless you take such responses with
[`onUnreadable`](fetching-messages.md#unreadable). See [](fetching-messages.md#dump) for how to get details.

Fields that were not fetched
: Awaiting a field that was not part of the fetch throws an `IllegalStateException`. See [](message-fields.md).

Cancellation
: Cancelling the coroutine that runs a command cancels the command. The connection it ran on is reused if the
rest of the response could be skipped, and closed otherwise.

## Where exceptions show up

Connections are opened lazily. A login problem is thrown by the first call that needs a connection, which can be
`getFolders()`, collecting the first `getMails { }` of a folder, or `idle { }`. Call `testConnection()` to get it at a place
of your choice.

| Call | Can throw |
|------|-----------|
| `testConnection()`, `getFolders()` | Connection errors, `ImapCommandException` (login) |
| `state()`, `status()`, `getMailIds()`, `getIdByUid()` | The above, `ImapCommandException` (select), `ImapConnectionClosedException` |
| Collecting `getMails { }` | The above, `ImapCommandException` (fetch), parse errors |
| `getContent()`, `getRawContent()` | The above |
| `idle { }`, collecting `events()` | Connection errors, `ImapCommandException`, `ImapConnectionClosedException` when the connection ends |
