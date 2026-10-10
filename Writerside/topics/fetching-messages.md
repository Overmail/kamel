# Fetching messages

`ImapFolder.getMails { }` fetches messages. The block configures a `FetchRequest`: which messages, and which
fields of them.

```kotlin
val mails: Flow<Email> = inbox.getMails {
    getAll()
    envelope = true
    flags = true
    uid = true
}

mails.collect { mail -> println(mail.subject.await()) }
```

## The result is a flow {id="flow"}

`getMails` sends nothing. The fetch runs when the flow is collected, and every collection fetches again. Each
message is emitted as soon as the server sent it, so a large folder is never held in memory as a whole.

Use the operators of `kotlinx.coroutines.flow` to get what you need:

```kotlin
val all: List<Email> = inbox.getMails { getAll(); all() }.toList()
val one: Email? = inbox.getMails { getUid(15201); all() }.firstOrNull()
```

The connection the fetch runs on is busy until the flow ends. Cancelling the collector cancels the fetch.

## Which messages {id="selection"}

Call one of these in the block. Without any of them, all messages are fetched. If you call several, the last one
wins.

By UID:

`getUid(uid)`
: The message with this UID.

`uids(uids)`
: Exactly the messages with these UIDs. UIDs the folder does not have are skipped.

`uidRange(from, to)`
: The messages with the UIDs `from` to `to`, both included. Without `to`, everything from `from` on.

By [sequence number](folders.md#ids):

`getAll()`
: Every message in the folder.

`getId(id)`
: The message with this sequence number.

`getIds(ids)`
: Exactly the messages with these sequence numbers.

`idRange(from, to)`
: The messages `from` to `to`, both included. Without `to`, everything up to the last message.

```kotlin
inbox.getMails { getUid(15201); all() }
inbox.getMails { uids(listOf(4, 9, 12)); all() }
inbox.getMails { uidRange(15202); all() }
inbox.getMails { getId(42); all() }
inbox.getMails { idRange(1, 50); all() }
```

The folder is not listed first, so the cost of a fetch depends on what it selects and not on the size of the
folder. A selection by UID is sent as it is, in one `UID FETCH`. A selection by sequence number is first cut to the
number of messages in the folder, which costs one `SELECT` unless the connection was just opened.

What is not there gives an empty flow: an unknown UID, a UID range without messages, a sequence number or a range
behind the last message, an empty collection or an empty folder.

### Fetching what is new {id="new"}

Remember the highest UID you have seen and ask for everything above it:

```kotlin
inbox.getMails {
    uidRange(lastKnownUid + 1)
    all()
}.collect { mail ->
    lastKnownUid = mail.uid.await()
}
```

If nothing arrived, the flow is empty. Check [`uidValidity`](folders.md#state) before you trust stored UIDs.

## Which fields {id="fields"}

| Property | Fetches | Fills |
|----------|---------|-------|
| `envelope` | The IMAP envelope | `subject`, `sentAt`, `from`, `senders`, `replyTo`, `to`, `cc`, `bcc`, `messageId`, `inReplyTo` |
| `flags` | The flags | `flags` |
| `uid` | The UID | `uid` |

All three are `false` by default. `all()` sets all three to `true`. A fetch by UID fills `uid` in any case.

Request `uid` whenever you want to download the body afterwards: [](message-content.md) needs it.

Leave out what you do not need. To see which messages were read or deleted since, the flags are enough:

```kotlin
inbox.getMails {
    uidRange(1)
    flags = true
    uid = true
}.collect { mail ->
    val seen = Email.Flag.Seen in mail.flags.await()
}
```

Reading the fields of the returned `Email` objects is described in [](message-fields.md).

## Messages Kamel cannot read {id="unreadable"}

If Kamel cannot parse the response for a message, the flow fails. Everything that was emitted before stays valid.

To lose only that message instead, take it with `onUnreadable`. The fetch then goes on with the next one:

```kotlin
inbox.getMails {
    uidRange(lastKnownUid + 1)
    all()
    onUnreadable { unreadable ->
        println("Could not read ${unreadable.uid}: ${unreadable.cause.message}")
    }
}.collect { mail ->
    // every message that could be read
}
```

| Property | Description |
|----------|-------------|
| `sequenceNumber` | The position of the message in the folder |
| `uid` | Its UID, or `null` if the response did not carry one that could be read |
| `cause` | What the parser failed with |

### Diagnosing parse errors {id="dump"}

Kamel logs an error for every response it cannot parse. Set `dumpMailOnError` to get the offending response
line, the position the parser stopped at and what it had parsed so far in the log:

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
