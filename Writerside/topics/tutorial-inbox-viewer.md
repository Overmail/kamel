# Tutorial: Build an inbox viewer

In this tutorial you build a small command line program that lists the folders of a mailbox and prints the ten
newest messages of the inbox.

You will use `ImapClient`, `getFolders()`, `getMailIds()` and `getMails { }`.

## Before you start

- Kamel and `kotlinx-coroutines-core` are on your classpath, see [](installation.md).
- Your credentials are in the environment variables `IMAP_USERNAME` and `IMAP_PASSWORD`.

## Connect

<procedure title="Open the mailbox" id="open-mailbox">
<step>

Create the client inside `runBlocking` and close it with `use`:

```kotlin
import es.jvbabi.overmail.core.ImapClient
import es.jvbabi.overmail.core.ImapFolder
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    ImapClient(
        host = "imap.example.com",
        port = 993,
        auth = ImapClient.Auth.BasicAuth(
            username = System.getenv("IMAP_USERNAME"),
            password = System.getenv("IMAP_PASSWORD"),
        ),
    ).use { client ->
        client.testConnection()
        println("Connected to ${client.host}")
    }
}
```

`testConnection()` opens a connection and logs in. Without it, a wrong password would only show up at the first
real command.

</step>
</procedure>

## List the folders

<procedure title="Print every folder" id="list-folders">
<step>

Ask the client for its folders and print them. `fullName` is the name the server uses, `specialType` tells you
what the folder is for:

```kotlin
val folders = client.getFolders()

folders.forEach { folder ->
    val type = folder.specialType?.let { " [$it]" }.orEmpty()
    println("${folder.fullName}$type")
}
```

```text
INBOX [INBOX]
Drafts [DRAFTS]
Sent [SENT]
Archive
Archive/2025
Trash [TRASH]
```

</step>
<step>

Keep a reference to the inbox for the next part:

```kotlin
val inbox = folders.first { it.specialType == ImapFolder.SpecialType.INBOX }
```

</step>
</procedure>

## Print the newest messages

<procedure title="Fetch the last ten messages" id="newest-messages">
<step>

`getMailIds()` returns the sequence numbers of all messages in the folder, oldest first. Take the last ten:

```kotlin
val newestIds = inbox.getMailIds().takeLast(10)
println("${inbox.fullName}: showing ${newestIds.size} newest messages")
```

</step>
<step>

Fetch that range. `all()` requests envelope, flags and UID in one go:

```kotlin
val mails = inbox.getMails {
    getIds(newestIds.map { it.toLong() })
    all()
}
```

</step>
<step>

Print the messages, newest first. Mark unread ones with a star:

```kotlin
mails.reversed().forEach { mail ->
    val unread = Email.Flag.Seen !in mail.flags.await()
    val sender = mail.from.await().firstOrNull()

    println(buildString {
        append(if (unread) "* " else "  ")
        append(mail.sentAt.await())
        append("  ")
        append(sender?.name ?: sender?.address ?: "<unknown>")
        append("  ")
        append(mail.subject.await() ?: "<no subject>")
    })
}
```

Add `import es.jvbabi.overmail.core.Email` for the flag.

```text
* 2026-10-09T08:12:44Z  Billing  Your invoice for October
  2026-10-08T17:03:10Z  Ada Lovelace  Re: Notes from Tuesday
```

</step>
</procedure>

## What you learned

- A client opens its connections on demand and is closed with `use`. More in [](connecting.md).
- Folders carry their path and their special use. More in [](folders.md).
- A fetch selects messages and fields. More in [](fetching-messages.md) and [](message-fields.md).

Next: [](tutorial-save-attachments.md).
