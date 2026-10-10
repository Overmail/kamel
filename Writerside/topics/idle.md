# Waiting for changes (IDLE)

With IMAP `IDLE`, the server tells the client about changes in a folder as they happen. Kamel wraps this in
`IdleFolder`.

```kotlin
val idleFolder = inbox.getIdleFolder()

val watcher = launch {
    idleFolder.events().collect { event ->
        when (event) {
            is IdleEvent.NewMessage -> println("new message: ${event.sequenceNumber}")
            is IdleEvent.RemovedMessage -> println("removed: ${event.sequenceNumber}")
            is IdleEvent.FlagsChanged -> println("flags of ${event.sequenceNumber}: ${event.flags}")
        }
    }
}

// later
watcher.cancel()
```

## Events {id="events"}

`events()` returns a `Flow<IdleEvent>`. Collecting it sends `IDLE` on a connection of its own and **suspends
until the collector is cancelled**. Start it in a coroutine you can let run in the background.

| Event | Sent when | Carries |
|-------|-----------|---------|
| `NewMessage` | The server reports a new message count (`EXISTS`) | The new number of messages, which is also the sequence number of the newest one |
| `RemovedMessage` | A message was removed (`EXPUNGE`) | The sequence number the message had |
| `FlagsChanged` | Flags of a message changed (`FETCH ... FLAGS`) | The sequence number and the new list of [flags](message-fields.md#flags) |

> Events name messages by their [sequence number](folders.md#ids), not by their UID. Pass it to `getId()`, not to
> `getUid()`.
{style="warning"}

The collector may suspend, so it can fetch the message an event announces right away:

```kotlin
idleFolder.events().collect { event ->
    if (event is IdleEvent.NewMessage) {
        inbox.getMails { getId(event.sequenceNumber); all() }.collect { println(it.subject.await()) }
    }
}
```

Only one collector at a time watches through an `IdleFolder`. A second one waits until the first has ended. To
watch a folder twice at the same time, call `getIdleFolder()` twice.

## Callbacks {id="callbacks"}

`idle { }` collects the events into callbacks. Register any number of each kind, all of them are called. Like
collecting `events()`, it suspends for as long as it watches.

```kotlin
idleFolder.idle {
    onNewMessage { sequenceNumber -> println("new message: $sequenceNumber") }
    onRemovedMessage { sequenceNumber -> println("removed: $sequenceNumber") }
    onFlagChanged { sequenceNumber, flags -> println("flags of $sequenceNumber: $flags") }
}
```

The callbacks are regular functions. To call a `suspend` function such as collecting `getMails`, use `events()`,
or hand the value over to another coroutine as [](tutorial-watch-mailbox.md) shows step by step.

## Stopping {id="stopping"}

Cancel the coroutine that collects `events()` or runs `idle { }`:

```kotlin
watcher.cancelAndJoin()
```

Kamel then sends `DONE` and waits for the server to complete the `IDLE`, so the connection stays usable. Flow
operators that end a collection, such as `first()` or `take(n)`, do the same.

The connection stays open until `idleFolder.close()`, or until its folder or the client is closed. Calling
`close()` while a collector is still running makes it fail with an `ImapConnectionClosedException`: cancel the
collector first.

## Long-running watchers {id="long-running"}

Servers end idle connections that have been quiet for a while, commonly after about half an hour. To stay ahead
of that, renew the `IDLE` before it happens. Cancelling and collecting again runs on the same connection:

```kotlin
launch {
    while (isActive) {
        withTimeoutOrNull(25.minutes) {
            idleFolder.events().collect { event -> handle(event) }
        }
    }
}
```

Networks drop connections too. Collecting then fails with an `ImapConnectionClosedException`. Kamel does not
reconnect on its own, but the next collection opens a new connection:

```kotlin
launch {
    while (isActive) {
        try {
            withTimeoutOrNull(25.minutes) {
                idleFolder.events().collect { event -> handle(event) }
            }
        } catch (e: ImapConnectionClosedException) {
            delay(5.seconds)
        }
    }
}
```

The [`readTimeout`](connecting.md) of the client does not apply while idling, since a quiet folder is what an
`IDLE` waits for. A connection that died without being closed is noticed at the next renewal.

Changes that happen between two idle periods are not reported. If you must not miss anything, fetch
[what is new](fetching-messages.md#new) after every renewal.
