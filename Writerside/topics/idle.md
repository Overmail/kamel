# Waiting for changes (IDLE)

With IMAP `IDLE`, the server tells the client about changes in a folder as they happen. Kamel wraps this in
`IdleFolder`.

```kotlin
val idleFolder = inbox.getIdleFolder()

val watcher = launch {
    idleFolder.idle {
        onNewMessage { sequenceNumber -> println("new message: $sequenceNumber") }
        onRemovedMessage { sequenceNumber -> println("removed: $sequenceNumber") }
        onFlagChanged { sequenceNumber, flags -> println("flags of $sequenceNumber: $flags") }
    }
}

// later
watcher.cancel()
```

## Starting

`idle { }` sends `IDLE` on a connection of its own and **suspends until the idling ends**. Start it in a coroutine
you can let run in the background.

## Callbacks {id="callbacks"}

Register any number of callbacks of each kind. All of them are called.

| Callback | Called when | Arguments |
|----------|-------------|-----------|
| `onNewMessage` | The server reports a new message count (`EXISTS`) | The new number of messages, which is also the sequence number of the newest one |
| `onRemovedMessage` | A message was removed (`EXPUNGE`) | The sequence number the message had |
| `onFlagChanged` | Flags of a message changed (`FETCH ... FLAGS`) | The sequence number and the new list of [flags](message-fields.md#flags) |

> The first parameter of the callbacks is named `messageUid` in the API, but it carries the
> [sequence number](folders.md#ids) the server sent, not a UID. Pass it to `getId()`, not to `getUid()`.
{style="warning"}

The callbacks are regular functions. To call a `suspend` function such as `getMails`, hand the value over to
another coroutine, for example through a `Channel`. [](tutorial-watch-mailbox.md) shows this step by step.

## Stopping {id="stopping"}

Cancel the coroutine that runs `idle { }`. The callbacks stop immediately:

```kotlin
watcher.cancel()
```

The connection the idling ran on stays open until its folder or the client is closed, so close those when you are
done watching.

`IdleFolder` also has `cancel()` and `close()` (it is `AutoCloseable`), which are meant to end the idling by
sending `DONE` to the server.

> In the current version, `cancel()` and `close()` have no effect while `idle { }` is still running. Cancel the
> coroutine as shown above. See [](known-limitations.md).
{style="warning"}

## Long-running watchers {id="long-running"}

Servers end idle connections that have been quiet for a while, commonly after about half an hour. Networks drop
them too. When that happens, `idle { }` throws an `ImapConnectionClosedException`.

Kamel does not re-establish the idling on its own. Wrap it in a loop:

```kotlin
launch {
    while (isActive) {
        try {
            inbox.getIdleFolder().idle {
                onNewMessage { arrivals.trySend(it) }
            }
        } catch (e: ImapConnectionClosedException) {
            delay(5.seconds)
        }
    }
}
```

Changes that happen between two idle periods are not reported. If you must not miss anything, compare
`getMailIds()` or the UIDs you know after every reconnect.
