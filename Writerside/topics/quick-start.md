# Quick start

This guide connects to a mailbox, finds the inbox and prints its messages.

It assumes that Kamel is on your classpath, see [](installation.md), and that your credentials are in the
environment variables `IMAP_USERNAME` and `IMAP_PASSWORD`.

<procedure title="Read the inbox" id="read-inbox">
<step>

**Create a client.** The client does not connect yet. It opens connections when the first command needs one.
`use` closes all of them at the end.

```kotlin
import es.jvbabi.overmail.core.ImapClient
import es.jvbabi.overmail.core.ImapFolder
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    ImapClient(
        host = "imap.example.com",
        port = 993,
        ssl = true,
        auth = ImapClient.Auth.BasicAuth(
            username = System.getenv("IMAP_USERNAME"),
            password = System.getenv("IMAP_PASSWORD"),
        ),
    ).use { client ->
        // the next steps go here
    }
}
```

</step>
<step>

**Pick a folder.** `getFolders()` returns every folder of the account. The inbox is the one with the special type
`INBOX`:

```kotlin
val inbox = client.getFolders().first { it.specialType == ImapFolder.SpecialType.INBOX }
```

</step>
<step>

**Fetch messages.** Say which messages you want and which fields you need. Only those fields are requested from
the server:

```kotlin
val mails = inbox.getMails {
    getAll()
    envelope = true
    flags = true
    uid = true
}
```

</step>
<step>

**Read the fields.** Every field is a `Deferred`. For fields you requested, `await()` returns immediately:

```kotlin
mails.forEach { mail ->
    println("${mail.uid.await()}: ${mail.subject.await()}")
    println("  from:  ${mail.from.await().joinToString()}")
    println("  flags: ${mail.flags.await().joinToString { it.value }}")
}
```

</step>
<step>

**Run it.** You should see one block per message:

```text
15201: Your invoice for October
  from:  Billing <billing@example.com>
  flags: \Seen
```

</step>
</procedure>

> A wrong password makes the first command throw an `ImapCommandException`. See [](error-handling.md).
{style="note"}

## What's next

- Build a small command line mail viewer: [](tutorial-inbox-viewer.md)
- Download the text, the HTML and the attachments of a message: [](message-content.md)
- React to new mail as it arrives: [](idle.md)
- Log in to Gmail or Outlook: [](tutorial-oauth.md)
