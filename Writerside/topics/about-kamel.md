# About Kamel

Kamel is an IMAP client library for Kotlin. It is coroutine-based, built on Ktor sockets and published to Maven
Central as `es.jvbabi.overmail:kamel`.

> Kamel is in an early stage of development. The API is not stable yet and may change between minor versions.
> Kamel only reads mail at the moment. Check [](known-limitations.md) before you build on it.
{style="warning"}

> **AI disclaimer:** Much of the code before 1.0 and large parts of this documentation were written with the help
> of AI tools, so mistakes can slip through. The library is being reworked piece by piece for 1.0, see
> [](known-limitations.md#pre-1-0). Please [open an issue](https://github.com/Overmail/kamel/issues) if something
> looks wrong or doesn't match the actual behaviour.
{style="note"}

## What it does

You create an `ImapClient`, ask it for folders, and fetch messages from a folder. A fetch names the fields it needs,
so only those travel over the wire:

```kotlin
ImapClient(
    host = "imap.example.com",
    port = 993,
    auth = ImapClient.Auth.BasicAuth(username, password),
).use { client ->
    val inbox = client.getFolders().first { it.specialType == ImapFolder.SpecialType.INBOX }

    inbox.getMails {
        getAll()
        envelope = true
        uid = true
    }.forEach { mail ->
        println("${mail.uid.await()}: ${mail.subject.await()}")
    }
}
```

## Features

- **Login** with username and password, or with an OAuth 2.0 access token (SASL `XOAUTH2`) for Gmail and Outlook.
- **Folders**: list every folder of an account, including its special use (inbox, sent, drafts, spam, trash).
- **Messages**: fetch envelopes, flags and UIDs for a single message, a range or a whole folder.
- **Bodies**: plain text, HTML and attachments, already decoded. Or the raw message source as a stream.
- **IDLE**: get notified about new, removed and changed messages without polling.
- **Connection pooling**: commands run in parallel on several connections, opened on demand.

## Where to go next

<deflist>
<def title="New to Kamel?">

Start with [](installation.md) and [](quick-start.md).

</def>
<def title="Prefer to learn by building something?">

Follow the tutorials: [](tutorial-inbox-viewer.md), [](tutorial-save-attachments.md), [](tutorial-watch-mailbox.md)
and [](tutorial-oauth.md).

</def>
<def title="Looking for a specific feature?">

The pages under **Using Kamel** cover one topic each, from [](connecting.md) to [](utilities.md).

</def>
<def title="Need every class and function?">

See the [Kotlin API reference](https://overmail.github.io/kamel/api/), generated with Dokka.

</def>
</deflist>

## The name

Kamel is German for camel. The original name was K-Mail (for Kotlin Mail). To avoid confusion with KMail (the KDE
mail client) and K-9 Mail (the Android mail client), it was renamed to the similar-sounding Kamel.
