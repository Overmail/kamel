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
: Scope the sockets and the `Deferred` [message fields](message-fields.md) run in. Pass your own to control the
dispatcher or to use a `SupervisorJob`.

`debug` (default: `false`)
: Prints the IMAP conversation to standard output, see [](logging-and-debugging.md#debug).

`maxConnections` (default: `500`)
: Upper limit of open connections for this client, see [](connection-pooling.md).

All of them except `maxConnections` are readable as properties, for example `client.host`.

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
