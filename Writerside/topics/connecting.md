# Connecting

`ImapClient` is the entry point of Kamel. One client represents one account on one server.

```kotlin
val client = ImapClient(
    host = "imap.example.com",
    port = 993,
    ssl = true,
    auth = ImapClient.Auth.BasicAuth(username, password),
)
```

## Parameters

`host`, `port`
: Address of the IMAP server.

`ssl` (default: `true`)
: `true` wraps the connection in TLS right away, which is what port `993` expects. `false` talks plain text, as on
port `143`. `STARTTLS` is not supported, see [](known-limitations.md).

`auth`
: How to log in, see [](authentication.md).

`coroutineScope` (default: `CoroutineScope(Dispatchers.IO)`)
: Scope for what a connection needs while it is open: its selector and the TLS layer. Commands do not run here.
They run in the coroutine that calls them, so cancelling that coroutine cancels the command.

`readTimeout` (default: `60.seconds`)
: How long the server may stay silent while a response is being read. After that the connection is closed and
the command throws an [`ImapConnectionClosedException`](error-handling.md#closed). It limits the silence between
two reads, not the duration of a command, so a large download is not cut off. `Duration.INFINITE` waits forever.
It does not apply while [idling](idle.md).

`debug` (default: `false`)
: Prints the IMAP conversation to standard output, see [](logging-and-debugging.md#debug).

`maxConnections` (default: `500`)
: Upper limit of open connections for this client, see [](connection-pooling.md).

All of them except `maxConnections` are readable as properties, for example `client.host`.

`ImapClient` implements `MailClient`, see [](testing.md).

## Connections are opened on demand

Creating a client does not touch the network. The first command opens a connection and logs in. That is also the
moment a wrong host, a TLS problem or a wrong password shows up.

To find out early, call `testConnection()`:

```kotlin
try {
    client.testConnection()
} catch (e: ImapCommandException) {
    println("Login failed: ${e.response}")
}
```

`testConnection()` opens a connection, logs in and keeps the connection for the commands that follow.

## Closing

`ImapClient` is `AutoCloseable`. `close()` closes every connection the client opened, including those of its
folders and idle folders.

```kotlin
ImapClient(host = "imap.example.com", port = 993, auth = auth).use { client ->
    // work with the client
}
```

For a long-living client, call `close()` yourself when your application shuts down.

## What a client gives you

| Function | Returns | Described in |
|----------|---------|--------------|
| `testConnection()` | Nothing, throws on failure | This page |
| `getFolders(onlyRoot = false)` | `List<ImapFolder>` | [](folders.md) |
| `close()` | Nothing | This page |
