# Tutorial: Watch a mailbox for new mail

In this tutorial you build a program that prints the subject of every message the moment it arrives, without
polling.

You will use `getIdleFolder()` and `idle { }`.

## Before you start

- You have a working client and an `inbox` folder, as built in [](tutorial-inbox-viewer.md).

## How it works

IMAP `IDLE` keeps a connection open on which the server reports changes of a folder. `idle { }` suspends for as
long as that lasts and calls your callbacks. The callbacks are plain functions, not `suspend` functions, so they
cannot fetch a message themselves. They hand the work to another coroutine through a channel.

## Start watching

<procedure title="Listen for new messages" id="listen">
<step>

Create the channel and the idle folder:

```kotlin
val arrivals = Channel<Long>(Channel.UNLIMITED)
val idleFolder = inbox.getIdleFolder()
```

</step>
<step>

Start `idle` in its own coroutine. `onNewMessage` receives the sequence number of the new message:

```kotlin
val watcher = launch {
    idleFolder.idle {
        onNewMessage { sequenceNumber -> arrivals.trySend(sequenceNumber) }
        onRemovedMessage { sequenceNumber -> println("Message $sequenceNumber was removed") }
        onFlagChanged { sequenceNumber, flags -> println("Message $sequenceNumber now has $flags") }
    }
}
```

</step>
</procedure>

## Fetch what arrived

<procedure title="Print each new message" id="print">
<step>

In a second coroutine, fetch every announced message by its sequence number. This runs on another connection of
the folder, so it does not disturb the idling one:

```kotlin
val printer = launch {
    for (sequenceNumber in arrivals) {
        inbox.getMails {
            getId(sequenceNumber)
            all()
        }.forEach { mail ->
            println("New mail from ${mail.from.await().joinToString()}: ${mail.subject.await()}")
        }
    }
}
```

</step>
<step>

Send yourself a mail. The program prints it within a second or two:

```text
New mail from Ada Lovelace <ada@example.com>: Lunch?
```

</step>
</procedure>

## Stop watching

<procedure title="Shut down cleanly" id="stop">
<step>

Cancel the coroutine that runs `idle { }`. Then close the channel so the printer finishes:

```kotlin
delay(10.minutes)

watcher.cancel()
arrivals.close()
printer.join()
```

The idle connection is closed together with the client at the end of `use`.

</step>
</procedure>

## The whole program

```kotlin
import es.jvbabi.overmail.core.ImapClient
import es.jvbabi.overmail.core.ImapFolder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.minutes

fun main() = runBlocking {
    ImapClient(
        host = "imap.example.com",
        port = 993,
        auth = ImapClient.Auth.BasicAuth(
            username = System.getenv("IMAP_USERNAME"),
            password = System.getenv("IMAP_PASSWORD"),
        ),
    ).use { client ->
        val inbox = client.getFolders().first { it.specialType == ImapFolder.SpecialType.INBOX }

        val arrivals = Channel<Long>(Channel.UNLIMITED)
        val idleFolder = inbox.getIdleFolder()

        val watcher = launch {
            idleFolder.idle {
                onNewMessage { sequenceNumber -> arrivals.trySend(sequenceNumber) }
            }
        }

        val printer = launch {
            for (sequenceNumber in arrivals) {
                inbox.getMails {
                    getId(sequenceNumber)
                    all()
                }.forEach { mail ->
                    println("New mail from ${mail.from.await().joinToString()}: ${mail.subject.await()}")
                }
            }
        }

        delay(10.minutes)

        watcher.cancel()
        arrivals.close()
        printer.join()
    }
}
```

## What you learned

- `idle { }` suspends for as long as it listens, so it gets its own coroutine. Cancelling that coroutine stops it.
- The callbacks receive sequence numbers, which you can pass to `getId()`.
- Servers end idle connections after a while. For a long-running watcher, see [](idle.md#long-running).

Next: [](tutorial-oauth.md).
