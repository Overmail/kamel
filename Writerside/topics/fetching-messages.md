# Fetching messages

`ImapFolder.getMails { }` fetches messages. The block configures a `FetchRequest`: which messages, and which
fields of them.

```kotlin
val mails: List<Email> = inbox.getMails {
    getAll()
    envelope = true
    flags = true
    uid = true
}
```

## Which messages {id="selection"}

Call one of these in the block. Without any of them, all messages are fetched. If you call several, the last one
wins.

`getAll()`
: Every message in the folder.

`getId(id)`
: The message with this [sequence number](folders.md#ids).

`getIds(ids)`
: The **range** from the smallest to the largest sequence number in the list. `getIds(listOf(3, 10))` fetches the
messages 3 to 10, not only those two.

`getUid(uid)`
: The message with this UID.

```kotlin
inbox.getMails { getId(42); all() }
inbox.getMails { getIds(listOf(1, 50)); all() }
inbox.getMails { getUid(15201); all() }
```

If nothing matches, for example an unknown UID or an empty folder, the result is an empty list.

## Which fields {id="fields"}

| Property | Fetches | Fills |
|----------|---------|-------|
| `envelope` | The IMAP envelope | `subject`, `sentAt`, `from`, `senders`, `replyTo`, `to`, `cc`, `bcc`, `messageId`, `inReplyTo` |
| `flags` | The flags | `flags` |
| `uid` | The UID | `uid` |

All three are `false` by default. `all()` sets all three to `true`.

> Always set `envelope = true`. Responses without an envelope are skipped at the moment, so a fetch without it
> returns an empty list.
{style="warning"}

Request `uid` whenever you want to download the body afterwards: [](message-content.md) needs it.

Reading the fields of the returned `Email` objects is described in [](message-fields.md).

## Fetching in pages

A fetch for a large folder returns one `Email` per message. To load a folder piece by piece, combine
`getMailIds()` with `getIds()`:

```kotlin
val ids = inbox.getMailIds()

ids.chunked(100).forEach { page ->
    val mails = inbox.getMails {
        getIds(page.map { it.toLong() })
        all()
    }
    // process the page
}
```

## Diagnosing parse errors {id="dump"}

If Kamel cannot parse the answer of a server, `getMails` logs an error and throws. Set `dumpMailOnError` to get the
offending response line, the position the parser stopped at and what it had parsed so far in the log:

```kotlin
inbox.getMails {
    getAll()
    all()
    dumpMailOnError = true
}
```

> The dump contains envelope data such as subjects and addresses. Leave it off in production logs.
{style="warning"}

Such a dump is the most helpful thing to attach to a [bug report](https://github.com/Overmail/kamel/issues).
