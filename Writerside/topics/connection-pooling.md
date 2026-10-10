# Connection pooling

An IMAP connection handles one command at a time. To let several coroutines work at once, Kamel keeps pools of
connections and opens new ones on demand. You do not have to manage them, but it helps to know what happens.

## Two levels

Client pool
: Connections that are logged in but have no folder selected. Used for `getFolders()`, `testConnection()` and the
[`status()`](folders.md#state) of a folder. Its
size is limited by `maxConnections` of [`ImapClient`](connecting.md), `500` by default.

Folder pool
: Every `ImapFolder` has its own pool of up to **five** connections. Each of them has that folder selected. Used
for `state()`, `getMailIds()`, `getIdByUid()`, `getMails { }` and message content.

An [`IdleFolder`](idle.md) takes one more connection of its folder and keeps it for itself.

A `getMails { }` flow holds its connection for as long as it is collected. Downloading the content of a mail
inside `collect { }` therefore runs on a second connection of the folder. Such a command never waits for a busy
connection: if the pool is full, it gets one beyond the limit, because the fetch it comes from cannot go on
before it is done.

Folder connections are opened through the client, so they count towards `maxConnections` as well.

## How a connection is chosen

For every command, the pool:

1. drops connections that are no longer alive, which includes those a cancelled or timed out command left in the
   middle of a response,
2. hands out a connection that is currently free, if there is one,
3. otherwise opens a new one, if the pool is not full,
4. otherwise picks one of the busy connections, and the command waits for its turn. A command issued from inside
   the collector of a fetch opens a connection instead, see above.

Opening a connection means connecting, logging in and, for a folder, selecting it. A command that needs a new
connection therefore takes a little longer than one that reuses an idle one.

## Working in parallel

Because of the pool, you can simply start several coroutines:

```kotlin
coroutineScope {
    mails.map { mail ->
        async { mail.getContent() }
    }.awaitAll()
}
```

Up to five downloads of the same folder run at the same time, the rest queue up.

## Server limits

Most providers limit the number of simultaneous IMAP connections per account, often to around 15. The limit
counts connections across all folders. If you work with many folders of one account at once, you can reach it,
and the server then refuses further logins with an error.

- Lower `maxConnections` to stay below the limit of your provider. Commands then wait instead of failing.
- Close folders you are done with: `folder.close()` closes the connections of that folder.

```kotlin
val client = ImapClient(
    host = "imap.example.com",
    port = 993,
    auth = auth,
    maxConnections = 10,
)
```

## Closing

`client.close()` closes every connection of the client and of all its folders. See [](connecting.md).
