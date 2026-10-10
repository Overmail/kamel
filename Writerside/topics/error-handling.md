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

Thrown when a command runs into a connection that is gone: closed by `close()`, or dropped by the server or the
network before the response was complete.

It is a `java.io.IOException`, so existing handling for I/O errors catches it.

The pool removes dead connections, so simply running the command again usually works: it gets a fresh connection.

```kotlin
suspend fun <T> retryOnce(block: suspend () -> T): T =
    try {
        block()
    } catch (e: ImapConnectionClosedException) {
        block()
    }

val ids = retryOnce { inbox.getMailIds() }
```

## Other exceptions

Connecting
: An unknown host, a refused connection or a failed TLS handshake surface as the exceptions of the JVM and of Ktor,
most of them an `IOException`.

Parsing
: A `FETCH` response Kamel cannot parse makes `getMails` throw, usually an `IllegalArgumentException`. See
[](fetching-messages.md#dump) for how to get details.

Fields that were not fetched
: Awaiting a field that was not part of the fetch throws a `NotImplementedError`. See [](message-fields.md).

## Where exceptions show up

Connections are opened lazily. A login problem is thrown by the first call that needs a connection, which can be
`getFolders()`, the first `getMails { }` on a folder, or `idle { }`. Call `testConnection()` to get it at a place
of your choice.

| Call | Can throw |
|------|-----------|
| `testConnection()`, `getFolders()` | Connection errors, `ImapCommandException` (login) |
| `getMailIds()`, `getIdByUid()`, `getMails { }` | The above, `ImapCommandException` (select or fetch), `ImapConnectionClosedException`, parse errors |
| `getContent()`, `getRawContent()` | The above |
| `idle { }` | Connection errors, `ImapCommandException`, `ImapConnectionClosedException` when the connection ends |
