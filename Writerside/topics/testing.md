# Testing without a server

Code that reads mail is hard to test against a real mailbox. Kamel therefore puts what such code uses behind
interfaces, and ships mailboxes that live in memory in a second artifact, `kamel-test`.

## The interfaces {id="interfaces"}

`ImapClient` and `ImapFolder` implement `MailClient` and `MailFolder`, and `getIdleFolder()` returns the interface
`IdleFolder`:

| Interface | Members |
|-----------|---------|
| `MailClient` | `getFolders()` |
| `MailFolder` | `path`, `delimiter`, `specialType`, `fullName`, `name`, `state()`, `status()`, `getMails { }`, `getIdleFolder()` |
| `IdleFolder` | `events()`, `idle { }`, `close()` |

Write the code you want to test against the interfaces:

```kotlin
suspend fun importNewMails(client: MailClient, lastKnownUid: Long): List<String?> =
    client.getFolders()
        .first { it.specialType == ImapFolder.SpecialType.INBOX }
        .getMails { uidRange(lastKnownUid + 1); all() }
        .map { it.subject.await() }
        .toList()
```

In production it gets an `ImapClient`, in a test an `InMemoryMailClient`.

## Add the dependency {id="dependency"}

`kamel-test` is published together with Kamel, under the same version:

```kotlin
dependencies {
    testImplementation("es.jvbabi.overmail:kamel-test:%version%")
}
```

## Build a mailbox {id="mailbox"}

```kotlin
val client = InMemoryMailClient()
val inbox = client.folder("INBOX", specialType = ImapFolder.SpecialType.INBOX)

inbox.add("From: ada@example.com\r\nSubject: Lunch?\r\n\r\nAt one?\r\n")
inbox.add(File("newsletter.eml").readBytes(), flags = setOf(Email.Flag.Seen))

importNewMails(client, lastKnownUid = 1) // only the newsletter
```

`folder(name)` creates the folder, or returns the one of that name. `add` takes a message source, as in an
`.eml` file, and returns the UID the mail got. The folder numbers its mails like a server: UIDs only grow, and
sequence numbers move up when a mail is removed.

| Function | Effect |
|----------|--------|
| `add(source, flags, uid)` | Puts a mail into the folder. Without `uid`, it gets the next one |
| `addUnreadable(cause)` | Puts a mail into the folder that a fetch [cannot read](fetching-messages.md#unreadable) |
| `remove(uid)` | Removes a mail |
| `setFlags(uid, flags)` | Replaces the flags of a mail |
| `resetUids()` | Numbers the mails anew under a new [`uidValidity`](folders.md#state) |
| `removeFolder(name)` on the client | Removes a folder |

`getFolders(onlyRoot = true)` returns an empty list: the account has no hierarchy root to report.

The mails a fetch emits are built with [`Email.parse()`](message-content.md#parse). They carry their envelope
and their content. Like a server, the folder only fills `uid` and `flags` if the request asked for them, so a
test notices a forgotten `uid = true`.

## Check what was fetched {id="fetches"}

`fetches` lists the selection of every `getMails` that was collected. Use it to make sure your code asks for what
is new and not for the whole folder:

```kotlin
importNewMails(client, lastKnownUid = 41)

inbox.fetches shouldBe listOf(FetchRequest.Selection.UidRange(42, null))
```

## Play failures {id="failures"}

Set `failure` on a folder or on the client to make it throw instead of answering:

```kotlin
inbox.failure = ImapConnectionClosedException("The connection closed")
// state(), status() and collecting getMails { } now throw

inbox.failure = null
```

A fetch for a UID or a sequence number the folder does not have gives an empty flow, as with a real folder.

## Watch for changes {id="watching"}

The events of `getIdleFolder()` report what `add`, `remove` and `setFlags` do while somebody collects them. Like
`IDLE`, they do not report what happened before, and the flow never completes.

A collector that was just started may not be listening yet. Wait for it with `awaitWatcher()` before you change
the folder:

```kotlin
val events = async { inbox.getIdleFolder().events().take(1).toList() }
inbox.awaitWatcher()

inbox.add("From: ada@example.com\r\nSubject: Now\r\n\r\nHello\r\n")

events.await() shouldBe listOf(IdleEvent.NewMessage(3))
```

## A folder of your own

If the in-memory folder does not fit, implement `MailFolder` yourself. Read the selection of a request from
`FetchRequest().apply(config).selection`, which is one of `Ids`, `IdRange`, `Uids` and `UidRange`. Report a mail
you cannot deliver with `request.unreadable(...)`.
